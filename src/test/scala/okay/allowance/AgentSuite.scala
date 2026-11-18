package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE AGENT (specs/allowance.md §4.3, §6) on the demo's invoices over two chains at once: its own refusals, the
 * contracts', the payments, the request, each on its chain; the payee never from the invoice; a trial writes nothing;
 * each chain's caps its own; refusals said with the contract's numbers */
class AgentSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("the demo's fifteen invoices on three chains, EVM and Cardano: each decision, by whom, on which chain") {
    Agent.demo(Files.compiled, Files.invoices("demo/invoices")).map { d =>
      val entries = d.entries
      val expect = Vector(
        "INT-2026-11" -> ("paid", "", "", "ethereum"), "CLOUD-2026-11" -> ("paid", "", "", "base"),
        "POWER-2026-11" -> ("waiting", "", "", "ethereum"),
        "URGENT-9921" -> ("refused", "agent", "address-mismatch", ""),
        "CI-77" -> ("refused", "agent", "unknown-payee", ""),
        "CLOUD-2026-11" -> ("refused", "contract", "AlreadyPaid", "base"),
        "CLOUD-2026-11-EXTRA" -> ("refused", "contract", "PayeeCap", "base"),
        "INT-2026-11-B" -> ("refused", "contract", "TooSoon", "ethereum"),
        "SW-2026-11" -> ("paid", "", "", "ethereum"),
        "GYM-2026-11" -> ("refused", "contract", "PayeeNotAllowed", "ethereum"),
        "POWER-2026-11-BASE" -> ("paid", "", "", "base"),
        "CLOUD-2026-11-ETH" -> ("refused", "agent", "no-chain", "ethereum"),
        "INT-2026-11-ADA" -> ("paid", "", "", "cardano"),
        "POWER-2026-11-ADA" -> ("waiting", "", "", "cardano"),
        "GYM-2026-11-ADA" -> ("refused", "contract", "PayeeNotAllowed", "cardano"))
      assertEquals(entries.map(e => e.invoice -> (e.status, e.by, e.reason, e.chain)), expect)
      assertEquals(entries.filter(_.status == "waiting").map(e => e.chain -> e.request),
        Vector("ethereum" -> 0, "cardano" -> 0))
      assertEquals(d.cardano.waiting.map(_._1), Vector(0))
      assert(entries.forall(e => e.words.nonEmpty && !e.words.contains("undefined") && !e.words.contains("NaN")))
    }
  }

  test("each chain keeps its own books: what was paid on one is not counted on the other") {
    Agent.demo(Files.compiled, Files.invoices("demo/invoices")).flatMap { d =>
      val worlds = d.worlds
      def spent(chain: String) =
        worlds(chain).chain.view(worlds(chain).policy, "SpendPolicy", "spent").map(_.toString.toDouble / 1e8)
      for e <- spent("ethereum"); b <- spent("base")
      yield assertEquals((e, b, d.cardano.state.spent.toDouble / 1e8), (49.99 + 90, 85.0 + 60, 49.99))
    }
  }

  test("the payee is never taken from the invoice; the chain, the currency and the amount judged") {
    val book = Book(Map("Cloud hosting" -> Map("base" -> "0x00000000000000000000000000000000000000c1")))
    val chains = Vector("ethereum", "base")
    def inv(address: Option[String] = None, amount: String = "1", currency: Option[String] = None,
            chain: Option[String] = None) = Invoice("X", "cloud HOSTING", address, amount, currency, "", chain)
    assertEquals(Agent.decide(inv(Some("0xdead")), book, chains), Decision.Refuse("address-mismatch",
      "0xdead ≠ 0x00000000000000000000000000000000000000c1"))
    assert(Agent.decide(inv(chain = Some("ethereum")), book, chains).isInstanceOf[Decision.Refuse])
    assert(Agent.decide(inv(currency = Some("EUR")), book, chains).isInstanceOf[Decision.Refuse])
    assert(Agent.decide(inv(amount = "0"), book, chains).isInstanceOf[Decision.Refuse])
    assert(Agent.decide(inv(amount = "ten"), book, chains).isInstanceOf[Decision.Refuse])
    Agent.decide(inv(Some("0x00000000000000000000000000000000000000C1")), book, chains) match
      case Decision.Pay(name, chain, _, amount) =>
        assertEquals((name, chain, amount.toString), ("Cloud hosting", "base", "100000000"))
      case other => fail(s"not paid: $other")
  }

  test("a chain with no node: refused, said") {
    val book = Book(Map("Cloud hosting" -> Map("solana" -> "Cloud1111")))
    Agent.on(Map.empty, Vector("solana"), Vector(Invoice("S1", "Cloud hosting", None, "5", None, "")), book)
      .map(es => assertEquals(es.map(e => (e.status, e.reason)), Vector(("refused", "NoSuchChain"))))
  }

  test("a trial passes and writes nothing: no money moved, the books and the invoice as before") {
    for
      d <- Agent.demo(Files.compiled, Vector.empty)
      worlds = d.worlds
      w = worlds("ethereum"); c = w.chain; owner = w.parties("owner"); agent = w.parties("agent")
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
