package policy

import org.scalatest.funsuite.AnyFunSuite
import okay.cardano.Attacks
import scalus.uplc.builtin.ByteString
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v1.{Address, Interval}
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.crypto.trie.MerklePatriciaForestry as Trie
import scalus.testing.kit.TestUtil

/** THE AUDIT'S KNOWN EXPLOITS (specs/audit.md): okay-cardano's Attacks on the validator's honest transactions — a
  * payment, a deposit, the owner's change, the birth — each changed the way a known Cardano exploit changes a
  * transaction (double satisfaction, a redirected payment, a stolen continuing output, token dust, forged state, an
  * extra mint, a sneaky burn, …). An accepted HIGH attack is a finding; every attempt is printed for the record. */
class AttacksSpec extends AnyFunSuite {
    private val owner = TestUtil.mockPubKeyHash(1)
    private val agent = TestUtil.mockPubKeyHash(2)
    private val cloud = Credential.PubKeyCredential(TestUtil.mockPubKeyHash(3))
    private val seed = TestUtil.mockTxOutRef(5, 0)
    private val script = SpendPolicyContract.compiled.withErrorTraces(seed.toData).program.deBruijnedProgram
    private val policy = TestUtil.mockScriptHash(7)
    private val home = Address(Credential.ScriptCredential(policy), POption.None)
    private val nft = Value(policy, SpendPolicy.stateName, 1)
    private val usd = AssetClass(TestUtil.mockScriptHash(11), ByteString.fromString("tUSD"))
    private def tusd(q: BigInt) = Value(usd.policy, usd.name, q)
    private val day = BigInt(86_400_000L)
    private val t0 = BigInt(1_800_000_000_000L)
    private val st = PolicyState(owner, agent, usd, day * 30, t0, 400, 300, 100, t0 + day * 365, false, 0, 0,
        List(Payee(cloud, 150, 0, 0, 0, 0)), Trie.empty.rootHash)
    private val held = Value.lovelace(5_000_000) + nft + tusd(1000)
    private val ref = TestUtil.mockTxOutRef(9, 0)
    private def out(s: PolicyState, v: Value) = TxOut(home, v, OutputDatum.OutputDatum(s.toData))
    private def spend(outs: List[TxOut], action: PolicyAction, signers: List[PubKeyHash], range: Interval) =
        val tx = TxInfo.placeholder.copy(inputs = List(TxInInfo(ref, out(st, held))), outputs = outs,
            signatories = signers, validRange = range)
        ScriptContext(tx, action.toData, ScriptInfo.SpendingScript(ref, POption.None))

    private val invoice = ByteString.fromString("INV-1")
    private val at = t0 + day
    private val next = st.copy(window = 0, spent = 90,
        payees = List(Payee(cloud, 150, 0, 90, 0, at)), invoices = Trie.empty.insert(invoice, ByteString.empty).rootHash)
    private val scenarios = scala.List(
        "a payment" -> spend(List(out(next, held - tusd(90)), TxOut(Address(cloud, POption.None),
            Value.lovelace(2_000_000) + tusd(90), OutputDatum.OutputDatum(Receipt(policy, invoice).toData))),
            PolicyAction.Pay(cloud, 90, invoice, Trie.empty.proveNonMembership(invoice)), List(agent),
            Interval.between(at, at + 600_000)),
        "a deposit" -> spend(List(out(st, held + tusd(500))), PolicyAction.Deposit, List(),
            Interval.always),
        "the owner's change" -> spend(List(out(st.copy(periodCap = 500), held - tusd(300))), PolicyAction.Owner,
            List(owner), Interval.always),
        "the birth" -> {
            val tx = TxInfo.placeholder.copy(inputs = List(TxInInfo(seed, TxOut(Address.fromPubKeyHash(owner),
                Value.lovelace(200_000_000), OutputDatum.NoOutputDatum))), outputs = List(out(st, held)),
                signatories = List(owner), mint = nft)
            ScriptContext(tx, PolicyMint.Init.toData, ScriptInfo.MintingScript(policy))
        })

    for (name, ctx) <- scenarios do
        test(s"known exploits on $name: none accepted") {
            val tried = Attacks.run(script, ctx).fold(e => fail(e), identity)
            for t <- tried do
                val verdict = if t.moot then "moot" else if t.accepted then "ACCEPTED" else "defended"
                println(f"  [$name] ${t.attack.severity}%-6s ${t.attack.id}%-24s $verdict" +
                    t.defence.filter(_ => !t.accepted).fold("")(d => s" — ${d.take(90)}"))
            val high = tried.filter(t => t.accepted && t.attack.severity == Attacks.Severity.High)
            assert(high.isEmpty, high.map(t => s"${t.attack.id}: ${t.attack.claim}").mkString("\n"))
        }
}
