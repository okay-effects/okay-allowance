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
        List(Payee(cloud, 150, 0, 0, 0, 0), Payee(net, 80, day * 25, 0, 0, 0)), List(), List())
    private val held = ada(5_000_000) + nft + tusd(1000)
    private val ref = TestUtil.mockTxOutRef(9, 0)

    private def out(s: PolicyState, value: Value, at: Address = home) =
        TxOut(at, value, OutputDatum.OutputDatum(s.toData))
    private def to(c: Credential, v: Value) = TxOut(Address(c, POption.None), v, OutputDatum.NoOutputDatum)

    private def spend(ins: List[TxInInfo], outs: List[TxOut], action: PolicyAction, signers: List[PubKeyHash],
        range: Interval = Interval.always, minted: Value = Value.zero): Result =
        val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers, mint = minted,
            validRange = range)
        program.runWithDebug(ScriptContext(tx, action.toData, ScriptInfo.SpendingScript(ref, POption.None)))

    /** what the rules give after paying `amount` to `who` at `at` */
    private def after(s: PolicyState, who: Credential, amount: BigInt, id: String, at: BigInt): PolicyState =
        val w = (at - s.start) / s.period
        val now = if w == s.window then s.paid else List.Nil
        val before = if w == s.window then s.paidBefore else if w == s.window + 1 then s.paid else List.Nil
        s.copy(window = w, spent = (if w == s.window then s.spent else BigInt(0)) + amount,
            payees = s.payees.map(p => if p.who === who then p.copy(spent = (if p.window == w then p.spent else
                BigInt(0)) + amount, window = w, last = at) else p),
            paid = List.Cons(inv(id), now), paidBefore = before)

    /** the agent pays `amount` of the token to `who` at `at` (a 10-minute window): the honest transaction, each part
      * replaceable by an attack */
    private def pay(who: Credential, amount: BigInt, id: String = "INV-1", at: BigInt = t0 + day,
        s: PolicyState = st, inValue: Value = held, signers: List[PubKeyHash] = List(agent),
        next: PolicyState = null, back: Value = null, outs: List[TxOut] = null, until: BigInt = BigInt(-1),
        minted: Value = Value.zero): Result =
        val n = if next == null then after(s, who, amount, id, at) else next
        val b = if back == null then inValue - tusd(amount) else back
        val o = if outs == null then List(out(n, b), to(who, ada(2_000_000) + tusd(amount))) else outs
        spend(List(TxInInfo(ref, out(s, inValue))), o, PolicyAction.Pay(who, amount, inv(id)), signers,
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
    test("an invoice twice: in the same window, and in the next") {
        val once = after(st, cloud, 50, "C1", t0 + day)
        assert(pay(cloud, 50, "C1", t0 + day * 2, s = once).failsWith("AlreadyPaid"))
        assert(pay(cloud, 50, "C1", t0 + day * 32, s = once).failsWith("AlreadyPaid"))
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
    test("a forged policy: an input without the NFT") {
        val r = spend(List(TxInInfo(ref, out(st, ada(5_000_000) + tusd(1000)))), List(out(st, tusd(910)),
            to(cloud, tusd(90))), PolicyAction.Pay(cloud, 90, inv("F")), List(agent),
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
