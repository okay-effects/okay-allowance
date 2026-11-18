package okay.allowance

import scala.scalajs.js

/** A BILL TO PAY (specs/allowance.md §12): the owner's — its invoice, the payee by name, the amount, the chain if
 * fixed, what it is for, and its payment window: from and to (days, as `YYYY-MM-DD`; to inclusive) */
final case class Bill(invoice: String, payee: String, amount: String, chain: Option[String], about: String,
                      from: String, to: String):
  /** as an invoice the agent pays */
  def toInvoice: Invoice = Invoice(invoice, payee, None, amount, None, about, chain)
  /** the window in seconds: from its first second to its last day's end */
  def opens: Long = Bill.seconds(from)
  def closes: Long = Bill.seconds(to) + 86_400 - 1

object Bill:
  def seconds(day: String): Long = (js.Date.parse(day + "T00:00:00Z") / 1000).toLong
  def day(seconds: Long): String = new js.Date(seconds.toDouble * 1000).toISOString().take(10)

  /** a JSON array of bills */
  def parse(json: String): Vector[Bill] = from(js.JSON.parse(json))
  def from(a: js.Dynamic): Vector[Bill] =
    if js.isUndefined(a) || a == null then Vector.empty
    else a.asInstanceOf[js.Array[js.Dynamic]].toVector.map { o =>
      def opt(k: String) = Option(o.selectDynamic(k)).filterNot(js.isUndefined).map(_.toString).filter(_.nonEmpty)
      val bill = Bill(o.invoice.toString, o.payee.toString, o.amount.toString, opt("chain"), opt("for").getOrElse(""),
        o.from.toString, o.to.toString)
      require(bill.opens > 0 && bill.closes >= bill.opens, s"bill ${bill.invoice}: a window from ≤ to as YYYY-MM-DD")
      bill
    }

/** a bill's state today: paid, waiting for the owner, due now, upcoming, overdue — the chain it goes to, and the
 * earliest the contract allows when the payee's gap ends after the window opens (seconds; 0: no such limit) */
final case class BillState(bill: Bill, state: String, chain: String, earliest: Long)
