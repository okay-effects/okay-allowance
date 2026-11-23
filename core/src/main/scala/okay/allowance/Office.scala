package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

/** a payment of the plan as the owner sees it today: the payment, its label (proposed, due, upcoming, unchecked,
 * past its window, or its state), the earliest the contract allows it when the payee's gap ends after the window
 * opens (seconds; 0: no such limit) */
final case class Row(p: Planned, label: String, earliest: Long):
  /** the checkbox: [x] approved and checked, [ ] unchecked, [?] proposed; nothing for a payment finished */
  def box: String = if !p.open then "" else if !p.approved then "[?]" else if p.checked then "[x]" else "[ ]"

/** one chain's policy and the book's payees there (name, address, its standing); `None`: no node for it */
final case class ChainView(chain: String, status: Option[Status], payees: Vector[(String, String, PayeeStatus)])

/** a payee of the book: its address on each chain, and its standing there (`None`: no node for that chain) */
final case class BookEntry(name: String, at: Vector[(String, String, Option[PayeeStatus])])

/** what a check of an invoice says: would it be paid, wait for the owner's signature, or be refused, by whom */
final case class Verdict(verdict: String, by: String, chain: String, words: String)

/**
 * THE OFFICE (specs/allowance.md §13, §14): the plan's operations as data — one class the MCP server words and the
 * owner's page shows. The agent's: check an invoice, take one in, pay what is due. The owner's: approve, check or
 * uncheck, edit, remove. Both: the plan as it stands today, the requests waiting on the chains, each chain's policy,
 * the address book. The chains are its nodes; the plan its store; today its clock.
 */
final class Office(val nodes: Map[String, Node], val chains: Seq[String], val book: Book, val store: PlanStore,
                   val today: () => Long, journal: Entry => Unit = _ => ())(using ExecutionContext):

  /** each chain's policy and the book's payees there */
  def policies: Future[Vector[ChainView]] = Life.inOrder(chains) { chain =>
    val payees = book.payees.toVector.sortBy(_._1).flatMap((name, m) => m.get(chain).map(name -> _))
    nodes.get(chain) match
      case None => Future.successful(ChainView(chain, None, Vector.empty))
      case Some(n) => n.status(payees.map(_._2)).map(s =>
        ChainView(chain, Some(s), payees.zip(s.payees).map { case ((name, a), p) => (name, a, p) }))
  }

  def addressBook: Future[Vector[BookEntry]] = policies.map { views =>
    book.payees.toVector.sortBy(_._1).map { (name, m) =>
      BookEntry(name, chains.toVector.flatMap(c => m.get(c).map(a =>
        (c, a, views.find(_.chain == c).flatMap(v => v.status.flatMap(_ => v.payees.find(_._1 == name).map(_._3)))))))
    }
  }

  /** THE PLAN TODAY: refreshed (payments the owner signed on a chain now paid; ended windows rolled on) */
  def plan: Future[Vector[Row]] =
    val now = today()
    for
      p <- Planner.refresh(store, nodes, now)
      views <- policies
    yield p.items.map { x =>
      val next = if !x.open then 0L else (for c <- x.chain; v <- views.find(_.chain == c);
        r <- v.payees.find(_._1.equalsIgnoreCase(x.payee)) yield r._3.nextAt).getOrElse(0L)
      Row(x, x.label(now), if next > math.max(x.opens, now) then next else 0)
    }

  def requests: Future[Vector[Planned]] = plan.map(_.map(_.p).filter(_.state == "waiting"))

  // ---- the agent's ----

  /** would this invoice be paid now: the agent's judgement and a trial on its chain; nothing written */
  def check(inv: Invoice): Future[Verdict] = Agent.decide(inv, book, chains) match
    case Decision.Refuse(reason, detail) => Future.successful(Verdict("refused", "agent", inv.chain.getOrElse(""),
      s"would be refused by the agent: ${Agent.explain(reason)}" + (if detail.isEmpty then "" else s" ($detail)")))
    case Decision.Pay(payee, chain, address, amount) => nodes.get(chain) match
      case None => Future.successful(Verdict("refused", "agent", chain, Agent.explain("NoSuchChain", Vector(chain))))
      case Some(n) =>
        for t <- n.trial(address, amount, Agent.invoiceId(inv.id)); st <- n.status(Nil) yield
          val money = Agent.money(amount)
          if !t.ok then Verdict("refused", "contract", chain, s"would be refused by the contract on $chain: " +
            Agent.explain(t.reason.getOrElse(""), t.args, payee))
          else if BigInt(amount.toString) > st.approvalAbove then Verdict("waiting", "", chain,
            s"$money to $payee on $chain: within the rules, above ${Office.money(st.approvalAbove)} — it would wait " +
              "for the owner's signature")
          else Verdict("paid", "", chain, s"$money to $payee on $chain would be paid")

  /** TAKE AN INVOICE IN: judged, tried, proposed (or listed as refused) — or why not (in the plan already, bad
   * `every`) */
  def intake(inv: Invoice, from: Option[String], to: Option[String], every: String): Future[Either[String, Planned]] =
    if store.load.find(inv.id).isDefined then Future.successful(Left(s"${inv.id} is in the plan already"))
    else if !Set("once", "month")(every) then Future.successful(Left("every: once or month"))
    else Planner.intake(inv, from, to, every, today(), nodes, chains, book).map { p =>
      store.load.add(p).map { plan => store.save(plan); p }
    }

  /** PAY WHAT IS DUE, in turn */
  def payDue: Future[Vector[Planned]] = Planner.payDue(store, nodes, chains, book, today(), journal)

  // ---- the owner's ----

  private def change(f: Plan => Either[String, Plan]): Either[String, Unit] =
    f(store.load).map(store.save)
  def approve(invoices: Seq[String]): Either[String, Unit] = change(_.approve(invoices))
  def approveAll: Either[String, Unit] = change(p => Right(p.approveAll))
  def setChecked(invoice: String, on: Boolean): Either[String, Unit] = change(_.check(invoice, on))
  def edit(invoice: String, amount: Option[String], from: Option[String], to: Option[String], every: Option[String])
      : Either[String, Unit] = change(_.edit(invoice, amount, from, to, every))
  def remove(invoice: String): Either[String, Unit] = change(_.remove(invoice))

object Office:
  def money(x: BigInt): String = Agent.money(js.BigInt(x.toString))
