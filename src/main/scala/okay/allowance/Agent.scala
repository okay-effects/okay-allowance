package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import okay.scala3.*
import okay.scala3.std.*

/** an invoice as the agent reads it: its id, the payee's name, an address if the invoice names one, the amount, the
 * currency, the chain if it names one, what it is for */
final case class Invoice(id: String, payee: String, address: Option[String], amount: String, currency: Option[String],
                         about: String, chain: Option[String] = None)

object Invoice:
  /** from an invoice's JSON (`{ "id", "payee", "address"?, "amount", "currency"?, "chain"?, "for"? }`) */
  def parse(json: String): Invoice =
    val o = js.JSON.parse(json)
    def opt(k: String) = Option(o.selectDynamic(k)).filterNot(js.isUndefined).map(_.toString)
    Invoice(o.id.toString, o.payee.toString, opt("address"), o.amount.toString, opt("currency"),
      opt("for").getOrElse(""), opt("chain"))

/** THE OWNER'S ADDRESS BOOK: each payee's address on each chain it accepts (specs/allowance.md §6) */
final case class Book(payees: Map[String, Map[String, String]]):
  def find(name: String): Option[(String, Map[String, String])] = payees.find(_._1.equalsIgnoreCase(name.trim))

/** the agent's journal: one entry an invoice — paid, waiting for the owner (the request's number), or refused (by
 * the agent's own judgement or by the contract, the reason, in words); the chain; the transaction */
final case class Entry(invoice: String, status: String, by: String = "", reason: String = "", words: String,
                       tx: String = "", request: Int = -1, chain: String = "")

/** what the agent judged: a payment (the payee's name, the chain, its address there from the book, the amount) or a
 * refusal */
enum Decision:
  case Pay(payee: String, chain: String, address: String, amount: js.BigInt)
  case Refuse(reason: String, detail: String)

/** THE CHAIN, AS THE AGENT ASKS IT: on `chain`, a trial of a payment (no transaction), the payment itself */
enum Ledger[X]:
  case Trial(chain: String, to: String, amount: js.BigInt, invoice: String) extends Ledger[Outcome]
  case Pay(chain: String, to: String, amount: js.BigInt, invoice: String) extends Ledger[Outcome]

/** THE JOURNAL: an entry written */
enum Journal[X]:
  case Write(entry: Entry) extends Journal[Unit]

/** ONE CHAIN'S NODE, as a Ledger handler asks it: a trial and a payment of the owner's policy there */
trait Node:
  def trial(to: String, amount: js.BigInt, invoice: String): Future[Outcome]
  def pay(to: String, amount: js.BigInt, invoice: String): Future[Outcome]

/** the policy on the chain in memory (Chain): the tests', the demo's, the page's */
final class MemoryNode(c: Chain, policy: String, agent: Party) extends Node:
  def trial(to: String, amount: js.BigInt, invoice: String) =
    c.simulate(agent, policy, "SpendPolicy", "pay", to, amount, invoice)
  def pay(to: String, amount: js.BigInt, invoice: String) =
    c.call(agent, policy, "SpendPolicy", "pay", to, amount, invoice)

object Ledger:
  def trial[R[_]](chain: String, to: String, amount: js.BigInt, invoice: String)(using In[Ledger, R]): Outcome ! R =
    perform[Ledger](Trial(chain, to, amount, invoice))
  def pay[R[_]](chain: String, to: String, amount: js.BigInt, invoice: String)(using In[Ledger, R]): Outcome ! R =
    perform[Ledger](Pay(chain, to, amount, invoice))

  /** EACH CHAIN ITS NODE: the requests routed by their chain (memory, RPC, …); a chain with no node is refused */
  def on[R[_], Rest[_], A](nodes: Map[String, Node])(p: A ! R)(using Split[Ledger, R, Rest], In[Async, Rest]): A ! Rest =
    def none(chain: String) = Future.successful(Outcome(false, Some("NoSuchChain"), Vector(chain), 0, None,
      Vector.empty, ""))
    handle(p, Handler.general[Ledger, Rest, A, A](pure)([X] => (op: Ledger[X], k: X => A ! Rest) => op match
      case Trial(chain, to, amount, inv) => Async.fromFuture[Outcome, Rest](nodes.get(chain)
        .fold(none(chain))(_.trial(to, amount, inv))).flatMap(k)
      case Pay(chain, to, amount, inv) => Async.fromFuture[Outcome, Rest](nodes.get(chain)
        .fold(none(chain))(_.pay(to, amount, inv))).flatMap(k)))

