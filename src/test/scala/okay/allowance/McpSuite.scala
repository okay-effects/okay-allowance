package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE MCP DOORS (specs/allowance.md §10, §12, §13), spoken to in JSON-RPC lines as an MCP client would: the agent's
 * and the owner's tools apart; the plan — the agent proposes, the owner approves, unchecks, edits, removes, the
 * agent pays what is due in turn, monthly payments roll on — on the demo's three chains */
class McpSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private var id = 0
  private def req(method: String, params: String = "{}") =
    id += 1
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
  private def call(tool: String, args: String = "{}") = req("tools/call", s"""{"name":"$tool","arguments":$args}""")
  private def words(lines: List[String]): String =
    js.JSON.parse(lines.head).result.content.asInstanceOf[js.Array[js.Dynamic]].map(_.text.toString).mkString
  private def isError(lines: List[String]): Boolean =
    val r = js.JSON.parse(lines.head).result
    !js.isUndefined(r.isError) && r.isError.asInstanceOf[Boolean]
  private def names(lines: List[String]) =
    js.JSON.parse(lines.head).result.tools.asInstanceOf[js.Array[js.Dynamic]].map(_.name.toString).toVector
  /** the plan's states by invoice */
  private def states(lines: List[String]): Map[String, String] =
    js.JSON.parse(lines.head).result.structuredContent.plan.asInstanceOf[js.Array[js.Dynamic]].toVector
      .map(x => x.invoice.toString -> x.state.toString).toMap

  test("two doors: the agent cannot approve, the owner cannot pay; a check, the status, the address book") {
    for
      d <- Agent.demo(Files.compiled, Vector.empty)
      store = MemoryPlan(Plan.seed(Files.bills))
      agent = McpServer(d.nodes, Agent.demoChains, d.book, store, Role.Agent)
      owner = McpServer(d.nodes, Agent.demoChains, d.book, store, Role.Owner)
      early <- agent.handle(req("tools/list"))
      _ = assert(early.head.contains("initialize first"), early.head)
      _ <- agent.handle(req("initialize")); _ <- owner.handle(req("initialize"))
      la <- agent.handle(req("tools/list")); lo <- owner.handle(req("tools/list"))
      _ = assertEquals(names(la), Vector("check_payment", "add_invoice", "pay_due", "plan", "status", "requests",
        "address_book"))
      _ = assertEquals(names(lo), Vector("approve", "uncheck", "check", "edit", "remove", "plan", "status",
        "requests", "address_book"))
      self <- agent.handle(call("approve", """{"all":true}"""))
      _ = assert(isError(self) && words(self).contains("no such tool here"), words(self))
      pays <- owner.handle(call("pay_due"))
      _ = assert(isError(pays), words(pays))
      c <- agent.handle(call("check_payment", """{"payee":"Internet provider","amount":"49.99","invoice":"X"}"""))
      _ = assertEquals(words(c), "49.99 tUSD8 to Internet provider on ethereum would be paid")
      f <- agent.handle(call("check_payment",
        """{"payee":"Cloud hosting","amount":"10","invoice":"X-1","address":"0xdead"}"""))
      _ = assert(words(f).contains("would be refused by the agent") && words(f).contains("invoice fraud"), words(f))
      st <- owner.handle(call("status", """{"chain":"base"}"""))
      _ = assert(words(st).startsWith("base: 1000 tUSD8 on the policy"), words(st))
      ab <- owner.handle(call("address_book"))
    yield assert(words(ab).contains("Gym\n  ethereum") && words(ab).contains("not allowed on the policy"), words(ab))
  }

  test("the plan: proposed, reviewed — approved, unchecked, edited, removed —, paid in turn, monthly rolling on") {
    for
      d <- Agent.demo(Files.compiled, Vector.empty)
      eth = d.worlds("ethereum").chain
      today = () => eth.now.toString.toLong
      advance = (days: Int) => { eth.advance(days * 86_400L); d.worlds("base").chain.advance(days * 86_400L);
        d.cardano.advance(days * 86_400L) }
      store = MemoryPlan(Plan.seed(Files.bills))
      agent = McpServer(d.nodes, Agent.demoChains, d.book, store, Role.Agent, today = today)
      owner = McpServer(d.nodes, Agent.demoChains, d.book, store, Role.Owner, today = today)
      _ <- agent.handle(req("initialize")); _ <- owner.handle(req("initialize"))
      // 2026-01-01: the standing orders; December's cloud bill missed, January's comes
      p0 <- owner.handle(call("plan"))
      _ = assertEquals(states(p0), Map("CLOUD/2025-12" -> "missed", "CLOUD/2026-01" -> "upcoming",
        "INT/2026-01" -> "due", "POWER/2026-01" -> "due", "POWER-2026-01-ADA" -> "due", "SW-2026-01" -> "upcoming"))
      // the agent takes three invoices in: proposed, proposed with the contract's objection, refused
      a1 <- agent.handle(call("add_invoice", """{"payee":"Software licence","amount":"20","invoice":"SW-EXTRA",""" +
        """"for":"one more seat","from":"2026-01-01","to":"2026-01-31"}"""))
      _ = assert(words(a1).contains("SW-EXTRA proposed") && words(a1).contains("waits for the owner"), words(a1))
      a2 <- agent.handle(call("add_invoice", """{"payee":"Gym","amount":"30","invoice":"GYM-1"}"""))
      _ = assert(words(a2).contains("the contract would refuse it now"), words(a2))
      a3 <- agent.handle(call("add_invoice",
        """{"payee":"Cloud hosting","amount":"10","invoice":"URGENT","address":"0xdead"}"""))
      _ = assert(words(a3).contains("refused") && words(a3).contains("invoice fraud"), words(a3))
      twice <- agent.handle(call("add_invoice", """{"payee":"Gym","amount":"30","invoice":"GYM-1"}"""))
      _ = assert(isError(twice), words(twice))
      // the agent pays what is due: the approved ones only, the earliest deadline first
      d1 <- agent.handle(call("pay_due"))
      _ = assertEquals(words(d1).linesIterator.map(_.takeWhile(_ != ':')).toVector,
        Vector("INT/2026-01", "POWER-2026-01-ADA", "POWER/2026-01"))
      p1 <- owner.handle(call("plan"))
      _ = assertEquals(states(p1).filter(_._1 != "CLOUD/2025-12"), Map("CLOUD/2026-01" -> "upcoming",
        "INT/2026-01" -> "paid", "INT/2026-02" -> "upcoming", "POWER/2026-01" -> "waiting",
        "POWER-2026-01-ADA" -> "paid", "SW-2026-01" -> "upcoming", "SW-EXTRA" -> "proposed", "GYM-1" -> "proposed",
        "URGENT" -> "refused"))
      _ = assert(words(p1).contains("[?] 2026-01-01 – 2026-01-31  proposed: Software licence, 20 tUSD8, SW-EXTRA"),
        words(p1))
      rq <- owner.handle(call("requests"))
      _ = assert(words(rq).contains("ethereum request 0: POWER/2026-01"), words(rq))
      // the owner signs it on the chain (its own wallet, not MCP): the plan sees it paid, February's comes
      w = d.worlds("ethereum")
      signed <- w.chain.call(w.parties("owner"), w.policy, "SpendPolicy", "approve", js.BigInt(0))
      _ = assert(signed.ok, signed.toString)
      p1b <- owner.handle(call("plan"))
      _ = assertEquals((states(p1b)("POWER/2026-01"), states(p1b)("POWER/2026-02")), ("paid", "upcoming"))
      rq2 <- owner.handle(call("requests"))
      _ = assertEquals(words(rq2), "no payment is waiting for the owner's signature")
      // the owner reviews: approves the extra seat at 25, not the licences this time, the gym gone
      _ <- owner.handle(call("edit", """{"invoice":"SW-EXTRA","amount":"25"}"""))
      _ <- owner.handle(call("approve", """{"invoices":["SW-EXTRA"]}"""))
      _ <- owner.handle(call("uncheck", """{"invoice":"SW-2026-01"}"""))
      _ <- owner.handle(call("remove", """{"invoice":"GYM-1"}"""))
      nope <- owner.handle(call("approve", """{"invoices":["URGENT"]}"""))
      _ = assert(isError(nope), words(nope))
      _ = advance(4) // 2026-01-05
      d2 <- agent.handle(call("pay_due"))
      _ = assertEquals(words(d2), "SW-EXTRA: paid 25 tUSD8 to Software licence on ethereum")
      _ = advance(16) // 2026-01-21: the licences' window ended unchecked; the cloud's is open
      d3 <- agent.handle(call("pay_due"))
      _ = assertEquals(words(d3), "CLOUD/2026-01: paid 85 tUSD8 to Cloud hosting on base")
      // the internet stops: February's removed, no March
      _ <- owner.handle(call("remove", """{"invoice":"INT/2026-02"}"""))
      _ = advance(40)
      p2 <- owner.handle(call("plan", """{"state":"open"}"""))
      all <- owner.handle(call("plan"))
    yield
      assertEquals(states(all)("SW-2026-01"), "skipped")
      assertEquals(states(all)("SW-EXTRA"), "paid")
      assert(!states(all).contains("GYM-1"))
      assert(!states(p2).keys.exists(_.startsWith("INT")), states(p2).toString)
      assert(states(p2).keys.exists(_.startsWith("CLOUD/2026-0")), states(p2).toString)
  }
