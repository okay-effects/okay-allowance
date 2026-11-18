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
  private def big(x: BigInt) = Agent.money(js.BigInt(x.toString))
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

  // ---- shared ----

  /** each chain's standing for the book's payees there: chain → (name, address, its standing) */
  private def standing: Future[Map[String, Vector[(String, String, PayeeStatus)]]] =
    Life.inOrder(chains.filter(nodes.contains)) { chain =>
      val payees = book.payees.toVector.sortBy(_._1).flatMap((name, m) => m.get(chain).map(name -> _))
      nodes(chain).status(payees.map(_._2)).map(s =>
        chain -> payees.zip(s.payees).map { case ((n, a), p) => (n, a, p) })
    }.map(_.toMap)

  private def addressBook: Future[ToolResult] = standing.map { st =>
    val rows = book.payees.toVector.sortBy(_._1).map { (name, m) =>
      name -> chains.flatMap(c => m.get(c).map(a => (c, a, st.get(c).flatMap(_.find(_._1 == name)).map(_._3))))
    }
    val words = rows.map { (name, at) =>
      (name +: at.map { (c, a, p) =>
        s"  $c $a — " + p.fold("no node for this chain")(p =>
          if p.allowed then s"allowed, ${big(p.cap)} a period" else "not allowed on the policy")
      }).mkString("\n")
    }.mkString("\n")
    val json = Json.obj("payees" -> Json.Arr(rows.map { (name, at) =>
      Json.obj("name" -> str(name), "addresses" -> Json.Arr(at.toVector.map { (c, a, p) =>
        Json.obj("chain" -> str(c), "address" -> str(a), "allowed" -> Json.Bool(p.exists(_.allowed)),
          "cap" -> str(p.filter(_.allowed).fold("")(x => big(x.cap))))
      }))
    }))
    ToolResult(List(Content.Text(if words.isEmpty then "the address book is empty" else words)), Some(json))
  }

  /** THE PLAN, as it stands today: each line its checkbox ([x] approved and checked, [ ] unchecked, [?] proposed),
   * window, state, payee, amount, invoice, chain, monthly, what for; the agent's note; the earliest date the contract
   * allows when the payee's gap ends after the window opens */
  private def planView(only: Option[String]): Future[ToolResult] =
    val now = today()
    for
      plan <- Planner.refresh(store, nodes, now)
      st <- standing
    yield
      def earliest(p: Planned): Long =
        if !p.open then 0
        else (for c <- p.chain; rows <- st.get(c); r <- rows.find(_._1.equalsIgnoreCase(p.payee))
          yield r._3.nextAt).filter(_ > math.max(p.opens, now)).getOrElse(0L)
      val shown = plan.items.filter(p => only.map(_.toLowerCase).forall {
        case "open" => p.open
        case s => p.label(now).startsWith(s)
      })
      val lines = shown.map { p =>
        val box = if !p.open then "   " else if !p.approved then "[?]" else if p.checked then "[x]" else "[ ]"
        val e = earliest(p)
        val limit = if e == 0 then "" else if e > p.closes then
          s"the contract allows it only from ${Bill.day(e)}, after the window"
          else s"the contract allows it from ${Bill.day(e)}"
        val notes = Vector(p.note, limit).filter(_.nonEmpty).mkString("; ")
        s"$box ${p.from} – ${p.to}  ${p.label(now)}: ${p.payee}, ${p.amount} tUSD8, ${p.invoice}" +
          p.chain.fold("")(c => s", on $c") + (if p.monthly then ", monthly" else "") +
          (if p.about.isEmpty then "" else s" (${p.about})") +
          (if notes.isEmpty then "" else s" — $notes")
      }
      val head = s"today ${Bill.day(now)}; " + (if shown.isEmpty then "nothing in the plan" + only.fold("")(o =>
        s" ($o)") else s"${shown.size} payment(s); [x] approved, [ ] unchecked, [?] waiting for the owner's review")
      val json = Json.obj("today" -> str(Bill.day(now)), "plan" -> Json.Arr(shown.map { p =>
        Json.obj("invoice" -> str(p.invoice), "payee" -> str(p.payee), "amount" -> str(p.amount),
          "chain" -> str(p.chain.getOrElse("")), "for" -> str(p.about), "from" -> str(p.from), "to" -> str(p.to),
          "every" -> str(p.every), "approved" -> Json.Bool(p.approved), "checked" -> Json.Bool(p.checked),
          "state" -> str(p.label(now)), "note" -> str(p.note), "tx" -> str(p.tx),
          "earliest" -> str(if earliest(p) == 0 then "" else Bill.day(earliest(p))))
      }))
      ToolResult(List(Content.Text((head +: lines).mkString("\n"))), Some(json))

  private def status(only: Option[String]): Future[ToolResult] =
    val shown = chains.filter(c => only.forall(_.equalsIgnoreCase(c)))
    if shown.isEmpty then failed(s"no such chain: ${only.getOrElse("")}") else
    Life.inOrder(shown) { chain =>
      val payees = book.payees.toVector.sortBy(_._1).flatMap((name, m) => m.get(chain).map(name -> _))
      nodes.get(chain) match
        case None => Future.successful(s"$chain: no node")
        case Some(n) => n.status(payees.map(_._2)).map { s =>
          val head = s"$chain: ${big(s.balance)} on the policy; this period ${big(s.spent)} of ${big(s.periodCap)};" +
            s" at most ${big(s.maxPayment)} a payment; above ${big(s.approvalAbove)} the owner approves;" +
            (if s.paused then " PAUSED;" else "") + s" until ${Bill.day(s.expires)}"
          val lines = payees.zip(s.payees).map { case ((name, _), p) =>
            if !p.allowed then s"  $name: not allowed on the policy"
            else s"  $name: ${big(p.spent)} of ${big(p.cap)} this period" +
              (if p.nextAt == 0 then "" else s", next from ${Bill.day(p.nextAt)}")
          }
          (head +: lines).mkString("\n")
        }
    }.map(ls => ToolResult.text(ls.mkString("\n")))

  private def requests: Future[ToolResult] = Planner.refresh(store, nodes, today()).map { plan =>
    val ws = plan.items.filter(_.state == "waiting")
    ToolResult.text(if ws.isEmpty then "no payment is waiting for the owner's signature"
      else ws.map(p => s"${p.chain.getOrElse("")} request ${p.request}: ${p.invoice} — ${p.note}").mkString("\n"))
  }

  // ---- the agent ----

  private def check(inv: Invoice): Future[ToolResult] = Agent.decide(inv, book, chains) match
    case Decision.Refuse(reason, detail) =>
      done(s"would be refused by the agent: ${Agent.explain(reason)}" + (if detail.isEmpty then "" else s" ($detail)"))
    case Decision.Pay(payee, chain, address, amount) => nodes.get(chain) match
      case None => done(Agent.explain("NoSuchChain", Vector(chain)))
      case Some(n) =>
        for t <- n.trial(address, amount, Agent.invoiceId(inv.id)); st <- n.status(Nil) yield
          val money = Agent.money(amount)
          ToolResult.text(
            if !t.ok then s"would be refused by the contract on $chain: " +
              Agent.explain(t.reason.getOrElse(""), t.args, payee)
            else if BigInt(amount.toString) > st.approvalAbove then
              s"$money to $payee on $chain: within the rules, above ${big(st.approvalAbove)} — it would wait for the " +
                "owner's signature"
            else s"$money to $payee on $chain would be paid")

  private def addInvoice(c: ToolCall): Future[ToolResult] = invoice(c) match
    case Left(e) => failed(e)
    case Right(inv) =>
      val every = text(c.arguments, "every").getOrElse("once")
      if store.load.find(inv.id).isDefined then failed(s"${inv.id} is in the plan already")
      else if !Set("once", "month")(every) then failed("every: once or month")
      else Planner.intake(inv, text(c.arguments, "from"), text(c.arguments, "to"), every, today(), nodes, chains,
        book).map { p =>
        store.load.add(p) match
          case Left(e) => ToolResult.error(e)
          case Right(plan) =>
            store.save(plan)
            ToolResult.text(p.state match
              case "refused" => s"${p.invoice} listed as refused — ${p.note}"
              case "paid" => s"${p.invoice}: ${p.note}"
              case _ => s"${p.invoice} proposed: ${p.amount} tUSD8 to ${p.payee}" + p.chain.fold("")(c => s" on $c") +
                s", ${p.from} – ${p.to}" + (if p.monthly then ", monthly" else "") +
                (if p.note.isEmpty then "" else s" — ${p.note}") + "; it waits for the owner's approval")
      }

  private def payDue: Future[ToolResult] =
    Planner.payDue(store, nodes, chains, book, today(), journal).map { done =>
      ToolResult.text(if done.isEmpty then s"nothing due today (${Bill.day(today())})"
        else done.map(p => s"${p.invoice}: " + (p.state match
          case "paid" => p.note
          case "waiting" => p.note
          case "open" => p.note
          case _ => s"refused — ${p.note}")).mkString("\n"))
    }

  // ---- the owner ----

  private def owner(f: Plan => Either[String, Plan], said: String): Future[ToolResult] =
    f(store.load) match
      case Left(e) => failed(e)
      case Right(p) => store.save(p); done(said)

  private val tools: Tools[Async] = new Tools[Async]:
    def list = pure[List[ToolSpec], Async](specs)
    def call(c: ToolCall): ToolResult ! Async = fromFuture[ToolResult, Async](
      if !specs.exists(_.name == c.name) then failed(s"no such tool here: ${c.name}")
      else c.name match
        case "check_payment" => invoice(c).fold(failed, check)
        case "add_invoice" => addInvoice(c)
        case "pay_due" => payDue
        case "plan" => planView(text(c.arguments, "state"))
        case "status" => status(text(c.arguments, "chain"))
        case "requests" => requests
        case "address_book" => addressBook
        case "approve" =>
          if flag(c.arguments, "all") then owner(p => Right(p.approveAll), "every open payment approved")
          else strings(c.arguments, "invoices") match
            case Vector() => failed("invoices (a list) or all: true")
            case ids => owner(_.approve(ids), s"approved: ${ids.mkString(", ")}")
        case "uncheck" | "check" | "remove" | "edit" => text(c.arguments, "invoice") match
          case None => failed("invoice is required")
          case Some(id) => c.name match
            case "uncheck" => owner(_.check(id, on = false), s"$id unchecked: not paid this time")
            case "check" => owner(_.check(id, on = true), s"$id checked")
            case "remove" => owner(_.remove(id), s"$id removed from the plan")
            case _ => owner(_.edit(id, text(c.arguments, "amount"), text(c.arguments, "from"),
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
