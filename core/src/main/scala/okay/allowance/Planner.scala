package okay.allowance

import scala.concurrent.{ExecutionContext, Future}

/**
 * THE AGENT ON THE PLAN (specs/allowance.md §13): an invoice taken in — judged, tried on its chain, proposed with its
 * window and the agent's note —; the payments due paid one after another, the earliest deadline first, each through
 * the agent's judgement and the contract; a payment waiting for the owner's signature seen paid once the chain says
 * so; windows that ended rolled on.
 */
object Planner:
  /** refusals that may pass later in the window: tried again on the next run */
  val notYet = Set("TooSoon", "PayeeCap", "PeriodCap", "InsufficientBalance", "Paused", "NoSuchChain")
  private val Week = 7 * 86_400L

  /** INTAKE: the invoice proposed (or refused, for the owner to see), its window from – to (from today and a week by
   * default), once or monthly */
  def intake(inv: Invoice, from: Option[String], to: Option[String], every: String, today: Long,
             nodes: Map[String, Node], chains: Seq[String], book: Book)(using ExecutionContext): Future[Planned] =
    val start = from.getOrElse(Bill.day(today))
    val end = to.getOrElse(Bill.day(Bill.seconds(start) + Week))
    def planned(payee: String, chain: Option[String], state: String, note: String) =
      Planned(inv.id, inv.id, payee, inv.amount, chain, inv.about, start, end, every, approved = false,
        checked = true, state, note)
    Agent.decide(inv, book, chains) match
      case Decision.Refuse(reason, detail) => Future.successful(planned(inv.payee, inv.chain, "refused",
        s"the agent refuses it: ${Agent.explain(reason)}" + (if detail.isEmpty then "" else s" ($detail)")))
      case Decision.Pay(payee, chain, address, amount) => nodes.get(chain) match
        case None => Future.successful(planned(payee, Some(chain), "refused", Agent.explain("NoSuchChain",
          Vector(chain))))
        case Some(n) =>
          for t <- n.trial(address, amount, Agent.invoiceId(inv.id)); s <- n.status(Nil) yield
            val reason = t.reason.getOrElse("")
            if reason == "AlreadyPaid" then planned(payee, Some(chain), "paid", "already paid on the chain")
            else planned(payee, Some(chain), "open",
              if !t.ok then s"the contract would refuse it now: ${Agent.explain(reason, t.args, payee)}"
              else if BigInt(amount.toString) > s.approvalAbove then
                s"above ${Agent.money(scala.scalajs.js.BigInt(s.approvalAbove.toString))}: the owner signs on the " +
                  "chain too"
              else "")

  /** a payment's outcome, as the plan keeps it */
  def outcome(planned: Planned, e: Entry): Planned =
    // the chain the agent used, kept: a payment waiting is checked there later
    val p = if e.chain.isEmpty then planned else planned.copy(chain = Some(e.chain))
    e.status match
      case "paid" => p.copy(state = "paid", tx = e.tx, note = e.words)
      case "waiting" => p.copy(state = "waiting", request = e.request, tx = e.tx, note = e.words)
      case _ if e.reason == "AlreadyPaid" => p.copy(state = "paid", note = "already paid on the chain")
      case _ if e.by == "contract" && notYet(e.reason) => p.copy(note = s"not yet: ${e.words}")
      case _ => p.copy(state = "refused", note = e.words)

  /** THE PLAN AS IT STANDS TODAY: payments waiting for the owner's signature that the chain now has paid; the windows
   * that ended rolled on (monthly ones come again); saved */
  def refresh(store: PlanStore, nodes: Map[String, Node], today: Long)(using ExecutionContext): Future[Plan] =
    val plan = store.load
    Life.inOrder(plan.items.filter(_.state == "waiting")) { p =>
      p.chain.flatMap(nodes.get).fold(Future.successful(p))(_.paid(Agent.invoiceId(p.invoice))
        .map(paid => if paid then p.copy(state = "paid", note = "paid on the owner's approval on the chain") else p))
    }.map { checked =>
      val next = checked.foldLeft(plan)((pl, p) => pl.record(p)).roll(today)
      store.save(next)
      next
    }

  /** PAY WHAT IS DUE, in turn: each payment's outcome saved before the next is tried */
  def payDue(store: PlanStore, nodes: Map[String, Node], chains: Seq[String], book: Book, today: Long,
             journal: Entry => Unit = _ => ())(using ExecutionContext): Future[Vector[Planned]] =
    refresh(store, nodes, today).flatMap { plan =>
      Life.inOrder(plan.due(today)) { p =>
        Agent.on(nodes, chains, Vector(p.toInvoice), book, journal).map { es =>
          val q = outcome(p, es.head)
          store.save(store.load.record(q))
          q
        }
      }
    }
