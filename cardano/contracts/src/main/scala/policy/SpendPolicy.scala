package policy

import scalus.compiler.Compile
import scalus.uplc.builtin.{ByteString, Data, FromData, ToData}
import scalus.uplc.builtin.ByteString.utf8
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v1.PolicyId
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*
import scalus.cardano.onchain.plutus.crypto.trie.MerklePatriciaForestry
import scalus.cardano.onchain.plutus.crypto.trie.MerklePatriciaForestry.Proof

/** the stablecoin the policy holds */
case class AssetClass(policy: PolicyId, name: ByteString) derives FromData, ToData

/** a payee the owner allowed: its payment credential, the most it may get a window, the least time between its
  * payments (ms); this window's spending on it, the window, its last payment's time (0: never) */
case class Payee(who: Credential, cap: BigInt, gap: BigInt, spent: BigInt, window: BigInt, last: BigInt)
    derives FromData, ToData

/** THE POLICY'S STATE (specs/allowance.md §9), the inline datum of the one UTxO carrying its NFT: the owner, the
  * agent, the token; the window's length (ms) and its start, the most a window, a payment, and without the owner's
  * signature; when it ends (ms); paused; this window's index and spending; the payees; the invoices ever paid, the
  * root of a Merkle Patricia Forestry (32 bytes, however many) */
case class PolicyState(owner: PubKeyHash, agent: PubKeyHash, token: AssetClass, period: BigInt, start: BigInt,
    periodCap: BigInt, maxPayment: BigInt, approvalAbove: BigInt, expires: BigInt, paused: Boolean, window: BigInt,
    spent: BigInt, payees: List[Payee], invoices: ByteString)
    derives FromData, ToData

enum PolicyAction derives FromData, ToData:
    /** `proof`: the invoice is not among those paid (a non-membership proof of the MPF) */
    case Pay(payee: Credential, amount: BigInt, invoice: ByteString, proof: Proof)
    case Deposit
    case Owner

enum PolicyMint derives FromData, ToData:
    case Init
    case Burn

/** THE AGENT'S ALLOWANCE ON CARDANO (specs/allowance.md §9): the budget is the UTxO carrying the policy's NFT, at the
  * script's address; the agent pays from it within the owner's rules, anyone tops it up, the owner does anything.
  * One script, the minting policy of its NFT and the spending validator. The errors are SpendPolicy.sol's names.
  *
  * The attacks, and what stops each:
  *   - A STRANGER, OR THE OWNER'S KEY ALONE, PAYS: only the agent's signature opens Pay (NotAgent).
  *   - THE AGENT PAYS ITSELF, OR AN ADDRESS FROM AN INVOICE: only an allowed payee's credential (PayeeNotAllowed);
  *     the amount must reach that credential's outputs (NotPaid).
  *   - THE AGENT TAKES MORE THAN IT PAYS: the one output back holds the balance less the amount, exactly, and the
  *     next state, exactly, compared as data (BalanceChanged, StateChanged).
  *   - TWICE THE SAME INVOICE: every invoice paid is in the MPF whose root the state keeps; a payment proves its
  *     invoice absent and the next root is the old one with it inserted (AlreadyPaid: no such proof exists).
  *   - A BURST OF PAYMENTS (an old start reused): the validity window at most an hour, its start never before the
  *     state's window nor the payee's gap.
  *   - FORGED STATE, DOUBLE SATISFACTION, STAKE HIJACK: as the vault — only the UTxO with the NFT is the state, one
  *     policy input and one output per transaction, no stake part at birth, the same address on.
  */
@Compile
object SpendPolicy extends DataParameterizedValidator {

    def stateName: ByteString = utf8"POLICY"
    def hour: BigInt = BigInt(3_600_000)

    inline override def mint(seedData: Data, redeemer: Data, policyId: PolicyId, tx: TxInfo): Unit = {
        redeemer.to[PolicyMint] match
            case PolicyMint.Init =>
                tx.findInputOrFail(seedData.to[TxOutRef], SeedNotSpent)
                require(tx.mint.hasOnly(policyId, stateName, BigInt(1)), MintExactlyOne)
                val outs = tx.findOutputsByCredential(Credential.ScriptCredential(policyId))
                require(outs.length == BigInt(1), OneOutput)
                val out = outs.head
                require(out.address.stakingCredential.isEmpty, NoStakeAtInit)
                val st = inlineState(out)
                require(holdsOnly(out.value, policyId, st.token), OnlyTheToken)
                require(tx.isSignedBy(st.owner), OwnerMustSign)
            case PolicyMint.Burn =>
                require(tx.mint.hasOnly(policyId, stateName, BigInt(-1)), BurnExactlyOne)
    }

