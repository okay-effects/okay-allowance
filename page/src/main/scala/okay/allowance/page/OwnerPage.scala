package okay.allowance.page

import okay.business.scala3.*
import okay.allowance.{Bill, Invoice, Office, Row}

/**
 * THE OWNER'S PAGE (specs/allowance.md §14): the plan the agent keeps — each payment with its checkbox and its own
 * buttons (approve, uncheck or check, edit, remove; sign one waiting on its chain) —, each chain's policy, the
 * address book; in the demo the agent's side too (take an invoice in, pay what is due) and the clock. A program of
 * okay-ui screens over two effects: `Ui` (the person) and `Desk` (the page's world).
 */
object OwnerPage:
  /** what a press on the plan's screen asks */
  enum Act:
    case Do(change: Change)
    case Edit(row: Row)
    case Intake, Policies, Book

  private def amount(s: String): String = s"$s tUSD8"
  private def day(seconds: Long): Day = Day.parse(Bill.day(seconds)).toOption.get
  private def plain(b: BigDecimal): String = b.bigDecimal.stripTrailingZeros.toPlainString

  /** the plan's screen, for as long as the page is open; `said`: what the last press did */
  def main[R[_]: Has[Ui]: Has[Desk]](said: Option[String] = None): Unit ! R =
    for
      rows <- Desk.plan[R]
      views <- Desk.policies[R]
      today <- Desk.today[R]
      act <- show(planScreen(rows, views, today, said))
      next <- act match
        case Act.Do(c) => Desk.run[R](c).map(Some(_))
        case Act.Edit(r) => change[R](r)
        case Act.Intake => intake[R](today)
        case Act.Policies => policies[R].as(None)
        case Act.Book => book[R].as(None)
      _ <- defer(main[R](next))
    yield ()

  def planScreen(rows: Vector[Row], views: Vector[okay.allowance.ChainView], today: Long, said: Option[String])
      : Screen[Act] =
    // each cell readable alone: on a phone a row is a card, its cells one under another (page/web/index.html)
    val chains = Table(views.toList)(Column.text("Chain")(_.chain),
      Column.text("On the policy")(v => v.status.fold("no node")(s => s"${Office.money(s.balance)} on the policy")),
      Column.text("Paid this period")(v => v.status.fold("")(s => s"paid ${Office.money(s.spent)} of " +
        s"${Office.money(s.periodCap)} this period")),
      Column.text("Your signature")(v => v.status.fold("")(s =>
        s"your signature above ${Office.money(s.approvalAbove)}")))
      .keyedBy(_.chain)
    def slot(r: Row) = s"row-${r.p.invoice}"
    val plan = Table(rows.toList)(
      Column.text("")(_.box),
      Column.text("Payment")(r => s"${r.p.payee} — ${amount(r.p.amount)}"),
      Column.text("Window")(r => s"${r.p.from} – ${r.p.to}: ${r.label}"),
      Column.text("Invoice")(r => r.p.invoice + (if r.p.monthly then ", monthly" else "") +
        r.p.chain.fold("")(c => s", on $c")),
      Column.text("Note")(r => Vector(r.p.note, if r.earliest == 0 then "" else
        s"the contract allows it from ${Bill.day(r.earliest)}").filter(_.nonEmpty).mkString("; ")),
      Column.slot("")(slot))
      .keyedBy(_.p.invoice).whenEmpty("nothing in the plan")
    val rowButtons = rows.toList.flatMap { r =>
      val id = r.p.invoice
      def b(label: String, a: Act, style: Style = Style.Plain) = Button(label, style)(a).at(slot(r))
      if r.p.state == "waiting" then List(b("Sign", Act.Do(Change.Sign(id)), Style.Primary))
      else if !r.p.open then Nil
      else
        (if !r.p.approved then List(b("Approve", Act.Do(Change.Approve(id)), Style.Primary))
         else if r.p.checked then List(b("Uncheck", Act.Do(Change.Check(id, on = false))))
         else List(b("Check", Act.Do(Change.Check(id, on = true))))) ++
          List(b("Edit", Act.Edit(r)), b("Remove", Act.Do(Change.Remove(id)), Style.Danger))
    }
    val proposed = rows.exists(r => r.p.open && !r.p.approved)
    Screen[Act]("Bills to pay",
      List(
        Part.text(s"Today is ${Bill.day(today)} (the demo's clock). The agent pays only what you approve and check, " +
          "in its window, one after another; the contract on each chain bounds every payment whatever the plan " +
          "says. [x] approved, [ ] unchecked, [?] proposed by the agent."),
      ) ++ said.map(Part.info).toList ++ List(
        Part.section("The plan")(plan),
        Part.section("The policies")(chains)),
      rowButtons ++ (if proposed then List(Button("Approve all proposed", Style.Primary)(Act.Do(Change.ApproveAll)))
        else Nil) ++ List(
        Button("Policies and payees")(Act.Policies),
        Button("Address book")(Act.Book),
        Button("The agent: take an invoice in")(Act.Intake),
        Button("The agent: pay what is due")(Act.Do(Change.PayDue)),
        Button("A day later")(Act.Do(Change.Later(1))),
        Button("A week later")(Act.Do(Change.Later(7)))))

  /** a payment changed: its amount, its window, once or monthly */
  def change[R[_]: Has[Ui]: Has[Desk]](r: Row): Option[String] ! R =
    val p = r.p
    val form = Form(Field.decimal("Amount, tUSD8").check("more than zero")(_ > 0).initially(BigDecimal(p.amount)),
      Field.date("From").initially(Day.parse(p.from).toOption.get),
      Field.date("To").initially(Day.parse(p.to).toOption.get),
      Field.choice("Every", "once" -> "once", "month" -> "month").initially(p.every))
      .check("from is not after to")((_, from, to, _) => summon[Ordering[Day]].lteq(from, to))
    edit[(BigDecimal, Day, Day, String), R](s"Change ${p.invoice}: ${p.payee}", form,
      Part.text(p.about)).flatMap {
      case None => pure(None)
      case Some((a, from, to, every)) =>
        Desk.run[R](Change.Edit(p.invoice, plain(a), from.toString, to.toString, every)).map(Some(_))
    }

  /** THE AGENT'S (in the demo): an invoice taken into the plan — the payee by the book's name; an address, if the
   * invoice names one, checked against the book */
  def intake[R[_]: Has[Ui]: Has[Desk]](today: Long): Option[String] ! R =
    Desk.book[R].flatMap { book =>
      val chains = book.flatMap(_.at.map(_._1)).distinct.sorted
      val start = day(today)
      val form = Form(Field.choice("Payee", book.map(e => e.name -> e.name)*),
        Field.decimal("Amount, tUSD8").check("more than zero")(_ > 0),
        Field.text("Invoice").check("an id")(_.nonEmpty),
        Field.choice("Chain", (("the agent chooses" -> "") +: chains.map(c => c -> c))*),
        Field.text("Address on the invoice").optional.hinted("if the invoice names one: checked against the book"),
        Field.text("For").optional,
        Field.date("From").initially(start),
        Field.date("To").initially(day(today + 7 * 86_400L)),
        Field.choice("Every", "once" -> "once", "month" -> "month"))
      edit[(String, BigDecimal, String, String, Option[String], Option[String], Day, Day, String), R](
        "The agent takes an invoice in", form,
        Part.text("The agent judges it — the payee from your address book, never from the invoice — tries it on " +
          "its chain and proposes it to you. Nothing is paid until you approve it.")).flatMap {
        case None => pure(None)
        case Some((payee, a, id, chain, address, about, from, to, every)) =>
          val inv = Invoice(id, payee, address, plain(a), None, about.getOrElse(""), Option(chain).filter(_.nonEmpty))
          Desk.run[R](Change.Intake(inv, from.toString, to.toString, every)).map(Some(_))
      }
    }

  /** each chain's policy and its payees */
  def policies[R[_]: Has[Ui]: Has[Desk]]: Unit ! R =
    Desk.policies[R].flatMap { views =>
      val parts = views.toList.map { v =>
        v.status match
          case None => Part.section(v.chain)(Part.text("no node for this chain"))
          case Some(s) => Part.section(v.chain)(
            Part.details("On the policy" -> Office.money(s.balance),
              "Paid this period" -> s"${Office.money(s.spent)} of ${Office.money(s.periodCap)}",
              "At most a payment" -> Office.money(s.maxPayment),
              "Your signature above" -> Office.money(s.approvalAbove),
              "Paused" -> (if s.paused then "yes" else "no"),
              "Until" -> Bill.day(s.expires)),
            Table(v.payees.toList)(Column.text("Payee")(_._1),
              Column.text("Allowed")(x => if x._3.allowed then "yes" else "no"),
              Column.text("Cap a period")(x => if x._3.allowed then Office.money(x._3.cap) else ""),
              Column.text("Paid this period")(x => if x._3.allowed then Office.money(x._3.spent) else ""),
              Column.text("Next payment from")(x => if x._3.nextAt == 0 then "" else Bill.day(x._3.nextAt)))
              .keyedBy(_._1).whenEmpty("no payee of the book on this chain"))
      }
      show(Screen[Unit]("Policies and payees", parts, List(Button("Back")(()))))
    }

  /** the owner's address book: each payee's address on each chain, allowed there or not */
  def book[R[_]: Has[Ui]: Has[Desk]]: Unit ! R =
    Desk.book[R].flatMap { entries =>
      val rows = entries.toList.flatMap(e => e.at.map((c, a, p) => (e.name, c, a, p)))
      val t = Table(rows)(Column.text("Payee")(_._1), Column.text("Chain")(_._2), Column.text("Address")(_._3),
        Column.text("On the policy")(x => x._4.fold("no node")(p =>
          if p.allowed then s"allowed, ${Office.money(p.cap)} a period" else "not allowed")))
        .keyedBy(x => s"${x._1}-${x._2}")
      show(Screen[Unit]("Address book")(Part.text("The agent pays only these addresses, whatever an invoice " +
        "says; the policy on each chain allows only some of them."), t)(Button("Back")(())))
    }
