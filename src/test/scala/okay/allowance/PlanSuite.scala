package okay.allowance

/** THE PLAN'S OWN RULES (specs/allowance.md §13), as data: proposals wait for approval, a checkbox, edits, removal,
 * the due list in deadline order, monthly payments rolling on */
class PlanSuite extends munit.FunSuite:
  private def day(d: String) = Bill.seconds(d)
  private def item(id: String, from: String, to: String, every: String = "once", approved: Boolean = true) =
    Planned(id, id, "Internet provider", "10", Some("ethereum"), "", from, to, every, approved, checked = true, "open")

  test("a month on: the same day, or the month's last") {
    assertEquals(Vector("2026-01-31", "2026-12-15", "2028-01-30").map(Plan.plusMonth),
      Vector("2026-02-28", "2027-01-15", "2028-02-29"))
  }

  test("due: approved, checked, open, in its window — the earliest deadline first") {
    val plan = Plan(Vector(item("A", "2026-01-01", "2026-01-20"), item("B", "2026-01-01", "2026-01-05"),
      item("C", "2026-01-01", "2026-01-10", approved = false), item("D", "2026-01-03", "2026-01-09")))
      .check("D", on = false).toOption.get
    assertEquals(plan.due(day("2026-01-02")).map(_.invoice), Vector("B", "A"))
    assertEquals(plan.due(day("2026-01-02") + 86_399).map(_.invoice), Vector("B", "A"))
    assertEquals(plan.approve(Seq("C")).toOption.get.due(day("2026-01-04")).map(_.invoice), Vector("B", "C", "A"))
  }

  test("the owner's changes: edit, uncheck, remove; nothing changes a payment already made") {
    val plan = Plan(Vector(item("A", "2026-01-01", "2026-01-20")))
    val edited = plan.edit("A", Some("12.5"), None, Some("2026-01-25"), Some("month")).toOption.get
    assertEquals(edited.find("A").map(p => (p.amount, p.to, p.every)), Some(("12.5", "2026-01-25", "month")))
    assert(plan.edit("A", Some("-1"), None, None, None).isLeft)
    assert(plan.edit("A", None, Some("2026-02-01"), None, None).isLeft) // from after to
    val paid = plan.record(plan.items.head.copy(state = "paid"))
    assert(paid.remove("A").isLeft && paid.check("A", on = false).isLeft)
    assertEquals(plan.remove("A").map(_.items), Right(Vector.empty))
  }

  test("monthly: paid brings next month's; unchecked, its window ended, skipped and the next comes") {
    val plan = Plan(Vector(item("INT/2026-01", "2026-01-01", "2026-01-10", "month").copy(series = "INT")))
    val paid = plan.record(plan.items.head.copy(state = "paid"))
    assertEquals(paid.items.map(p => (p.invoice, p.from, p.to, p.state)), Vector(
      ("INT/2026-01", "2026-01-01", "2026-01-10", "paid"), ("INT/2026-02", "2026-02-01", "2026-02-10", "open")))
    val rolled = plan.check("INT/2026-01", on = false).toOption.get.roll(day("2026-01-11"))
    assertEquals(rolled.items.map(p => (p.invoice, p.state, p.checked)), Vector(
      ("INT/2026-01", "skipped", false), ("INT/2026-02", "open", true)))
    // rolled again the same day: nothing new
    assertEquals(rolled.roll(day("2026-01-11")), rolled)
  }

  test("the plan in a file, as the agent's and the owner's servers share it: the same plan read back") {
    val path = s"target/plan-test-${System.nanoTime}.json"
    val seeded = Plan.seed(Bill.parse(Files.read("demo/bills.json")))
    val store = FilePlan(path, seeded)
    assertEquals(store.load, seeded) // no file yet: the seed
    val changed = seeded.check("SW-2026-01", on = false).toOption.get
      .record(seeded.items.head.copy(state = "paid", tx = "0xabc", note = "paid 85 tUSD8", request = 3))
    store.save(changed)
    assertEquals(FilePlan(path, Plan.empty).load, changed)
  }
