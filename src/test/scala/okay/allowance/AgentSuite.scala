package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE AGENT (specs/allowance.md §4.3) on the demo's invoices: its own refusals, the contract's, the payments, the
 * request; the payee never from the invoice; a trial writes nothing; refusals said with the contract's numbers */
class AgentSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("the demo's ten invoices: three paid, one waiting, two refused by the agent, four by the contract") {
    Agent.demo(Node.compiled, Node.invoices("demo/invoices")).map { (entries, _, _) =>
      val expect = Vector("INT-2026-11" -> ("paid", "", ""), "CLOUD-2026-11" -> ("paid", "", ""),
        "POWER-2026-11" -> ("waiting", "", ""), "URGENT-9921" -> ("refused", "agent", "address-mismatch"),
        "CI-77" -> ("refused", "agent", "unknown-payee"), "CLOUD-2026-11" -> ("refused", "contract", "AlreadyPaid"),
        "CLOUD-2026-11-EXTRA" -> ("refused", "contract", "PayeeCap"),
        "INT-2026-11-B" -> ("refused", "contract", "TooSoon"), "SW-2026-11" -> ("paid", "", ""),
        "GYM-2026-11" -> ("refused", "contract", "PayeeNotAllowed"))
      assertEquals(entries.map(e => e.invoice -> (e.status, e.by, e.reason)), expect)
      assertEquals(entries.find(_.status == "waiting").map(_.request), Some(0))
      assert(entries.forall(e => e.words.nonEmpty && !e.words.contains("undefined") && !e.words.contains("NaN")))
    }
  }

  test("the payee is never taken from the invoice; the currency and the amount judged") {
    val book = Map("Cloud hosting" -> "0x00000000000000000000000000000000000000c1")
    def inv(address: Option[String] = None, amount: String = "1", currency: Option[String] = None) =
      Invoice("X", "cloud HOSTING", address, amount, currency, "")
    assertEquals(Agent.decide(inv(Some("0xdead")), book), Decision.Refuse("address-mismatch",
      "0xdead ≠ 0x00000000000000000000000000000000000000c1"))
    assert(Agent.decide(inv(currency = Some("EUR")), book).isInstanceOf[Decision.Refuse])
    assert(Agent.decide(inv(amount = "0"), book).isInstanceOf[Decision.Refuse])
    assert(Agent.decide(inv(amount = "ten"), book).isInstanceOf[Decision.Refuse])
    Agent.decide(inv(Some("0x00000000000000000000000000000000000000C1")), book) match
      case Decision.Pay(name, _, amount) => assertEquals((name, amount.toString), ("Cloud hosting", "100000000"))
      case other => fail(s"not paid: $other")
  }

  test("a trial passes and writes nothing: no money moved, the books and the invoice as before") {
    for
      (_, w, _) <- Agent.demo(Node.compiled, Vector.empty)
      c = w.chain; owner = w.parties("owner"); agent = w.parties("agent")
      was <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      spent <- c.view(w.policy, "SpendPolicy", "spent")
      t <- c.simulate(agent, w.policy, "SpendPolicy", "pay", w.parties("software licence").address, Life.usd(5),
        Agent.invoiceId("TRIAL-ONLY"))
      now <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      spentNow <- c.view(w.policy, "SpendPolicy", "spent")
      paid <- c.view(w.policy, "SpendPolicy", "paidInvoice", Agent.invoiceId("TRIAL-ONLY"))
    yield
      assert(t.ok, t.toString)
      assertEquals((now.toString, spentNow.toString, paid.asInstanceOf[Boolean]), (was.toString, spent.toString, false))
  }

  test("the words name the contract's numbers") {
    val w = Agent.explain("PeriodCap", Vector(js.BigInt("41499000000"), js.BigInt("40000000000")))
    assert(w.contains("414.99 tUSD8") && w.contains("400 tUSD8"), w)
    assert(Agent.explain("TooSoon", Vector("0x0", js.BigInt("1769385600")), "Internet provider")
      .contains("2026-01-26"))
  }
