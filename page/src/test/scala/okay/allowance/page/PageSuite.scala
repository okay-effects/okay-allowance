package okay.allowance.page

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import okay.allowance.{Agent, Bill, Plan}

/** THE OWNER'S PAGE (specs/allowance.md §14), below the browser: the demo's desk through a review — proposed,
 * approved, edited, unchecked, removed, paid in turn, signed on a chain — and the plan's screen giving each payment
 * the buttons its state allows */
class PageSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private def read(path: String) = NodeFs.fs.readFileSync(path, "utf8").asInstanceOf[String]

  private def desk = Agent.demo(js.JSON.parse(read("out/contracts.json")), Vector.empty)
    .map(d => DemoDesk(d, Plan.seed(Bill.parse(read("demo/bills.json")))))

  test("a review on the desk: the agent proposes, the owner decides, the agent pays, the owner signs") {
    for
      k <- desk
      said <- k.answer(Change.Intake(okay.allowance.Invoice("SW-EXTRA", "Software licence", None, "20", None,
        "one more seat"), "2026-01-01", "2026-01-31", "once"))
      _ = assert(said.contains("SW-EXTRA proposed by the agent"), said)
      paid <- k.answer(Change.PayDue)
      _ = assert(paid.contains("INT/2026-01: paid 49.99") && paid.contains("POWER/2026-01: 140 tUSD8"), paid)
      _ = assert(!paid.contains("SW-EXTRA"), paid)
      e <- k.answer(Change.Edit("SW-EXTRA", "25", "2026-01-01", "2026-01-31", "once"))
      a <- k.answer(Change.Approve("SW-EXTRA"))
      u <- k.answer(Change.Check("SW-2026-01", on = false))
      bad <- k.answer(Change.Approve("NO-SUCH"))
      _ = assertEquals((e, a, u), ("SW-EXTRA changed", "SW-EXTRA approved", "SW-2026-01 unchecked: not paid this time"))
      _ = assert(bad.startsWith("Not done"), bad)
      s <- k.answer(Change.Sign("POWER/2026-01"))
      _ = assertEquals(s, "POWER/2026-01 signed on ethereum and paid")
      _ <- k.answer(Change.Later(4))
      p2 <- k.answer(Change.PayDue)
      _ = assertEquals(p2, "SW-EXTRA: paid 25 tUSD8 to Software licence on ethereum")
      rows <- k.office.plan
    yield
      val st = rows.map(r => r.p.invoice -> r.label).toMap
      assertEquals((st("POWER/2026-01"), st("SW-EXTRA"), st("SW-2026-01"), st("POWER/2026-02")),
        ("paid", "paid", "unchecked", "upcoming"))
  }

  test("the plan's screen: each payment the buttons its state allows, at its own row") {
    for
      k <- desk
      _ <- k.answer(Change.Intake(okay.allowance.Invoice("GYM-1", "Gym", None, "30", None, ""), "2026-01-01",
        "2026-01-08", "once"))
      _ <- k.answer(Change.PayDue)
      _ <- k.answer(Change.Check("SW-2026-01", on = false))
      rows <- k.office.plan
      views <- k.office.policies
    yield
      val screen = OwnerPage.planScreen(rows, views, k.office.today(), Some("done"))
      def at(id: String) = screen.buttons.filter(_.slot.contains(s"row-$id")).map(_.label)
      assertEquals(at("GYM-1"), List("Approve", "Edit", "Remove"))
      assertEquals(at("POWER/2026-01"), List("Sign"))
      assertEquals(at("SW-2026-01"), List("Check", "Edit", "Remove"))
      assertEquals(at("POWER-2026-01-ADA"), Nil) // paid
      assertEquals(at("CLOUD/2026-01"), List("Uncheck", "Edit", "Remove"))
      assert(screen.buttons.exists(_.label == "Approve all proposed"))
  }

/** node's files, for the test (the page never reads them) */
object NodeFs:
  @js.native @JSImport("node:fs", JSImport.Namespace) private object Fs extends js.Object
  def fs: js.Dynamic = Fs.asInstanceOf[js.Dynamic]
