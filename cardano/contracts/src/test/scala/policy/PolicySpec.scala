package policy

import org.scalatest.funsuite.AnyFunSuite
import scalus.uplc.builtin.ByteString
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.Result
import scalus.cardano.onchain.plutus.v1.{Address, Interval}
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.testing.kit.{ScalusTest, TestUtil}
import scalus.crypto.trie.MerklePatriciaForestry as Trie
import scalus.cardano.onchain.plutus.crypto.trie.MerklePatriciaForestry.Proof

/** THE POLICY ON CARDANO (specs/allowance.md §9): the agent's payments, a deposit, the owner, the birth — and every
  * attack refused for its own reason, named as SpendPolicy.sol names it */
class PolicySpec extends AnyFunSuite, ScalusTest {
    val owner: PubKeyHash = TestUtil.mockPubKeyHash(1)
    val agent: PubKeyHash = TestUtil.mockPubKeyHash(2)
    val eve: PubKeyHash = TestUtil.mockPubKeyHash(66)
    val cloud: Credential = Credential.PubKeyCredential(TestUtil.mockPubKeyHash(3))
    val net: Credential = Credential.PubKeyCredential(TestUtil.mockPubKeyHash(4))

    private val seed = TestUtil.mockTxOutRef(5, 0)
    private val program = SpendPolicyContract.compiled.withErrorTraces(seed.toData).program
    private val policy = TestUtil.mockScriptHash(7)
    private val home = Address(Credential.ScriptCredential(policy), POption.None)
    private val nft = Value(policy, SpendPolicy.stateName, 1)
    private val usd = AssetClass(TestUtil.mockScriptHash(11), ByteString.fromString("tUSD"))
    private val junk = AssetClass(TestUtil.mockScriptHash(12), ByteString.fromString("JUNK"))
    private def of(a: AssetClass, q: BigInt) = Value(a.policy, a.name, q)
    private def ada(l: BigInt) = Value.lovelace(l)
    private def tusd(q: BigInt) = of(usd, q)
    private val hour = BigInt(3_600_000)
    private val day = hour * 24
    private val t0 = BigInt(1_800_000_000_000L)
    private def inv(s: String) = ByteString.fromString(s)

    // a month's window from t0; 400 a month, 300 a payment, the owner's signature above 100; a year
    private val st = PolicyState(owner, agent, usd, day * 30, t0, 400, 300, 100, t0 + day * 365, false, 0, 0,
        List(Payee(cloud, 150, 0, 0, 0, 0), Payee(net, 80, day * 25, 0, 0, 0)), Trie.empty.rootHash)
    private val held = ada(5_000_000) + nft + tusd(1000)
    private val ref = TestUtil.mockTxOutRef(9, 0)

    private def out(s: PolicyState, value: Value, at: Address = home) =
        TxOut(at, value, OutputDatum.OutputDatum(s.toData))
    private def to(c: Credential, v: Value) = TxOut(Address(c, POption.None), v, OutputDatum.NoOutputDatum)
    /** a payment's output to the payee, naming the payment: `by` the policy that pays, for invoice `id` */
    private def paid(c: Credential, v: Value, id: String, by: PolicyId = policy) =
        TxOut(Address(c, POption.None), v, OutputDatum.OutputDatum(Receipt(by, inv(id)).toData))

