package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE MCP SERVER (specs/allowance.md §10), spoken to in JSON-RPC lines as an MCP client would: the tools listed, a
 * check that writes nothing, payments on the three chains of the demo — paid, waiting, refused by the agent and by
 * the contract —, the requests, the status; a call before `initialize` refused */
class McpSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private var id = 0
  private def req(method: String, params: String = "{}") =
    id += 1
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
  private def call(tool: String, args: String) = req("tools/call", s"""{"name":"$tool","arguments":$args}""")
  /** the answer's result: its text, its structured fields */
  private def answer(lines: List[String]): (String, js.Dynamic) =
    val r = js.JSON.parse(lines.head).result
    (r.content.asInstanceOf[js.Array[js.Dynamic]].map(_.text.toString).mkString,
      if js.isUndefined(r.structuredContent) then js.Dynamic.literal() else r.structuredContent)

  test("the policy's door over MCP: list, check, pay on three chains, requests, status") {
    for
      d <- Agent.demo(Files.compiled, Vector.empty)
      s = McpServer(d.nodes, Agent.demoChains, d.book)
      early <- s.handle(req("tools/list"))
      _ = assert(early.head.contains("initialize first"), early.head)
      _ <- s.handle(req("initialize"))
      list <- s.handle(req("tools/list"))
      _ = assertEquals(js.JSON.parse(list.head).result.tools.asInstanceOf[js.Array[js.Dynamic]].map(_.name.toString)
        .toVector, Vector("check_payment", "pay", "status", "requests"))
      // a check: would be paid, and nothing written
      c <- s.handle(call("check_payment", """{"payee":"Internet provider","amount":"49.99","invoice":"INT-11"}"""))
      (cw, cs) = answer(c)
      _ = assertEquals(cs.verdict.toString, "paid", cw)
      st0 <- d.nodes("ethereum").status(Nil)
      _ = assertEquals(st0.spent, BigInt(0))
      p1 <- s.handle(call("pay", """{"payee":"Internet provider","amount":"49.99","invoice":"INT-11"}"""))
      (w1, s1) = answer(p1)
      _ = assertEquals((s1.status.toString, s1.chain.toString), ("paid", "ethereum"), w1)
      // the same invoice again: the contract refuses
      p2 <- s.handle(call("pay", """{"payee":"Internet provider","amount":"49.99","invoice":"INT-11"}"""))
      _ = assertEquals(answer(p2)._2.reason.toString, "AlreadyPaid")
      // an address from the invoice that is not the book's: the agent refuses — the model cannot redirect money
      p3 <- s.handle(call("pay", """{"payee":"Cloud hosting","amount":"10","invoice":"X-1","address":"0xdead"}"""))
      _ = assertEquals((answer(p3)._2.by.toString, answer(p3)._2.reason.toString), ("agent", "address-mismatch"))
      // above the threshold, on Cardano: waiting for the owner
      p4 <- s.handle(call("pay",
        """{"payee":"Electricity","amount":"140","invoice":"POWER-ADA","chain":"cardano"}"""))
      _ = assertEquals((answer(p4)._2.status.toString, answer(p4)._2.chain.toString), ("waiting", "cardano"))
      rq <- s.handle(call("requests", "{}"))
      _ = assert(answer(rq)._1.contains("cardano request 0: POWER-ADA"), answer(rq)._1)
      // a check that the contract would refuse, with its numbers
      c2 <- s.handle(call("check_payment", """{"payee":"Internet provider","amount":"5","invoice":"INT-12"}"""))
      _ = assert(answer(c2)._1.contains("next payment is possible from"), answer(c2)._1)
      stt <- s.handle(call("status", """{"chain":"ethereum"}"""))
      (sw, _) = answer(stt)
      _ = assert(sw.contains("950.01 tUSD8 on the policy") && sw.contains("Internet provider: 49.99 tUSD8 of 60 tUSD8")
        && sw.contains("Gym: not allowed"), sw)
      bad <- s.handle(call("pay", """{"payee":"Gym"}"""))
    yield assert(js.JSON.parse(bad.head).result.isError.asInstanceOf[Boolean])
  }
