package okay.allowance

import scala.scalajs.js

/**
 * A PAYMENT IN THE PLAN (specs/allowance.md §13): its invoice (unique in the plan; a monthly one's is its series and
 * month, `INT/2026-02`), the series, the payee by the book's name, the amount, the chain (the agent's choice at
 * intake), what for, its window (from – to, days), once or every month; the owner's approval and checkbox; its
 * state — open, paid, waiting (for the owner's signature on the chain), refused, skipped (unchecked when its window
 * ended), missed (its window ended unpaid) —; the agent's note; the transaction, the request's number.
 */
final case class Planned(invoice: String, series: String, payee: String, amount: String, chain: Option[String],
                         about: String, from: String, to: String, every: String, approved: Boolean, checked: Boolean,
                         state: String, note: String = "", tx: String = "", request: Int = -1):
  def toInvoice: Invoice = Invoice(invoice, payee, None, amount, None, about, chain)
  def opens: Long = Bill.seconds(from)
  def closes: Long = Bill.seconds(to) + 86_400 - 1
  def open: Boolean = state == "open"
  def monthly: Boolean = every == "month"
  /** what the owner sees: proposed, unchecked, upcoming, due, past its window — or the state */
  def label(today: Long): String =
    if !open then state
    else if !approved then "proposed"
    else if !checked then "unchecked"
    else if today < opens then "upcoming"
    else if today > closes then "past its window"
    else "due"

/** THE PLAN: the payments, in the order they came; every change a new plan (§13) */
final case class Plan(items: Vector[Planned]):
  def find(invoice: String): Option[Planned] = items.find(_.invoice == invoice)
  private def change(invoice: String)(f: Planned => Either[String, Planned]): Either[String, Plan] =
    items.indexWhere(_.invoice == invoice) match
      case -1 => Left(s"no payment $invoice in the plan")
      case i => f(items(i)).map(p => Plan(items.updated(i, p)))
  private def openOnly(p: Planned): Either[String, Planned] =
    if p.open then Right(p) else Left(s"${p.invoice} is ${p.state}: nothing to change")

  // ---- the agent ----
  def add(p: Planned): Either[String, Plan] =
    if find(p.invoice).isDefined then Left(s"${p.invoice} is in the plan already") else Right(Plan(items :+ p))

  /** the payments to make now, the earliest deadline first: approved, checked, open, today in their window */
  def due(today: Long): Vector[Planned] =
    items.zipWithIndex.filter((p, _) => p.open && p.approved && p.checked && p.opens <= today && today <= p.closes)
      .sortBy((p, i) => (p.closes, p.opens, i)).map(_._1)

  /** a payment's outcome recorded; a monthly one paid brings the next month's */
  def record(p: Planned): Plan =
    val next = Plan(items.map(x => if x.invoice == p.invoice then p else x))
    if p.state == "paid" then next.withNext(p) else next

  /** windows that ended: unpaid payments skipped (unchecked) or missed; monthly ones come again */
  def roll(today: Long): Plan =
    items.filter(p => p.open && p.closes < today).foldLeft(this) { (plan, p) =>
      plan.record(p.copy(state = if p.checked then "missed" else "skipped")).withNext(p)
    }

  private def withNext(p: Planned): Plan =
    if !p.monthly then this
    else
      val from = Plan.plusMonth(p.from); val to = Plan.plusMonth(p.to)
      if items.exists(x => x.series == p.series && x.from == from) then this
      else Plan(items :+ p.copy(invoice = s"${p.series}/${from.take(7)}", from = from, to = to, checked = true,
        state = "open", note = "", tx = "", request = -1))

  // ---- the owner ----
  def approve(invoices: Seq[String]): Either[String, Plan] =
    invoices.foldLeft[Either[String, Plan]](Right(this))((acc, i) => acc.flatMap(_.change(i)(p =>
      openOnly(p).map(_.copy(approved = true)))))
  def approveAll: Plan = Plan(items.map(p => if p.open then p.copy(approved = true) else p))
  def check(invoice: String, on: Boolean): Either[String, Plan] =
    change(invoice)(p => openOnly(p).map(_.copy(checked = on)))
  def edit(invoice: String, amount: Option[String], from: Option[String], to: Option[String], every: Option[String])
      : Either[String, Plan] = change(invoice) { p =>
    openOnly(p).flatMap { p =>
      val q = p.copy(amount = amount.getOrElse(p.amount), from = from.getOrElse(p.from), to = to.getOrElse(p.to),
        every = every.getOrElse(p.every))
      if amount.exists(a => a.toDoubleOption.forall(_ <= 0)) then Left(s"amount: a positive number, not ${amount.get}")
      else if !Set("once", "month")(q.every) then Left("every: once or month")
      else if Bill.seconds(q.from) <= 0 || Bill.seconds(q.to) <= 0 || q.closes < q.opens then
        Left("from and to: dates YYYY-MM-DD, from ≤ to")
      else Right(q)
    }
  }
  /** gone from the plan; a monthly one stops for good (its paid months stay as history) */
  def remove(invoice: String): Either[String, Plan] =
    find(invoice) match
      case None => Left(s"no payment $invoice in the plan")
      case Some(p) if !p.open => Left(s"$invoice is ${p.state}: history stays")
      case Some(_) => Right(Plan(items.filterNot(_.invoice == invoice)))

  def json: String = js.JSON.stringify(js.Array(items.map { p =>
    js.Dynamic.literal(invoice = p.invoice, series = p.series, payee = p.payee, amount = p.amount,
      chain = p.chain.getOrElse(""), `for` = p.about, from = p.from, to = p.to, every = p.every,
      approved = p.approved, checked = p.checked, state = p.state, note = p.note, tx = p.tx, request = p.request)
  }*), null, 1)