    private def spend(ins: List[TxInInfo], outs: List[TxOut], action: PolicyAction, signers: List[PubKeyHash],
        range: Interval = Interval.always, minted: Value = Value.zero): Result =
        val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers, mint = minted,
            validRange = range)
        program.runWithDebug(ScriptContext(tx, action.toData, ScriptInfo.SpendingScript(ref, POption.None)))

    /** the invoices paid, off-chain: each root the tests made, its trie */
    private var tries = Map(Trie.empty.rootHash -> Trie.empty)
    private def trie(s: PolicyState): Trie = tries(s.invoices)

    /** what the rules give after paying `amount` to `who` at `at` */
    private def after(s: PolicyState, who: Credential, amount: BigInt, id: String, at: BigInt): PolicyState =
        val w = (at - s.start) / s.period
        val paid = trie(s).insert(inv(id), ByteString.empty)
        tries += paid.rootHash -> paid
        s.copy(window = w, spent = (if w == s.window then s.spent else BigInt(0)) + amount,
            payees = s.payees.map(p => if p.who === who then p.copy(spent = (if p.window == w then p.spent else
                BigInt(0)) + amount, window = w, last = at) else p),
            invoices = paid.rootHash)

    /** the agent pays `amount` of the token to `who` at `at` (a 10-minute window): the honest transaction, each part
      * replaceable by an attack */
    private def pay(who: Credential, amount: BigInt, id: String = "INV-1", at: BigInt = t0 + day,
        s: PolicyState = st, inValue: Value = held, signers: List[PubKeyHash] = List(agent),
        next: PolicyState = null, back: Value = null, outs: List[TxOut] = null, until: BigInt = BigInt(-1),
        minted: Value = Value.zero, proof: Proof = null): Result =
        val n = if next == null then after(s, who, amount, id, at) else next
        val pr = if proof == null then trie(s).proveNonMembership(inv(id)) else proof
        val b = if back == null then inValue - tusd(amount) else back
        val o = if outs == null then List(out(n, b), paid(who, ada(2_000_000) + tusd(amount), id)) else outs
        spend(List(TxInInfo(ref, out(s, inValue))), o, PolicyAction.Pay(who, amount, inv(id), pr), signers,
            Interval.between(at, if until < 0 then at + 600_000 else until), minted)

    extension (r: Result)
        def failsWith(m: String): Boolean = r.isFailure && r.logs.exists(_.startsWith(m + ":"))
        def explain: String = r.logs.mkString("\n")

    // ---- the agent's payments ----
    test("a payment: the agent signs, an allowed payee, within every cap") {
        val r = pay(cloud, 90)
        assert(r.isSuccess, r.explain)
    }
    test("a stranger's key, or the owner's alone, cannot pay") {
        assert(pay(cloud, 90, signers = List(eve)).failsWith("NotAgent"))
        assert(pay(cloud, 90, signers = List(owner)).failsWith("NotAgent"))
    }
    test("a payee the owner did not allow: the agent itself, an address from an invoice") {
        assert(pay(Credential.PubKeyCredential(agent), 10).failsWith("PayeeNotAllowed"))
        assert(pay(Credential.PubKeyCredential(eve), 10).failsWith("PayeeNotAllowed"))
    }
    test("nothing, and above the most a payment") {
        assert(pay(cloud, 0).failsWith("ZeroAmount"))
        assert(pay(cloud, 301).failsWith("OverMax"))
    }
    test("above approvalAbove: the owner signs too, else a request waiting") {
        assert(pay(cloud, 140).failsWith("NeedsOwner"))
        assert(pay(cloud, 140, signers = List(agent, owner)).isSuccess)
    }
    test("the payee's cap for the window, and the window's cap") {
        val once = after(st, cloud, 90, "C1", t0 + day)
        assert(pay(cloud, 70, "C2", t0 + day * 2, s = once).failsWith("PayeeCap"))
        val full = st.copy(spent = 350)
        assert(pay(cloud, 60, s = full).failsWith("PeriodCap"))
        // a new window: both counted from zero
        assert(pay(cloud, 100, "C3", t0 + day * 31, s = once, signers = List(agent)).isSuccess)
    }
    test("the payee's gap: sooner refused, after it paid") {
        val once = after(st, net, 50, "N1", t0 + day)
        assert(pay(net, 20, "N2", t0 + day * 10, s = once).failsWith("TooSoon"))
        assert(pay(net, 20, "N2", t0 + day * 27, s = once).isSuccess)
    }
    test("an invoice twice, a year later too: no proof of its absence exists; a stale one is refused") {
        val once = after(st, cloud, 50, "C1", t0 + day)
        val stale = Trie.empty.proveNonMembership(inv("C1"))
        def refused(r: Result) = r.isFailure && r.logs.exists(_.contains("element exists"))
        assert(refused(pay(cloud, 50, "C1", t0 + day * 2, s = once, next = once, proof = stale)))
        assert(refused(pay(cloud, 50, "C1", t0 + day * 300, s = once, next = once, proof = stale)))
    }
    test("the invoice not remembered: the next root without it") {
        assert(pay(cloud, 50, "C9", next = after(st, cloud, 50, "C9", t0 + day).copy(invoices = st.invoices))
            .failsWith("StateChanged"))
    }
    test("paused, expired, a window over an hour, before the window recorded") {
        assert(pay(cloud, 10, s = st.copy(paused = true)).failsWith("Paused"))
        assert(pay(cloud, 10, at = t0 + day * 366).failsWith("Expired"))
        assert(pay(cloud, 10, until = t0 + day + hour * 2).failsWith("WindowTooLong"))
        assert(pay(cloud, 10, s = st.copy(window = 3)).failsWith("BadTime"))
    }
    test("more than the policy holds") {
        assert(pay(cloud, 90, inValue = ada(5_000_000) + nft + tusd(50)).failsWith("InsufficientBalance"))
    }
    test("the agent takes more than it pays: the balance back short, the rest to itself") {
        val r = pay(cloud, 90, outs = List(out(after(st, cloud, 90, "INV-1", t0 + day), held - tusd(200)),
            to(cloud, ada(2_000_000) + tusd(90)), to(Credential.PubKeyCredential(agent), ada(2_000_000) + tusd(110))))
        assert(r.failsWith("BalanceChanged"))
    }
    test("the agent keeps the state's spending low (a forged next state)") {
        assert(pay(cloud, 90, next = st).failsWith("StateChanged"))
        assert(pay(cloud, 90, next = after(st, cloud, 90, "INV-1", t0 + day).copy(agent = eve))
            .failsWith("StateChanged"))
    }
    test("the payee not paid: the amount sent elsewhere") {
        val r = pay(cloud, 90, outs = List(out(after(st, cloud, 90, "INV-1", t0 + day), held - tusd(90)),
            to(Credential.PubKeyCredential(eve), ada(2_000_000) + tusd(90))))
        assert(r.failsWith("NotPaid"))
    }
    test("a foreign token slipped into the policy") {
        assert(pay(cloud, 90, back = held - tusd(90) + of(junk, 1)).failsWith("OnlyTheToken"))
    }
    test("the policy split in two, sent to another address, a second NFT minted") {
        val n = after(st, cloud, 90, "INV-1", t0 + day)
        assert(pay(cloud, 90, outs = List(out(n, held - tusd(500)), out(n, ada(2_000_000) + tusd(410)),
            to(cloud, tusd(90)))).failsWith("OneOutput"))
        val other = Address(Credential.ScriptCredential(policy),
            POption.Some(StakingCredential.StakingHash(Credential.PubKeyCredential(eve))))
        assert(pay(cloud, 90, outs = List(out(n, held - tusd(90), other), to(cloud, tusd(90))))
            .failsWith("SameAddress"))
        assert(pay(cloud, 90, minted = nft).failsWith("NoMint"))
    }
    test("AUDIT C1, double satisfaction: two policies paying one payee, one output counted by both") {
        // another policy (another owner's, the same agent) in the same transaction, paying the same payee 90: one
        // output of 90 to the payee is all both scripts see; the other 90 goes to the agent
        val other = TestUtil.mockScriptHash(8)
        val otherHome = Address(Credential.ScriptCredential(other), POption.None)
        val otherHeld = ada(5_000_000) + Value(other, SpendPolicy.stateName, 1) + tusd(1000)
        val n = after(st, cloud, 90, "INV-1", t0 + day)
        val ins = List(TxInInfo(ref, out(st, held)), TxInInfo(TestUtil.mockTxOutRef(10, 0), out(st, otherHeld,
            otherHome)))
        val outs = List(out(n, held - tusd(90)), out(n, otherHeld - tusd(90), otherHome),
            paid(cloud, ada(2_000_000) + tusd(90), "INV-1"), to(Credential.PubKeyCredential(agent), tusd(90)))
        val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = List(agent),
            validRange = Interval.between(t0 + day, t0 + day + 600_000))
        def run(own: TxOutRef) = program.runWithDebug(ScriptContext(tx, PolicyAction.Pay(cloud, 90, inv("INV-1"),
            Trie.empty.proveNonMembership(inv("INV-1"))).toData, ScriptInfo.SpendingScript(own, POption.None)))
        // the output names the first policy: it is that one's payment; the other policy cannot count it
        assert(run(ref).isSuccess, run(ref).explain)
        assert(run(TestUtil.mockTxOutRef(10, 0)).failsWith("NotPaid"))
    }
    test("AUDIT C2: tokens sent to the policy's address by a plain transfer — swept by the owner, by no one else") {
        val strayRef = TestUtil.mockTxOutRef(11, 0)
        val stray = TxInInfo(strayRef, TxOut(home, ada(2_000_000) + tusd(300), OutputDatum.NoOutputDatum))
        def run(own: TxOutRef, ins: List[TxInInfo], outs: List[TxOut], action: PolicyAction, signers: List[PubKeyHash],
            range: Interval = Interval.always) =
            val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers, validRange = range)
            program.runWithDebug(ScriptContext(tx, action.toData, ScriptInfo.SpendingScript(own, POption.None)))
        // the owner: the state on, the stray to the owner — both scripts agree
        val sweep = List(out(st, held), TxOut(Address.fromPubKeyHash(owner), ada(2_000_000) + tusd(300),
            OutputDatum.NoOutputDatum))
        val ins = List(TxInInfo(ref, out(st, held)), stray)
        assert(run(ref, ins, sweep, PolicyAction.Owner, List(owner)).isSuccess)
        assert(run(strayRef, ins, sweep, PolicyAction.Owner, List(owner)).isSuccess)
        // the stray alone: refused
        assert(run(strayRef, List(stray), List(to(Credential.PubKeyCredential(eve), tusd(300))), PolicyAction.Owner,
            List(eve)).failsWith("NotThePolicy"))
        // the agent's payment taking the stray along: the state refuses it
        val n = after(st, cloud, 90, "INV-1", t0 + day)
        val pay = PolicyAction.Pay(cloud, 90, inv("INV-1"), Trie.empty.proveNonMembership(inv("INV-1")))
        assert(run(ref, ins, List(out(n, held - tusd(90)), paid(cloud, ada(2_000_000) + tusd(90), "INV-1"),
            to(Credential.PubKeyCredential(agent), tusd(300))), pay, List(agent),
            Interval.between(t0 + day, t0 + day + 600_000)).failsWith("OneInput"))
    }
    test("a forged policy: an input without the NFT") {
        val r = spend(List(TxInInfo(ref, out(st, ada(5_000_000) + tusd(1000)))), List(out(st, tusd(910)),
            to(cloud, tusd(90))), PolicyAction.Pay(cloud, 90, inv("F"), Trie.empty.proveNonMembership(inv("F"))),
            List(agent),
            Interval.between(t0 + day, t0 + day + 600_000))
        assert(r.failsWith("NotThePolicy"))
    }

    // ---- a deposit ----
    test("anyone tops the policy up: the same state, more of the token") {
        val r = spend(List(TxInInfo(ref, out(st, held))), List(out(st, held + tusd(500))), PolicyAction.Deposit,
            List(eve))
        assert(r.isSuccess, r.explain)
    }
    test("a deposit that changes the rules, or takes the token out") {
        assert(spend(List(TxInInfo(ref, out(st, held))), List(out(st.copy(agent = eve), held + tusd(1))),
            PolicyAction.Deposit, List(eve)).failsWith("StateChanged"))
        assert(spend(List(TxInInfo(ref, out(st, held))), List(out(st, held - tusd(500)), to(Credential
            .PubKeyCredential(eve), tusd(500))), PolicyAction.Deposit, List(eve)).failsWith("DepositNothing"))
    }

    // ---- the owner ----
    test("the owner withdraws, changes the rules, closes") {
        assert(spend(List(TxInInfo(ref, out(st, held))), List(out(st.copy(agent = eve), held - tusd(600))),
            PolicyAction.Owner, List(owner)).isSuccess)
        val r = spend(List(TxInInfo(ref, out(st, held))), List(TxOut(Address.fromPubKeyHash(owner),
            ada(5_000_000) + tusd(1000), OutputDatum.NoOutputDatum)), PolicyAction.Owner, List(owner),
            minted = Value(policy, SpendPolicy.stateName, -1))
        assert(r.isSuccess, r.explain)
    }
    test("the agent, or anyone else, acting as the owner") {
        assert(spend(List(TxInInfo(ref, out(st, held))), List(TxOut(Address.fromPubKeyHash(agent), held,
            OutputDatum.NoOutputDatum)), PolicyAction.Owner, List(agent)).failsWith("NotOwner"))
        assert(spend(List(TxInInfo(ref, out(st, held))), List(out(st.copy(owner = eve), held)), PolicyAction.Owner,
            List(eve)).failsWith("NotOwner"))
    }
    test("the owner leaving an invalid state (a window of zero)") {
        assert(spend(List(TxInInfo(ref, out(st, held))), List(out(st.copy(period = 0), held)), PolicyAction.Owner,
            List(owner)).failsWith("BadPeriod"))
    }

    // ---- the birth ----
    private val seedIn =
        TxInInfo(seed, TxOut(Address.fromPubKeyHash(owner), ada(200_000_000), OutputDatum.NoOutputDatum))
    private def init(ins: List[TxInInfo], outs: List[TxOut], signers: List[PubKeyHash], minted: Value = nft): Result =
        val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers, mint = minted)
        program.runWithDebug(ScriptContext(tx, PolicyMint.Init.toData, ScriptInfo.MintingScript(policy)))

    test("init: the seed spent, one NFT, to the script with no stake part, the owner signing") {
        val r = init(List(seedIn), List(out(st, held)), List(owner))
        assert(r.isSuccess, r.explain)
    }
    test("init without the seed, of two NFTs, without the owner, with a foreign token") {
        assert(init(List(), List(out(st, held)), List(owner)).failsWith("SeedNotSpent"))
        assert(init(List(seedIn), List(out(st, held + nft)), List(owner), nft + nft).failsWith("MintExactlyOne"))
        assert(init(List(seedIn), List(out(st, held)), List(eve)).failsWith("NotOwner"))
        assert(init(List(seedIn), List(out(st, held + of(junk, 5))), List(owner)).failsWith("OnlyTheToken"))
    }
}
