package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import okay.scala3.*
import okay.scala3.std.*

/** an invoice as the agent reads it: its id, the payee's name, an address if the invoice names one, the amount, the
 * currency, what it is for */
final case class Invoice(id: String, payee: String, address: Option[String], amount: String, currency: Option[String],
                         about: String)

object Invoice:
  /** from an invoice's JSON (`{ "id", "payee", "address"?, "amount", "currency"?, "for"? }`) */
  def parse(json: String): Invoice =
    val o = js.JSON.parse(json)
    def opt(k: String) = Option(o.selectDynamic(k)).filterNot(js.isUndefined).map(_.toString)
    Invoice(o.id.toString, o.payee.toString, opt("address"), o.amount.toString, opt("currency"),
      opt("for").getOrElse(""))

/** the agent's journal: one entry an invoice — paid, waiting for the owner (the request's number), or refused (by
 * the agent's own judgement or by the contract, the reason, in words); the transaction */
final case class Entry(invoice: String, status: String, by: String = "", reason: String = "", words: String,
                       tx: String = "", request: Int = -1)

/** what the agent judged: a payment (the payee's name, its address from the book, the amount) or a refusal */
enum Decision:
  case Pay(payee: String, address: String, amount: js.BigInt)
  case Refuse(reason: String, detail: String)

/** THE CHAIN, AS THE AGENT ASKS IT: a trial of a payment (no transaction), the payment itself */
enum Ledger[X]:
  case Trial(to: String, amount: js.BigInt, invoice: String) extends Ledger[Outcome]
  case Pay(to: String, amount: js.BigInt, invoice: String) extends Ledger[Outcome]

/** THE JOURNAL: an entry written */
enum Journal[X]:
  case Write(entry: Entry) extends Journal[Unit]

object Ledger:
  def trial[R[_]](to: String, amount: js.BigInt, invoice: String)(using In[Ledger, R]): Outcome ! R =
    perform[Ledger](Trial(to, amount, invoice))
  def pay[R[_]](to: String, amount: js.BigInt, invoice: String)(using In[Ledger, R]): Outcome ! R =
    perform[Ledger](Pay(to, amount, invoice))

  /** ON THE CHAIN IN MEMORY (Chain): the tests', the demo's, the page's */
  def memory[R[_], Rest[_], A](c: Chain, policy: String, agent: Party)(p: A ! R)
      (using Split[Ledger, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Ledger, Rest, A, A](pure)([X] => (op: Ledger[X], k: X => A ! Rest) => op match
      case Trial(to, amount, inv) =>
        Async.fromFuture[Outcome, Rest](c.simulate(agent, policy, "SpendPolicy", "pay", to, amount, inv)).flatMap(k)
      case Pay(to, amount, inv) =>
        Async.fromFuture[Outcome, Rest](c.call(agent, policy, "SpendPolicy", "pay", to, amount, inv)).flatMap(k)))

  /** ON A REAL NODE over RPC (a testnet): viem's clients; the agent's key from a file outside the repository */
  def rpc[R[_], Rest[_], A](node: Rpc)(p: A ! R)(using Split[Ledger, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Ledger, Rest, A, A](pure)([X] => (op: Ledger[X], k: X => A ! Rest) => op match
      case Trial(to, amount, inv) => Async.fromFuture[Outcome, Rest](node.trial(to, amount, inv)).flatMap(k)
      case Pay(to, amount, inv) => Async.fromFuture[Outcome, Rest](node.pay(to, amount, inv)).flatMap(k)))

object Journal:
  def write[R[_]](e: Entry)(using In[Journal, R]): Unit ! R = perform[Journal](Write(e))

  /** each entry handed to `f` (printed, appended to a file, shown on a page) */
  def to[R[_], Rest[_], A](f: Entry => Unit)(p: A ! R)(using Split[Journal, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Journal, Rest, A, A](pure)([X] => (op: Journal[X]) => op match
      case Write(e) => f(e)))

/**
 * THE AGENT (specs/allowance.md §4.3): invoices in, payments out, every refusal explained. Its own judgement first
 * (the payee from the owner's address book, never from the invoice; a different address there is refused as invoice
 * fraud), then a TRIAL of the payment, then the payment, read back from the contract's events. The bound is the
 * contract, not this program: whatever this code does, the policy refuses what is outside it. A program on two
 * effects — Ledger and Journal — whose handlers say where it runs: the chain in memory or a real node.
 */
