package okay.allowance

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scalus.uplc.builtin.ByteString
import okay.allowance.cardano.{CardanoWorld, Policy}
import CardanoWorld.usd

/** THE POLICY ON CARDANO (specs/allowance.md §9), on Scalus's ledger emulator: real transactions, the script run by
 * the ledger's rules — the agent's payments and refusals, a request the owner approves, a top-up, the close */
class CardanoSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private def inv(s: String) = ByteString.fromHex(Agent.invoiceId(s).stripPrefix("0x"))

  test("a life on Cardano: each payment, each refusal for its own reason, the owner's approval, the close") {
    val w = CardanoWorld()
    val created = w.create()
    assert(created.isRight, created.toString)
    assertEquals(w.balance, usd(1_000))
    def pay(to: String, x: Double, id: String, signers: Seq[String] = Seq("agent")) =
      w.pay(w.parties(to), usd(x), inv(id), signers).left.toOption
    assertEquals(pay("internet provider", 49.99, "INT-1"), None)
    assertEquals(w.holds("internet provider"), usd(49.99))
    w.advance(86_400)
    assertEquals(pay("cloud hosting", 85, "INT-1"), Some("AlreadyPaid"))
    assertEquals(pay("internet provider", 10, "INT-2"), Some("TooSoon"))
    assertEquals(pay("attacker", 10, "X-1"), Some("PayeeNotAllowed"))
    assertEquals(pay("agent", 10, "X-2"), Some("PayeeNotAllowed"))
    assertEquals(pay("cloud hosting", 301, "X-3"), Some("OverMax"))
    assertEquals(pay("cloud hosting", 0, "X-4"), Some("ZeroAmount"))
    // the attacker's own key, and the owner's alone, cannot pay
    assert(pay("cloud hosting", 10, "X-5", Seq("attacker")).nonEmpty)
    assertEquals(pay("cloud hosting", 85, "CLOUD-1"), None)
    assertEquals(pay("cloud hosting", 120, "CLOUD-2", Seq("agent", "owner")), Some("PayeeCap"))
    // above approvalAbove: the owner's signature
    assertEquals(pay("electricity", 140, "POWER-1", Seq("agent", "owner")), None)
    assertEquals(w.state.spent, usd(49.99 + 85 + 140))
    assertEquals(pay("software licence", 90, "SW-1"), None)
    assertEquals(pay("cloud hosting", 40, "CLOUD-3"), Some("PeriodCap"))
    // a top-up by a stranger; the balance after the payments
    assert(w.deposit("stranger", 50).isRight)
    assertEquals(w.balance, usd(1_000 - 49.99 - 85 - 140 - 90 + 50))
    // a month later: a new window, the internet's gap passed
    w.advance(30 * 86_400)
    assertEquals(pay("internet provider", 49.99, "INT-2"), None)
    // the owner closes: everything back, the policy gone
    val before = w.holds("owner")
    assert(w.close().isRight)
    assertEquals(w.balance, BigInt(0))
    assertEquals(w.holds("owner") - before, usd(1_000 - 49.99 * 2 - 85 - 140 - 90 + 50))
  }

  test("more than the policy holds: refused, the owner should top it up") {
    val w = CardanoWorld()
    assert(w.create(deposit = 50).isRight)
    assertEquals(w.pay(w.parties("cloud hosting"), usd(60), inv("C")).left.toOption, Some("InsufficientBalance"))
  }

  test("the agent's door on Cardano: a trial writes nothing, a request waits, the words carry the numbers") {
    val w = CardanoWorld()
    assert(w.create().isRight)
    val n = w.node
    val cloud = w.address("cloud hosting")
    for
      t <- n.trial(cloud, Life.usd(85), Agent.invoiceId("C1"))
      _ = assert(t.ok, t.toString)
      _ = assertEquals((w.balance, w.state.spent), (usd(1_000), BigInt(0)))
      r <- n.pay(w.address("electricity"), Life.usd(140), Agent.invoiceId("P1"))
      _ = assertEquals(r.events.map(_.name), Vector("Requested"))
      _ = assertEquals(w.waiting.map(_._1), Vector(0))
      _ = assertEquals(w.balance, usd(1_000))
      a = w.approve(0)
      _ = assert(a.isRight, a.toString)
      _ = assertEquals(w.holds("electricity"), usd(140))
      c <- n.pay(cloud, Life.usd(85), Agent.invoiceId("C1"))
      _ = assertEquals(c.events.map(_.name), Vector("Paid"))
      cap <- n.pay(cloud, Life.usd(130), Agent.invoiceId("C2"))
    yield
      assertEquals(cap.reason, Some("PayeeCap"))
      val words = Agent.explain(cap.reason.get, cap.args, "Cloud hosting")
      assert(words.contains("215 tUSD8") && words.contains("200 tUSD8"), words)
  }
