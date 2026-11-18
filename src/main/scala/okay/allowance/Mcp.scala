package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.async.{Async, fromFuture, toFuture}
import okay.mcp.{Rpc, ToolCall, ToolResult, ToolSpec, Content, Servers}

/**
 * THE POLICY'S DOOR AS AN MCP SERVER (specs/allowance.md §10): any AI agent pays bills through it. The server holds
 * the agent's keys (in its nodes) and the owner's address book; the model names a payee, an amount, an invoice —
 * never a key, never an address that is not the book's. Every call through the agent's judgement, then the contract.
 * On okay-mcp: `dispatch` on the machine with okay-simple's async module, one message a run, to a Future.
 */
final class McpServer(nodes: Map[String, Node], chains: Seq[String], book: Book, journal: Entry => Unit = _ => ())
    (using ExecutionContext):
  val servers: Servers[Machine.type] = new Servers[Machine.type]()(using Machine)
  import servers.{Server, Tools}
  import servers.fx.{!, pure}

  /** the payments this server asked the owner to approve, still waiting */
  private var waiting = Vector.empty[Entry]

  private def text(j: Json, k: String): Option[String] = j match
    case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s.trim; case Json.Num(n) => n.toString }
      .filter(_.nonEmpty)
    case _ => None
  private def str(s: String) = Json.Str(s)
  private def result(words: String, fields: (String, String)*): ToolResult =
    ToolResult(List(Content.Text(words)), Some(Json.Obj(fields.toVector.map((k, v) => k -> str(v)))))
  private def big(x: BigInt) = Agent.money(js.BigInt(x.toString))

  /** the call's invoice: a payee by name, an amount, the invoice's id; a chain and an address if the model has them */
  private def invoice(c: ToolCall): Either[String, Invoice] =
    (text(c.arguments, "payee"), text(c.arguments, "amount"), text(c.arguments, "invoice")) match
      case (Some(p), Some(a), Some(i)) => Right(Invoice(i, p, text(c.arguments, "address"), a,
        text(c.arguments, "currency"), text(c.arguments, "for").getOrElse(""), text(c.arguments, "chain")))
      case _ => Left("payee, amount and invoice are required")

  private val payment = Json.obj("type" -> str("object"), "required" -> Json.Arr(Vector(str("payee"), str("amount"),
    str("invoice"))), "properties" -> Json.obj(
    "payee" -> Json.obj("type" -> str("string"), "description" -> str("the payee's name in the owner's address book")),
    "amount" -> Json.obj("type" -> str("string"), "description" -> str("the amount in tUSD8, e.g. \"49.99\"")),
    "invoice" -> Json.obj("type" -> str("string"), "description" -> str("the invoice's id; each is paid once")),
    "chain" -> Json.obj("type" -> str("string"), "description" -> str("the chain the invoice asks for, if any")),
    "address" -> Json.obj("type" -> str("string"),
      "description" -> str("the address the invoice names, if any: checked against the book, never used")),
    "for" -> Json.obj("type" -> str("string"), "description" -> str("what the invoice is for"))))
  private val specs = List(
    ToolSpec("check_payment", "Would this invoice be paid? The agent's judgement and a trial on the chain; nothing " +
      "is written. Answers: would be paid, would wait for the owner's approval, or refused — and why.", payment),
    ToolSpec("pay", "Pay an invoice from the owner's policy: paid, waiting for the owner's approval, or refused — " +
      "by the agent's judgement or by the contract — with the reason in words.", payment),
    ToolSpec("status", "Each chain's policy: balance, this period's spending and cap, the most a payment, the " +
      "approval threshold, and each payee's cap, spending and next possible payment.",
      Json.obj("type" -> str("object"), "properties" -> Json.obj("chain" -> Json.obj("type" -> str("string"))))),
    ToolSpec("requests", "Payments waiting for the owner's approval.", Json.obj("type" -> str("object"))))

  private def check(inv: Invoice): Future[ToolResult] = Agent.decide(inv, book, chains) match
    case Decision.Refuse(reason, detail) =>
      Future.successful(result(s"would be refused by the agent: ${Agent.explain(reason)}" +
        (if detail.isEmpty then "" else s" ($detail)"), "verdict" -> "refused", "by" -> "agent", "reason" -> reason))
    case Decision.Pay(payee, chain, address, amount) => nodes.get(chain) match
      case None => Future.successful(result(Agent.explain("NoSuchChain", Vector(chain)), "verdict" -> "refused",
        "by" -> "agent", "reason" -> "NoSuchChain", "chain" -> chain))
      case Some(n) =>
        for t <- n.trial(address, amount, Agent.invoiceId(inv.id)); st <- n.status(Nil) yield
          val money = Agent.money(amount)
          if !t.ok then
            val r = t.reason.getOrElse("")
            result(s"would be refused by the contract on $chain: ${Agent.explain(r, t.args, payee)}",
              "verdict" -> "refused", "by" -> "contract", "reason" -> r, "chain" -> chain)
          else if BigInt(amount.toString) > st.approvalAbove then
            result(s"$money to $payee on $chain: within the rules, above ${big(st.approvalAbove)} — it would wait " +
              "for the owner's approval", "verdict" -> "waiting", "chain" -> chain)
          else result(s"$money to $payee on $chain would be paid", "verdict" -> "paid", "chain" -> chain)

  private def pay(inv: Invoice): Future[ToolResult] =
    Agent.on(nodes, chains, Vector(inv), book, journal).map(_.head).map { e =>
      if e.status == "waiting" then waiting :+= e
      result(e.words, "status" -> e.status, "by" -> e.by, "reason" -> e.reason, "chain" -> e.chain, "tx" -> e.tx,
        "request" -> (if e.request < 0 then "" else e.request.toString))
    }

  private def status(only: Option[String]): Future[ToolResult] =
    val shown = chains.filter(c => only.forall(_.equalsIgnoreCase(c)))
    if shown.isEmpty then Future.successful(ToolResult.error(s"no such chain: ${only.getOrElse("")}")) else
    Life.inOrder(shown) { chain =>
      val payees = book.payees.toVector.sortBy(_._1).flatMap((name, m) => m.get(chain).map(name -> _))
      nodes.get(chain) match
        case None => Future.successful(s"$chain: no node")
        case Some(n) => n.status(payees.map(_._2)).map { s =>
          def date(t: Long) = new js.Date(t.toDouble * 1000).toISOString().take(10)
          val head = s"$chain: ${big(s.balance)} on the policy; this period ${big(s.spent)} of ${big(s.periodCap)};" +
            s" at most ${big(s.maxPayment)} a payment; above ${big(s.approvalAbove)} the owner approves;" +
            (if s.paused then " PAUSED;" else "") + s" until ${date(s.expires)}"
          val lines = payees.zip(s.payees).map { case ((name, _), p) =>
            if !p.allowed then s"  $name: not allowed on the policy"
            else s"  $name: ${big(p.spent)} of ${big(p.cap)} this period" +
              (if p.nextAt == 0 then "" else s", next from ${date(p.nextAt)}")
          }
          (head +: lines).mkString("\n")
        }
    }.map(ls => ToolResult.text(ls.mkString("\n")))

  private val tools: Tools[Async] = new Tools[Async]:
    def list = pure[List[ToolSpec], Async](specs)
    def call(c: ToolCall): ToolResult ! Async = fromFuture[ToolResult, Async](c.name match
      case "check_payment" => invoice(c).fold(e => Future.successful(ToolResult.error(e)), check)
      case "pay" => invoice(c).fold(e => Future.successful(ToolResult.error(e)), pay)
      case "status" => status(text(c.arguments, "chain"))
      case "requests" => Future.successful(ToolResult.text(
        if waiting.isEmpty then "no payment is waiting for the owner"
        else waiting.map(e => s"${e.chain} request ${e.request}: ${e.invoice} — ${e.words}").mkString("\n")))
      case other => Future.successful(ToolResult.error(s"no such tool: $other")))

  val server: Server[Async] = Server[Async]("okay-allowance", "0.1.0", tools, instructions = Some(
    "Pays the owner's bills in tUSD8 from a policy on each chain: name the payee as in the owner's address book, " +
    "the amount and the invoice's id. check_payment first when unsure; the contract bounds every payment."))

  private var session = servers.fresh
  private var last: Future[Unit] = Future.unit

  /** ONE LINE IN, the lines to send back; lines answered in the order they came */
  def handle(line: String): Future[List[String]] =
    val next = last.flatMap { _ =>
      Rpc.decode(line) match
        case Left(failed) => Future.successful(List(Rpc.encode(failed)))
        case Right(m) => toFuture(servers.dispatch[Async](server, session, m)).map { (s, replies) =>
          session = s
          replies.map(Rpc.encode)
        }
    }
    last = next.map(_ => ()).recover { case _ => () }
    next

/** node's readline, as an ES module */
@js.native @JSImport("node:readline", JSImport.Namespace) private object Readline extends js.Object

object McpServer:
  /** THE PROTOCOL ON STDIN AND STDOUT: each line handled, its replies written; logs to stderr only */
  def stdio(server: McpServer)(using ExecutionContext): Unit =
    val process = js.Dynamic.global.process
    val rl = Readline.asInstanceOf[js.Dynamic].createInterface(js.Dynamic.literal(input = process.stdin,
      terminal = false))
    rl.on("line", (line: String) => if line.trim.nonEmpty then
      server.handle(line).onComplete {
        case scala.util.Success(out) => out.foreach(l => process.stdout.write(l + "\n"))
        case scala.util.Failure(e) => process.stderr.write(s"okay-allowance mcp: ${e.getMessage}\n")
      })