object Agent:
  val Decimals = 8 // tUSD8

  def invoiceId(id: String): String = Npm.viem.keccak256(Npm.viem.toHex(id)).asInstanceOf[String]
  private def units(s: String): Option[js.BigInt] =
    try Some(Npm.viem.parseUnits(s, Decimals).asInstanceOf[js.BigInt]) catch case _: Throwable => None
  def money(x: js.Any): String = s"${Npm.viem.formatUnits(x, Decimals)} tUSD8"

  /** THE AGENT'S OWN JUDGEMENT, before any chain */
  def decide(inv: Invoice, book: Map[String, String]): Decision =
    book.find(_._1.equalsIgnoreCase(inv.payee.trim)) match
      case None => Decision.Refuse("unknown-payee", inv.payee)
      case Some((name, address)) =>
        if inv.address.exists(!_.equalsIgnoreCase(address)) then
          Decision.Refuse("address-mismatch", s"${inv.address.get} ≠ $address")
        else if inv.currency.exists(c => !c.matches("(?i)t?usd8?")) then Decision.Refuse("currency", inv.currency.get)
        else units(inv.amount).filter(_ > js.BigInt(0)) match
          case None => Decision.Refuse("amount", inv.amount)
          case Some(a) => Decision.Pay(name, address, a)

  /** a refusal in words: the agent's own, or the contract's error with its arguments */
  def explain(reason: String, args: Vector[js.Any] = Vector.empty, payee: String = ""): String =
    def date(t: js.Any) = new js.Date(t.toString.toDouble * 1000).toISOString().take(10)
    val who = if payee.isEmpty then "this payee" else payee
    reason match
      case "unknown-payee" => "the payee is not in the owner's address book"
      case "address-mismatch" =>
        "the invoice names an address other than the address book's — possible invoice fraud; not paid"
      case "currency" => "the invoice is not in the policy's currency"
      case "amount" => "the invoice has no positive amount"
      case "NotAgent" => "this key is not the policy's agent"
      case "Paused" => "the owner has paused the agent"
      case "Expired" => "the policy has expired"
      case "PayeeNotAllowed" => "the payee is not on the policy's list of allowed payees"
      case "OverMax" => s"above the most one payment may be (${money(args(1))})"
      case "PayeeCap" => s"$who would get ${money(args(1))} this period; its cap is ${money(args(2))}"
      case "PeriodCap" => s"all payments this period would come to ${money(args(0))}; the cap is ${money(args(1))}"
      case "TooSoon" => s"$who was paid recently; the next payment is possible from ${date(args(1))}"
      case "AlreadyPaid" => "this invoice has already been paid"
      case "ZeroAmount" => "a payment of zero"
      case other => s"refused by the contract ($other) — e.g. the owner's wallet or allowance does not cover it"

  /** ONE INVOICE: judged, tried, paid or refused; its entry written to the journal */
  def one[R[_]: Has[Ledger] : Has[Journal]](inv: Invoice, book: Map[String, String]): Entry ! R =
    val entry: Entry ! R = decide(inv, book) match
      case Decision.Refuse(reason, detail) =>
        val more = if detail.isEmpty then "" else s" ($detail)"
        pure(Entry(inv.id, "refused", "agent", reason, explain(reason) + more))
      case Decision.Pay(payee, address, amount) =>
        Ledger.trial[R](address, amount, invoiceId(inv.id)).flatMap { t =>
          if !t.ok then pure(Entry(inv.id, "refused", "contract", t.reason.getOrElse(""),
            explain(t.reason.getOrElse(""), t.args, payee)))
          else Ledger.pay[R](address, amount, invoiceId(inv.id)).map { tx =>
            val paid = tx.events.find(_.name == "Paid")
            val asked = tx.events.find(_.name == "Requested")
            if !tx.ok then Entry(inv.id, "refused", "contract", tx.reason.getOrElse(""),
              explain(tx.reason.getOrElse(""), tx.args, payee), tx.hash)
            else asked match
              case Some(e) =>
                val id = e.args.id.toString.toInt
                Entry(inv.id, "waiting", words = s"${money(amount)} to $payee: above the approval threshold — " +
                  s"waiting for the owner (request $id)", tx = tx.hash, request = id)
              case None => Entry(inv.id, "paid", words = s"paid ${money(paid.fold(amount: js.Any)(_.args.amount))} " +
                s"to $payee", tx = tx.hash)
          }
        }
    entry.flatMap(e => Journal.write[R](e).map(_ => e))

  /** THE RUN: the invoices one after another */
  def run[R[_]: Has[Ledger] : Has[Journal]](invoices: Seq[Invoice], book: Map[String, String]): Vector[Entry] ! R =
    invoices.foldLeft(pure[Vector[Entry], R](Vector.empty))((acc, inv) =>
      acc.flatMap(done => one[R](inv, book).map(done :+ _)))

  type OnChain[X] = (Async + Ledger + Journal)[X]

  /** the agent on the chain in memory: the journal handed to `journal`, the run as a future */
  def onMemory(c: Chain, policy: String, agent: Party, invoices: Seq[Invoice], book: Map[String, String],
               journal: Entry => Unit = _ => ())(using ExecutionContext): Future[Vector[Entry]] =
    Async.toFuture(Journal.to(journal)(Ledger.memory(c, policy, agent)(run[OnChain](invoices, book))))

  /** THE DEMO: the policy of the life (set up, no bill paid), the owner's address book, the invoices */
  def demo(compiled: js.Dynamic, invoices: Seq[Invoice])(using ExecutionContext)
      : Future[(Vector[Entry], World, Map[String, String])] =
    Life(compiled, stopAfter = "created").flatMap { w =>
      val book = Map("Internet provider" -> w.parties("internet provider").address,
        "Cloud hosting" -> w.parties("cloud hosting").address, "Electricity" -> w.parties("electricity").address,
        "Software licence" -> w.parties("software licence").address,
        "Gym" -> w.parties("gym").address) // in the book, not on the policy's list: the contract's to refuse
      onMemory(w.chain, w.policy, w.parties("agent"), invoices, book).map(e => (e, w, book))
    }