object Plan:
  val empty: Plan = Plan(Vector.empty)
  /** the owner's standing orders: approved, checked; a monthly one's invoice is its series and month (INT/2026-01) */
  def seed(bills: Seq[Bill]): Plan = Plan(bills.toVector.map(b => Planned(
    if b.every == "month" then s"${b.invoice}/${b.from.take(7)}" else b.invoice, b.invoice, b.payee, b.amount,
    b.chain, b.about, b.from, b.to, b.every, approved = true, checked = true, state = "open")))
  def parse(text: String): Plan = Plan(js.JSON.parse(text).asInstanceOf[js.Array[js.Dynamic]].toVector.map { o =>
    val chain = o.chain.toString
    Planned(o.invoice.toString, o.series.toString, o.payee.toString, o.amount.toString,
      Option(chain).filter(_.nonEmpty), o.selectDynamic("for").toString, o.from.toString, o.to.toString,
      o.every.toString, o.approved.asInstanceOf[Boolean], o.checked.asInstanceOf[Boolean], o.state.toString,
      o.note.toString, o.tx.toString, o.request.asInstanceOf[Int])
  })
  /** the same day a month on, the month's last if it has fewer days (Jan 31 → Feb 28) */
  def plusMonth(day: String): String =
    val Array(y, m, d) = day.split('-').map(_.toInt)
    val (ny, nm) = if m == 12 then (y + 1, 1) else (y, m + 1)
    val last = new js.Date(js.Date.UTC(ny, nm, 0)).getUTCDate().toInt
    f"$ny%04d-$nm%02d-${math.min(d, last)}%02d"

/** where the plan lives: memory (the demo, the tests, the page), a JSON file (real nodes: FilePlan, beside the
 * command line) */
trait PlanStore:
  def load: Plan
  def save(p: Plan): Unit

final class MemoryPlan(var plan: Plan = Plan.empty) extends PlanStore:
  def load: Plan = plan
  def save(p: Plan): Unit = plan = p