object Journal:
  def write[R[_]](e: Entry)(using In[Journal, R]): Unit ! R = perform[Journal](Write(e))

  /** each entry handed to `f` (printed, appended to a file, shown on a page) */
  def to[R[_], Rest[_], A](f: Entry => Unit)(p: A ! R)(using Split[Journal, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Journal, Rest, A, A](pure)([X] => (op: Journal[X]) => op match
      case Write(e) => f(e)))

/**
 * THE AGENT (specs/allowance.md §4.3, §6): invoices in, payments out, every refusal explained — on several chains at
 * once. Its own judgement first (the payee from the owner's address book, never from the invoice; a different address
 * there is refused as invoice fraud; the chain: the invoice's if the payee accepts it there, else the first of the
 * owner's chains where the payee has an address), then a TRIAL of the payment on that chain, then the payment, read
 * back from the contract's events. The bound is each chain's contract, not this program. A program on two effects —
 * Ledger and Journal — whose handlers say where it runs.
 */
object Agent:
  val Decimals = 8 // tUSD8

  def invoiceId(id: String): String = Npm.viem.keccak256(Npm.viem.toHex(id)).asInstanceOf[String]
  private def units(s: String): Option[js.BigInt] =
    try Some(Npm.viem.parseUnits(s, Decimals).asInstanceOf[js.BigInt]) catch case _: Throwable => None
  def money(x: js.Any): String = s"${Npm.viem.formatUnits(x, Decimals)} tUSD8"

  /** THE AGENT'S OWN JUDGEMENT, before any chain; `chains` the owner's, in the owner's order of preference */
  def decide(inv: Invoice, book: Book, chains: Seq[String]): Decision =
    book.find(inv.payee) match
      case None => Decision.Refuse("unknown-payee", inv.payee)
      case Some((name, addresses)) =>
        val chain = inv.chain match
          case Some(c) => Some(c).filter(addresses.contains)
          case None => chains.find(addresses.contains)
        chain match
          case None => Decision.Refuse("no-chain", inv.chain.getOrElse(chains.mkString(", ")))
          case Some(c) =>
            val address = addresses(c)
            if inv.address.exists(!_.equalsIgnoreCase(address)) then
              Decision.Refuse("address-mismatch", s"${inv.address.get} ≠ $address")
            else if inv.currency.exists(cur => !cur.matches("(?i)t?usd[8cm]?")) then
              Decision.Refuse("currency", inv.currency.get)
            else units(inv.amount).filter(_ > js.BigInt(0)) match
              case None => Decision.Refuse("amount", inv.amount)
              case Some(a) => Decision.Pay(name, c, address, a)

  /** a refusal in words: the agent's own, or the contract's error with its arguments */
  def explain(reason: String, args: Vector[js.Any] = Vector.empty, payee: String = ""): String =
    def date(t: js.Any) = new js.Date(t.toString.toDouble * 1000).toISOString().take(10)
    val who = if payee.isEmpty then "this payee" else payee
    reason match
      case "unknown-payee" => "the payee is not in the owner's address book"
      case "no-chain" => "the payee has no address on the invoice's chain (or on any of the owner's chains)"
      case "address-mismatch" =>
        "the invoice names an address other than the address book's — possible invoice fraud; not paid"
      case "currency" => "the invoice is not in the policy's currency"
      case "amount" => "the invoice has no positive amount"
      case "NoSuchChain" => s"the agent has no node for that chain (${args.headOption.getOrElse("")})"
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

  /** ONE INVOICE: judged, tried on its chain, paid or refused; its entry written to the journal */
  def one[R[_]: Has[Ledger] : Has[Journal]](inv: Invoice, book: Book, chains: Seq[String]): Entry ! R =
    val entry: Entry ! R = decide(inv, book, chains) match
      case Decision.Refuse(reason, detail) =>
        val more = if detail.isEmpty then "" else s" ($detail)"
        pure(Entry(inv.id, "refused", "agent", reason, explain(reason) + more, chain = inv.chain.getOrElse("")))
      case Decision.Pay(payee, chain, address, amount) =>
        val id = invoiceId(inv.id)
        Ledger.trial[R](chain, address, amount, id).flatMap { t =>
          if !t.ok then pure(Entry(inv.id, "refused", "contract", t.reason.getOrElse(""),
            explain(t.reason.getOrElse(""), t.args, payee), chain = chain))
          else Ledger.pay[R](chain, address, amount, id).map { tx =>
            val paid = tx.events.find(_.name == "Paid")
            val asked = tx.events.find(_.name == "Requested")
            if !tx.ok then Entry(inv.id, "refused", "contract", tx.reason.getOrElse(""),
              explain(tx.reason.getOrElse(""), tx.args, payee), tx.hash, chain = chain)
            else asked match
              case Some(e) =>
                val n = e.args.id.toString.toInt
                Entry(inv.id, "waiting", words = s"${money(amount)} to $payee on $chain: above the approval " +
                  s"threshold — waiting for the owner (request $n)", tx = tx.hash, request = n, chain = chain)
              case None => Entry(inv.id, "paid", words = s"paid ${money(paid.fold(amount: js.Any)(_.args.amount))} " +
                s"to $payee on $chain", tx = tx.hash, chain = chain)
          }
        }
    entry.flatMap(e => Journal.write[R](e).map(_ => e))

  /** THE RUN: the invoices one after another */
  def run[R[_]: Has[Ledger] : Has[Journal]](invoices: Seq[Invoice], book: Book, chains: Seq[String])
      : Vector[Entry] ! R =
    invoices.foldLeft(pure[Vector[Entry], R](Vector.empty))((acc, inv) =>
      acc.flatMap(done => one[R](inv, book, chains).map(done :+ _)))

  type OnChains[X] = (Async + Ledger + Journal)[X]

  /** the agent on its nodes (one a chain), the journal handed to `journal`, the run as a future */
  def on(nodes: Map[String, Node], chains: Seq[String], invoices: Seq[Invoice], book: Book,
         journal: Entry => Unit = _ => ())(using ExecutionContext): Future[Vector[Entry]] =
    Async.toFuture(Journal.to(journal)(Ledger.on(nodes)(run[OnChains](invoices, book, chains))))

  /** THE DEMO: two chains in memory at once ("ethereum", "base"), each the life's policy (set up, no bill paid); the
   * owner's address book with each payee's chains; the invoices */
  val demoChains = Vector("ethereum", "base")
  def demo(compiled: js.Dynamic, invoices: Seq[Invoice])(using ExecutionContext)
      : Future[(Vector[Entry], Map[String, World], Book)] =
    for
      eth <- Life(compiled, stopAfter = "created")
      base <- Life(compiled, stopAfter = "created")
      worlds = Map("ethereum" -> eth, "base" -> base)
      p = eth.parties
      book = Book(Map(
        "Internet provider" -> Map("ethereum" -> p("internet provider").address),
        "Cloud hosting" -> Map("base" -> p("cloud hosting").address),
        "Electricity" -> Map("ethereum" -> p("electricity").address, "base" -> p("electricity").address),
        "Software licence" -> Map("ethereum" -> p("software licence").address),
        "Gym" -> Map("ethereum" -> p("gym").address))) // in the book, not on the policy's list: the contract refuses
      nodes = worlds.map((name, w) => name -> (MemoryNode(w.chain, w.policy, w.parties("agent")): Node))
      entries <- on(nodes, demoChains, invoices, book)
    yield (entries, worlds, book)