    inline override def spend(seedData: Data, datum: Option[Data], redeemer: Data, tx: TxInfo,
        ownRef: TxOutRef): Unit = {
        val own = tx.findInputOrFail(ownRef, NoOwnInput).resolved
        val ownCred = own.address.credential
        val ownHash = ownCred match
            case Credential.ScriptCredential(h) => h
            case _                              => fail(NotAScript)
        require(own.value.quantityOf(ownHash, stateName) == BigInt(1), NotThePolicy)
        require(tx.findInputsByCredential(ownCred).length == BigInt(1), OneInput)
        val st = inlineState(own)
        redeemer.to[PolicyAction] match
            case PolicyAction.Pay(payee, amount, invoice, proof) =>
                require(tx.isSignedBy(st.agent), NotAgent)
                require(!st.paused, Paused)
                require(tx.mint.tokens(ownHash).isEmpty, NoMint)
                val from = tx.validFromOrFail(NoStart)
                val to = tx.validToOrFail(NoEnd)
                require(to - from <= hour, WindowTooLong)
                require(to <= st.expires, Expired)
                // the rules in SpendPolicy.sol's order: a payment breaking two is refused for the same one on both
                require(amount > BigInt(0), ZeroAmount)
                val p = st.payees.find(x => x.who === payee) match
                    case Option.Some(x) => x
                    case Option.None    => fail(PayeeNotAllowed)
                require(amount <= st.maxPayment, OverMax)
                require(from >= st.start, NotStarted)
                val w = (from - st.start) / st.period
                require(w >= st.window, BadTime)
                // AlreadyPaid: the invoice absent from those paid, by the proof, and inserted — the MPF's own refusal
                // ("Invalid proof or element exists") when it is there: no proof of absence exists
                val invoices = MerklePatriciaForestry(st.invoices).insert(invoice, ByteString.empty, proof).root
                require(p.last == BigInt(0) || from >= p.last + p.gap, TooSoon)
                val own1 = (if p.window == w then p.spent else BigInt(0)) + amount
                require(own1 <= p.cap, PayeeCap)
                val total = (if st.window == w then st.spent else BigInt(0)) + amount
                require(total <= st.periodCap, PeriodCap)
                if amount > st.approvalAbove then require(tx.isSignedBy(st.owner), NeedsOwner)
                val balance = own.value.quantityOf(st.token.policy, st.token.name)
                require(amount <= balance, InsufficientBalance)
                val out = theOutput(tx, ownCred)
                require(out.address === own.address, SameAddress)
                val next = PolicyState(st.owner, st.agent, st.token, st.period, st.start, st.periodCap,
                    st.maxPayment, st.approvalAbove, st.expires, st.paused, w, total,
                    st.payees.map(x => if x.who === payee then Payee(payee, p.cap, p.gap, own1, w, from) else x),
                    invoices)
                require(outDatum(out) == next.toData, StateChanged)
                require(out.value.quantityOf(st.token.policy, st.token.name) == balance - amount, BalanceChanged)
                require(out.value.getLovelace >= own.value.getLovelace, BalanceChanged)
                require(holdsOnly(out.value, ownHash, st.token), OnlyTheToken)
                val got = tx.outputs.foldLeft(BigInt(0)) { (acc, o) =>
                    if o.address.credential === payee then acc + o.value.quantityOf(st.token.policy, st.token.name)
                    else acc
                }
                require(got >= amount, NotPaid)
            case PolicyAction.Deposit =>
                require(tx.mint.tokens(ownHash).isEmpty, NoMint)
                val out = theOutput(tx, ownCred)
                require(out.address === own.address, SameAddress)
                require(outDatum(out) == st.toData, StateChanged)
                require(out.value.quantityOf(st.token.policy, st.token.name) >
                    own.value.quantityOf(st.token.policy, st.token.name), DepositNothing)
                require(out.value.getLovelace >= own.value.getLovelace, BalanceChanged)
                require(holdsOnly(out.value, ownHash, st.token), OnlyTheToken)
            case PolicyAction.Owner =>
                require(tx.isSignedBy(st.owner), OwnerMustSign)
                // closing burns the NFT; else the state goes on, valid, at the same address
                if !tx.mint.hasOnly(ownHash, stateName, BigInt(-1)) then
                    val out = theOutput(tx, ownCred)
                    require(out.address === own.address, SameAddress)
                    val next = inlineState(out)
                    require(holdsOnly(out.value, ownHash, next.token), OnlyTheToken)
    }

