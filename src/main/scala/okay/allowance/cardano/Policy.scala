package okay.allowance.cardano

import scalus.cardano.address.{Address, Network, ShelleyAddress, ShelleyDelegationPart, ShelleyPaymentPart}
import scalus.cardano.ledger.{AddrKeyHash, AssetName, CardanoInfo, Script, SlotNo, Utxo, ValidityInterval,
  Value as LValue}
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{Credential, TxOutRef}
import scalus.cardano.onchain.plutus.prelude.List
import scalus.cardano.txbuilder.TxBuilder
import scalus.uplc.DeBruijnedProgram
import scalus.crypto.trie.MerklePatriciaForestry as Trie
import scalus.cardano.onchain.plutus.crypto.trie.MerklePatriciaForestry.Proof
import scalus.uplc.builtin.{ByteString, FromData, ToData}
import scalus.uplc.builtin.Data.toData

// THE POLICY'S DATA, OFF-CHAIN: the same types, fields and order as cardano/contracts SpendPolicy.scala — the same Data

case class AssetClass(policy: ByteString, name: ByteString) derives FromData, ToData
case class Payee(who: Credential, cap: BigInt, gap: BigInt, spent: BigInt, window: BigInt, last: BigInt)
    derives FromData, ToData
case class PolicyState(owner: PubKeyHash, agent: PubKeyHash, token: AssetClass, period: BigInt, start: BigInt,
    periodCap: BigInt, maxPayment: BigInt, approvalAbove: BigInt, expires: BigInt, paused: Boolean, window: BigInt,
    spent: BigInt, payees: List[Payee], invoices: ByteString)
    derives FromData, ToData:

  /** THE STATE AFTER A PAYMENT, as the script computes it: the window at `at` (ms), the spending, the payee's,
   * the invoices paid — `invoices`, the trie's new root */
  def after(who: Credential, amount: BigInt, invoices: ByteString, at: BigInt): PolicyState =
    val w = (at - start) / period
    copy(window = w, spent = (if w == window then spent else BigInt(0)) + amount,
      payees = payees.map(p => if p.who == who then p.copy(spent = (if p.window == w then p.spent else BigInt(0)) +
        amount, window = w, last = at) else p),
      invoices = invoices)

  def payee(who: Credential): Option[Payee] = payees.find(_.who == who) match
    case scalus.cardano.onchain.plutus.prelude.Option.Some(p) => Some(p)
    case _ => None

enum PolicyAction derives FromData, ToData:
  case Pay(payee: Credential, amount: BigInt, invoice: ByteString, proof: Proof)
  case Deposit
  case Owner

enum PolicyMint derives FromData, ToData:
  case Init
  case Burn

/**
 * THE SPEND POLICY OFF-CHAIN (specs/allowance.md §9): its script for a seed, its address and NFT, and the
 * transactions that create it, top it up, pay from it and close it — built by Scalus's `TxBuilder`, to be completed
 * (fees, change, collateral) by the one who pays the fee and signed by those who must.
 */
object Policy:
  val nftName: AssetName = AssetName(ByteString.fromString("POLICY"))
  private lazy val program = DeBruijnedProgram.fromCborHex(PolicyCode.cborHex)

  def script(seed: TxOutRef): Script.PlutusV3 = Script.PlutusV3(
    ByteString.unsafeFromArray(program.applyArg(seed.toData).cborEncoded))
  def address(script: Script.PlutusV3, network: Network): ShelleyAddress =
    ShelleyAddress(network, ShelleyPaymentPart.Script(script.scriptHash), ShelleyDelegationPart.Null)
  def nft(script: Script.PlutusV3): LValue = LValue.asset(script.scriptHash, nftName, 1L)
  def token(st: PolicyState, q: BigInt): LValue =
    LValue.asset(scalus.cardano.ledger.ScriptHash.fromByteString(st.token.policy), AssetName(st.token.name), q.toLong)

  /** an address's payment credential, as the script sees it */
  def credential(a: Address): Credential = a match
    case s: ShelleyAddress => s.payment match
      case ShelleyPaymentPart.Key(h) => Credential.PubKeyCredential(PubKeyHash(h))
      case ShelleyPaymentPart.Script(h) => Credential.ScriptCredential(h)
    case other => throw new IllegalArgumentException(s"not a Shelley address: $other")

  /** CREATE: the seed spent, the NFT minted, the ada and the first deposit to the policy's address with its state */
  def create(env: CardanoInfo, seed: Utxo, script: Script.PlutusV3, holds: LValue, state: PolicyState): TxBuilder =
    TxBuilder(env)
      .spend(seed)
      .mint(script, Map(nftName -> 1L), PolicyMint.Init)
      .payTo(address(script, env.network), holds + nft(script), state)
      .requireSignature(AddrKeyHash(state.owner.hash))

  /** DEPOSIT: anyone adds the token; the state as it was */
  def deposit(env: CardanoInfo, policy: Utxo, script: Script.PlutusV3, state: PolicyState, amount: BigInt)
      : TxBuilder =
    TxBuilder(env)
      .spend(policy, PolicyAction.Deposit, script)
      .payTo(address(script, env.network), policy.output.value + token(state, amount), state)

  /** PAY: the agent pays `amount` to `to` for `invoice`, valid for ten minutes from the slot `from` (its start is the
   * payment's time); above approvalAbove the owner's signature is required too. `paid`: the invoices paid, the trie
   * whose root the state keeps — the proof of this one's absence from it, the new root with it */
  def pay(env: CardanoInfo, policy: Utxo, script: Script.PlutusV3, state: PolicyState, to: Address, amount: BigInt,
      invoice: ByteString, from: SlotNo, paid: Trie): TxBuilder =
    val at = BigInt(env.slotConfig.slotToTime(from))
    val who = credential(to)
    // an invoice paid has no proof of absence: a stale one (the empty trie's) is sent, and the script refuses it
    val again = paid.get(invoice).isDefined
    val proof = if again then Trie.empty.proveNonMembership(invoice) else paid.proveNonMembership(invoice)
    val root = if again then paid.rootHash else paid.insert(invoice, ByteString.empty).rootHash
    val b = TxBuilder(env)
      .spend(policy, PolicyAction.Pay(who, amount, invoice, proof), script)
      .payTo(address(script, env.network), policy.output.value - token(state, amount),
        state.after(who, amount, root, at))
      .payTo(to, LValue.ada(2) + token(state, amount))
      .requireSignature(AddrKeyHash(state.agent.hash))
      .validDuring(ValidityInterval(Some(from), Some(from + 600)))
    if amount > state.approvalAbove then b.requireSignature(AddrKeyHash(state.owner.hash)) else b

  /** CLOSE: the owner takes everything; the NFT burned */
  def close(env: CardanoInfo, policy: Utxo, script: Script.PlutusV3, state: PolicyState, to: Address): TxBuilder =
    TxBuilder(env)
      .spend(policy, PolicyAction.Owner, script)
      .mint(script, Map(nftName -> -1L), PolicyMint.Burn)
      .payTo(to, policy.output.value - nft(script))
      .requireSignature(AddrKeyHash(state.owner.hash))
