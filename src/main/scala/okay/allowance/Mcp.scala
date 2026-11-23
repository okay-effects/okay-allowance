package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.async.{Async, fromFuture, toFuture}
import okay.mcp.{Rpc, ToolCall, ToolResult, ToolSpec, Content, Servers}

/** who a server serves (specs/allowance.md §13): the agent (proposes, pays what is due), the owner (reviews,
 * approves, edits), or both in one process (the demo) */
enum Role:
  case Agent, Owner, Demo

/**
 * THE POLICY'S DOORS AS MCP SERVERS (specs/allowance.md §10, §12, §13): the agent's — invoices taken into the plan,
 * the payments due paid in turn — and the owner's — the plan reviewed, approved, unchecked, edited, removed. The
 * agent's server holds the agent's keys (in its nodes) and the owner's address book; the model names a payee, an
 * amount, an invoice — never a key, never an address that is not the book's; the agent cannot approve its own
 * proposals. Every payment through the agent's judgement, then the contract. On okay-mcp: `dispatch` on the machine
 * with okay-simple's async module, one message a run, to a Future.
 */
final class McpServer(nodes: Map[String, Node], chains: Seq[String], book: Book, store: PlanStore,
                      role: Role = Role.Demo, journal: Entry => Unit = _ => (),
                      today: () => Long = () => (js.Date.now() / 1000).toLong)(using ExecutionContext):
  val servers: Servers[Machine.type] = new Servers[Machine.type]()(using Machine)
  import servers.{Server, Tools}
  import servers.fx.{!, pure}

  private def text(j: Json, k: String): Option[String] = j match
    case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s.trim; case Json.Num(n) => n.toString }
      .filter(_.nonEmpty)
    case _ => None
  private def flag(j: Json, k: String): Boolean = j match
    case o: Json.Obj => o.get(k).contains(Json.Bool(true))
    case _ => false
  private def strings(j: Json, k: String): Vector[String] = j match
    case o: Json.Obj => o.get(k) match
      case Some(Json.Arr(xs)) => xs.collect { case Json.Str(s) => s.trim }
      case Some(Json.Str(s)) => Vector(s.trim)
      case _ => Vector.empty
    case _ => Vector.empty
  private def str(s: String) = Json.Str(s)
  private def done(words: String): Future[ToolResult] = Future.successful(ToolResult.text(words))
  private def failed(words: String): Future[ToolResult] = Future.successful(ToolResult.error(words))
  private def obj(props: (String, String)*) = Json.obj("type" -> str("object"), "properties" -> Json.Obj(
    props.toVector.map((k, d) => k -> Json.obj("type" -> str("string"), "description" -> str(d)))))
  private def required(o: Json, names: String*) = o match
    case Json.Obj(fs) => Json.Obj(fs :+ ("required" -> Json.Arr(names.toVector.map(str))))
    case other => other

  /** the call's invoice: a payee by name, an amount, the invoice's id; a chain and an address if the model has them */
  private def invoice(c: ToolCall): Either[String, Invoice] =
    (text(c.arguments, "payee"), text(c.arguments, "amount"), text(c.arguments, "invoice")) match
      case (Some(p), Some(a), Some(i)) => Right(Invoice(i, p, text(c.arguments, "address"), a,
        text(c.arguments, "currency"), text(c.arguments, "for").getOrElse(""), text(c.arguments, "chain")))
      case _ => Left("payee, amount and invoice are required")

  private val invoiceFields = Seq(
    "payee" -> "the payee's name in the owner's address book",
    "amount" -> "the amount in tUSD8, e.g. \"49.99\"",
    "invoice" -> "the invoice's id; each is paid once",
    "chain" -> "the chain the invoice asks for, if any",
    "address" -> "the address the invoice names, if any: checked against the book, never used",
    "for" -> "what the invoice is for")
  private val windowFields = Seq("from" -> "the first day it may be paid, YYYY-MM-DD (default: today)",
    "to" -> "the last day, YYYY-MM-DD (default: a week after from)", "every" -> "once (default) or month")
  private val one = required(obj("invoice" -> "the payment's invoice id in the plan"), "invoice")

  private val agentTools = List(
    ToolSpec("check_payment", "Would this invoice be paid now? The agent's judgement and a trial on the chain; " +
      "nothing is written.", required(obj(invoiceFields*), "payee", "amount", "invoice")),
    ToolSpec("add_invoice", "Take an invoice into the plan: judged and tried on its chain, then PROPOSED to the " +
      "owner with its window (from – to) and the agent's note. Nothing is paid until the owner approves it.",
      required(obj((invoiceFields ++ windowFields)*), "payee", "amount", "invoice")),
    ToolSpec("pay_due", "Pay, one after another, the payments the owner approved and checked whose window is open " +
      "today — the earliest deadline first; each paid, waiting for the owner's signature, not yet, or refused.",
      Json.obj("type" -> str("object"))))
  private val ownerTools = List(
    ToolSpec("approve", "The owner approves payments of the plan: `invoices` (a list) or `all: true`.",
      Json.obj("type" -> str("object"), "properties" -> Json.obj(
        "invoices" -> Json.obj("type" -> str("array"), "items" -> Json.obj("type" -> str("string"))),
        "all" -> Json.obj("type" -> str("boolean"))))),
    ToolSpec("uncheck", "Not this time: the payment stays in the plan unpaid (a monthly one comes again next month).",
      one),
    ToolSpec("check", "Check a payment again, so it is paid in its window.", one),
    ToolSpec("edit", "Change a payment of the plan: its amount, its window (from, to), once or monthly.",
      required(obj(("invoice" -> "the payment's invoice id in the plan") +: ("amount" -> "the new amount in tUSD8") +:
        windowFields*), "invoice")),
    ToolSpec("remove", "Remove a payment from the plan; a monthly one stops for good.", one))
  private val shared = List(
    ToolSpec("plan", "The plan: each payment with its checkbox, window (from – to), state — proposed, due, " +
      "upcoming, unchecked, waiting, paid, refused, skipped, missed — and the agent's note.",
      obj("state" -> "only these: proposed, due, upcoming, unchecked, waiting, paid, refused, open (not finished)")),
    ToolSpec("status", "Each chain's policy: balance, this period's spending and cap, the most a payment, the " +
      "approval threshold, and each payee's cap, spending and next possible payment.", obj("chain" -> "one chain")),
    ToolSpec("requests", "Payments waiting for the owner's signature on the chain.", Json.obj("type" -> str("object"))),
    ToolSpec("address_book", "The owner's address book: each payee's address on each chain, and whether that " +
      "chain's policy allows it (its cap a period).", Json.obj("type" -> str("object"))))
  private val specs = role match
    case Role.Agent => agentTools ++ shared
    case Role.Owner => ownerTools ++ shared
    case Role.Demo => agentTools ++ ownerTools ++ shared

  // ---- the office: the plan's operations as data; here, worded ----
  val office: Office = Office(nodes, chains, book, store, today, journal)
  private def big(x: BigInt) = Office.money(x)

  private def addressBook: Future[ToolResult] = office.addressBook.map { rows =>
    val words = rows.map { e =>
      (e.name +: e.at.map { (c, a, p) =>
        s"  $c $a — " + p.fold("no node for this chain")(p =>
          if p.allowed then s"allowed, ${big(p.cap)} a period" else "not allowed on the policy")
      }).mkString("\n")
    }.mkString("\n")
    val json = Json.obj("payees" -> Json.Arr(rows.map { e =>
      Json.obj("name" -> str(e.name), "addresses" -> Json.Arr(e.at.map { (c, a, p) =>
        Json.obj("chain" -> str(c), "address" -> str(a), "allowed" -> Json.Bool(p.exists(_.allowed)),
          "cap" -> str(p.filter(_.allowed).fold("")(x => big(x.cap))))
      }))
    }))
    ToolResult(List(Content.Text(if words.isEmpty then "the address book is empty" else words)), Some(json))
  }

  /** THE PLAN, as it stands today: each line its checkbox, window, state, payee, amount, invoice, chain, monthly,
   * what for; the agent's note; the earliest date the contract allows */
  private def planView(only: Option[String]): Future[ToolResult] = office.plan.map { rows =>
    val now = today()
    val shown = rows.filter(r => only.map(_.toLowerCase).forall {
      case "open" => r.p.open
      case s => r.label.startsWith(s)
    })
    val lines = shown.map { r =>
      val p = r.p
      val limit = if r.earliest == 0 then "" else if r.earliest > p.closes then
        s"the contract allows it only from ${Bill.day(r.earliest)}, after the window"
        else s"the contract allows it from ${Bill.day(r.earliest)}"
      val notes = Vector(p.note, limit).filter(_.nonEmpty).mkString("; ")
      s"${if r.box.isEmpty then "   " else r.box} ${p.from} – ${p.to}  ${r.label}: ${p.payee}, ${p.amount} tUSD8, " +
        s"${p.invoice}" + p.chain.fold("")(c => s", on $c") + (if p.monthly then ", monthly" else "") +
        (if p.about.isEmpty then "" else s" (${p.about})") + (if notes.isEmpty then "" else s" — $notes")
    }
    val head = s"today ${Bill.day(now)}; " + (if shown.isEmpty then "nothing in the plan" + only.fold("")(o =>
      s" ($o)") else s"${shown.size} payment(s); [x] approved, [ ] unchecked, [?] waiting for the owner's review")
    val json = Json.obj("today" -> str(Bill.day(now)), "plan" -> Json.Arr(shown.map { r =>
      val p = r.p
      Json.obj("invoice" -> str(p.invoice), "payee" -> str(p.payee), "amount" -> str(p.amount),
        "chain" -> str(p.chain.getOrElse("")), "for" -> str(p.about), "from" -> str(p.from), "to" -> str(p.to),
        "every" -> str(p.every), "approved" -> Json.Bool(p.approved), "checked" -> Json.Bool(p.checked),
        "state" -> str(r.label), "note" -> str(p.note), "tx" -> str(p.tx),
        "earliest" -> str(if r.earliest == 0 then "" else Bill.day(r.earliest)))
    }))
    ToolResult(List(Content.Text((head +: lines).mkString("\n"))), Some(json))
  }

  private def status(only: Option[String]): Future[ToolResult] =
    if !chains.exists(c => only.forall(_.equalsIgnoreCase(c))) then failed(s"no such chain: ${only.getOrElse("")}")
    else office.policies.map { views =>
      ToolResult.text(views.filter(v => only.forall(_.equalsIgnoreCase(v.chain))).map { v =>
        v.status match
          case None => s"${v.chain}: no node"
          case Some(s) =>
            val head = s"${v.chain}: ${big(s.balance)} on the policy; this period ${big(s.spent)} of " +
              s"${big(s.periodCap)}; at most ${big(s.maxPayment)} a payment; above ${big(s.approvalAbove)} the " +
              "owner approves;" + (if s.paused then " PAUSED;" else "") + s" until ${Bill.day(s.expires)}"
            (head +: v.payees.map { (name, _, p) =>
              if !p.allowed then s"  $name: not allowed on the policy"
              else s"  $name: ${big(p.spent)} of ${big(p.cap)} this period" +
                (if p.nextAt == 0 then "" else s", next from ${Bill.day(p.nextAt)}")
            }).mkString("\n")
      }.mkString("\n"))
    }

  private def requests: Future[ToolResult] = office.requests.map { ws =>
    ToolResult.text(if ws.isEmpty then "no payment is waiting for the owner's signature"
      else ws.map(p => s"${p.chain.getOrElse("")} request ${p.request}: ${p.invoice} — ${p.note}").mkString("\n"))
  }

  // ---- the agent ----

  private def addInvoice(c: ToolCall): Future[ToolResult] = invoice(c) match
    case Left(e) => failed(e)
    case Right(inv) =>
      office.intake(inv, text(c.arguments, "from"), text(c.arguments, "to"), text(c.arguments, "every")
        .getOrElse("once")).map {
        case Left(e) => ToolResult.error(e)
        case Right(p) => ToolResult.text(p.state match
          case "refused" => s"${p.invoice} listed as refused — ${p.note}"
          case "paid" => s"${p.invoice}: ${p.note}"
          case _ => s"${p.invoice} proposed: ${p.amount} tUSD8 to ${p.payee}" + p.chain.fold("")(c => s" on $c") +
            s", ${p.from} – ${p.to}" + (if p.monthly then ", monthly" else "") +
            (if p.note.isEmpty then "" else s" — ${p.note}") + "; it waits for the owner's approval")
      }

  private def payDue: Future[ToolResult] = office.payDue.map { done =>
    ToolResult.text(if done.isEmpty then s"nothing due today (${Bill.day(today())})"
      else done.map(p => s"${p.invoice}: " + (if p.state == "refused" then s"refused — ${p.note}" else p.note))
        .mkString("\n"))
  }

  // ---- the owner ----

  private def owner(r: Either[String, Unit], said: String): Future[ToolResult] =
    r.fold(failed, _ => done(said))

  private val tools: Tools[Async] = new Tools[Async]:
    def list = pure[List[ToolSpec], Async](specs)
    def call(c: ToolCall): ToolResult ! Async = fromFuture[ToolResult, Async](
      if !specs.exists(_.name == c.name) then failed(s"no such tool here: ${c.name}")
      else c.name match
        case "check_payment" => invoice(c).fold(failed, inv => office.check(inv).map(v => ToolResult.text(v.words)))
        case "add_invoice" => addInvoice(c)
        case "pay_due" => payDue
        case "plan" => planView(text(c.arguments, "state"))
        case "status" => status(text(c.arguments, "chain"))
        case "requests" => requests
        case "address_book" => addressBook
        case "approve" =>
          if flag(c.arguments, "all") then owner(office.approveAll, "every open payment approved")
          else strings(c.arguments, "invoices") match
            case Vector() => failed("invoices (a list) or all: true")
            case ids => owner(office.approve(ids), s"approved: ${ids.mkString(", ")}")
        case "uncheck" | "check" | "remove" | "edit" => text(c.arguments, "invoice") match
          case None => failed("invoice is required")
          case Some(id) => c.name match
            case "uncheck" => owner(office.setChecked(id, on = false), s"$id unchecked: not paid this time")
            case "check" => owner(office.setChecked(id, on = true), s"$id checked")
            case "remove" => owner(office.remove(id), s"$id removed from the plan")
            case _ => owner(office.edit(id, text(c.arguments, "amount"), text(c.arguments, "from"),
              text(c.arguments, "to"), text(c.arguments, "every")), s"$id changed")
        case other => failed(s"no such tool: $other"))

  val server: Server[Async] = Server[Async]("okay-allowance-" + role.toString.toLowerCase, "0.1.0", tools,
    instructions = Some(role match
      case Role.Owner => "The owner's door to the agent's payment plan: review the plan, approve, uncheck for this " +
        "time, edit or remove payments. The agent pays only what is approved and checked, in its window."
      case _ => "Pays the owner's bills in tUSD8 by a plan: take invoices in with add_invoice (they wait for the " +
        "owner's approval), then pay_due pays what is approved and due, in turn. The contract bounds every payment."))

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
