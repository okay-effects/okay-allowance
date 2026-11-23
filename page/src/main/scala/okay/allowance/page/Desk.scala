package okay.allowance.page

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import okay.scala3.*
import okay.scala3.std.*
import okay.allowance.{Agent, BookEntry, ChainView, Invoice, MemoryPlan, Office, Plan, Row}

/** what the owner (or, in the demo, the agent) does on the page: the plan's changes, the payments due paid, a
 * waiting payment signed on its chain, the demo's clock moved on */
enum Change:
  case Approve(invoice: String)
  case ApproveAll
  case Check(invoice: String, on: Boolean)
  case Edit(invoice: String, amount: String, from: String, to: String, every: String)
  case Remove(invoice: String)
  /** the owner signs a payment waiting on its chain (in the demo, with the owner's demo key) */
  case Sign(invoice: String)
  /** the agent's: an invoice taken into the plan; what is due paid */
  case Intake(invoice: Invoice, from: String, to: String, every: String)
  case PayDue
  /** the demo's clock, all chains: `days` later */
  case Later(days: Int)

/**
 * THE PAGE'S WORLD (specs/allowance.md §14), as an effect: the plan today, the policies, the address book, the day;
 * a change done, answered in words. The page's program asks; the demo's handler answers from an Office over three
 * chains in memory, a test's the same way.
 */
enum Desk[X]:
  case Plan() extends Desk[Vector[Row]]
  case Policies() extends Desk[Vector[ChainView]]
  case Book() extends Desk[Vector[BookEntry]]
  /** today, seconds */
  case Today() extends Desk[Long]
  case Do(change: Change) extends Desk[String]

object Desk:
  def plan[R[_]](using In[Desk, R]): Vector[Row] ! R = perform[Desk](Plan())
  def policies[R[_]](using In[Desk, R]): Vector[ChainView] ! R = perform[Desk](Policies())
  def book[R[_]](using In[Desk, R]): Vector[BookEntry] ! R = perform[Desk](Book())
  def today[R[_]](using In[Desk, R]): Long ! R = perform[Desk](Today())
  def run[R[_]](c: Change)(using In[Desk, R]): String ! R = perform[Desk](Do(c))

/**
 * THE DEMO'S DESK: the agent's demo — Ethereum, Base and Cardano in memory, each with the policy set up and 1 000
 * tUSD8 on it — the owner's standing orders as the plan, the clock starting on 2026-01-01. The owner's signature of a
 * waiting payment made with the owner's demo key (a real owner signs in their own wallet).
 */
final class DemoDesk(d: Agent.Demo, bills: Plan)(using ExecutionContext):
  private val eth = d.worlds("ethereum")
  val office: Office = Office(d.nodes, Agent.demoChains, d.book, MemoryPlan(bills),
    () => eth.chain.now.toString.toLong)

  private def done(r: Either[String, Unit], said: String): Future[String] =
    Future.successful(r.fold(e => s"Not done: $e", _ => said))

  def answer(c: Change): Future[String] = c match
    case Change.Approve(id) => done(office.approve(Seq(id)), s"$id approved")
    case Change.ApproveAll => done(office.approveAll, "every proposed payment approved")
    case Change.Check(id, on) => done(office.setChecked(id, on),
      if on then s"$id checked: it is paid in its window" else s"$id unchecked: not paid this time")
    case Change.Edit(id, amount, from, to, every) =>
      done(office.edit(id, Some(amount), Some(from), Some(to), Some(every)), s"$id changed")
    case Change.Remove(id) => done(office.remove(id), s"$id removed from the plan")
    case Change.Sign(id) => sign(id)
    case Change.Intake(inv, from, to, every) => office.intake(inv, Some(from), Some(to), every).map {
      case Left(e) => s"Not taken in: $e"
      case Right(p) if p.state == "refused" => s"${p.invoice} listed as refused — ${p.note}"
      case Right(p) => s"${p.invoice} proposed by the agent: it waits for your approval" +
        (if p.note.isEmpty then "" else s" — ${p.note}")
    }
    case Change.PayDue => office.payDue.map(ps =>
      if ps.isEmpty then "The agent found nothing due today"
      else ps.map(p => s"${p.invoice}: " + (if p.state == "refused" then s"refused — ${p.note}" else p.note))
        .mkString("; "))
    case Change.Later(days) =>
      val s = days * 86_400L
      d.worlds.values.foreach(_.chain.advance(s)); d.cardano.advance(s)
      Future.successful(s"$days day(s) later")

  /** the owner signs a waiting payment on its chain: SpendPolicy.approve on an EVM chain, the second signature of
   * the payment on Cardano */
  private def sign(id: String): Future[String] = office.store.load.find(id) match
    case Some(p) if p.state == "waiting" => p.chain match
      case Some("cardano") => Future.successful(d.cardano.approve(p.request).fold(e => s"Not signed: $e",
        _ => s"$id signed on cardano and paid"))
      case Some(c) if d.worlds.contains(c) =>
        val w = d.worlds(c)
        w.chain.call(w.parties("owner"), w.policy, "SpendPolicy", "approve", js.BigInt(p.request)).map(o =>
          if o.ok then s"$id signed on $c and paid" else s"Not signed: ${o.reason.getOrElse("")}")
      case other => Future.successful(s"Not signed: no chain ${other.getOrElse("")} here")
    case _ => Future.successful(s"Not signed: $id is not waiting for a signature")

  /** the handler: each question to the desk answered, later (`Async`) */
  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Desk, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Desk, Rest, A, A](pure)([X] => (op: Desk[X], k: X => A ! Rest) =>
      val later: Future[X] = op match
        case Desk.Plan() => office.plan
        case Desk.Policies() => office.policies
        case Desk.Book() => office.addressBook
        case Desk.Today() => Future.successful(office.today())
        case Desk.Do(c) => answer(c)
      Async.fromFuture[X, Rest](later).flatMap(k)))