    /** the one output back to the policy's credential */
    def theOutput(tx: TxInfo, cred: Credential): TxOut =
        val outs = tx.findOutputsByCredential(cred)
        require(outs.length == BigInt(1), OneOutput)
        outs.head

    /** ada, the one NFT, and the token only */
    def holdsOnly(v: Value, policy: PolicyId, token: AssetClass): Boolean =
        v.quantityOf(policy, stateName) == BigInt(1) &&
            v.withoutLovelace.flatten.forall { case (p, n, _) =>
                (p === policy && n === stateName) || (p === token.policy && n === token.name)
            }

    def outDatum(out: TxOut): Data = out.datum match
        case OutputDatum.OutputDatum(d) => d
        case _                          => fail(InlineDatumRequired)

    /** the inline datum, decoded and valid */
    def inlineState(out: TxOut): PolicyState =
        val st = outDatum(out).to[PolicyState]
        require(st.owner.hash.length == BigInt(28) && st.agent.hash.length == BigInt(28), BadKeys)
        require(st.token.policy.length == BigInt(28), BadToken)
        require(st.period > BigInt(0), BadPeriod)
        require(st.invoices.length == BigInt(32), BadInvoices)
        st

    inline val SeedNotSpent = "SeedNotSpent: the seed UTxO must be spent"
    inline val MintExactlyOne = "MintExactlyOne: exactly one policy token is minted"
    inline val OneOutput = "OneOutput: exactly one output to the policy"
    inline val NoStakeAtInit = "NoStakeAtInit: the policy starts at the script address with no stake part"
    inline val OnlyTheToken = "OnlyTheToken: the policy holds ada, its NFT and its token only"
    inline val OwnerMustSign = "NotOwner: the owner signs"
    inline val BurnExactlyOne = "BurnExactlyOne: burn exactly one policy token"
    inline val NoOwnInput = "NoOwnInput: own input not found"
    inline val NotAScript = "NotAScript: own input is not at a script"
    inline val NotThePolicy = "NotThePolicy: the input does not carry the policy's NFT"
    inline val OneInput = "OneInput: exactly one input from the policy"
    inline val SameAddress = "SameAddress: the policy stays at the same address"
    inline val NoMint = "NoMint: a payment or a deposit mints nothing of the policy's"
    inline val NoStart = "NoStart: a payment has a start"
    inline val NoEnd = "NoEnd: a payment has an end"
    inline val WindowTooLong = "WindowTooLong: a payment's validity is at most an hour"
    inline val NotAgent = "NotAgent: the agent signs"
    inline val Paused = "Paused: the owner paused the policy"
    inline val Expired = "Expired: the policy has ended"
    inline val ZeroAmount = "ZeroAmount: a payment of nothing"
    inline val OverMax = "OverMax: above the most a payment"
    inline val PayeeNotAllowed = "PayeeNotAllowed: not a payee the owner allowed"
    inline val NotStarted = "NotStarted: before the policy's start"
    inline val BadTime = "BadTime: before the window already recorded"
    inline val AlreadyPaid = "AlreadyPaid: the invoice was paid"
    inline val PayeeCap = "PayeeCap: above the payee's cap for the window"
    inline val PeriodCap = "PeriodCap: above the window's cap"
    inline val TooSoon = "TooSoon: sooner than the payee's gap"
    inline val NeedsOwner = "NeedsOwner: above approvalAbove the owner signs too"
    inline val InsufficientBalance = "InsufficientBalance: more than the policy holds"
    inline val StateChanged = "StateChanged: the next state is not the one the rules give"
    inline val BalanceChanged = "BalanceChanged: the policy keeps its balance less the amount, and its ada"
    inline val NotPaid = "NotPaid: the payee's outputs do not get the amount"
    inline val DepositNothing = "DepositNothing: a deposit adds the token"
    inline val InlineDatumRequired = "InlineDatumRequired: an inline datum is required"
    inline val BadKeys = "BadKeys: the owner and the agent are 28-byte key hashes"
    inline val BadToken = "BadToken: the token is a native token"
    inline val BadPeriod = "BadPeriod: the window's length is positive"
    inline val BadInvoices = "BadInvoices: the invoices paid are a 32-byte MPF root"
}
