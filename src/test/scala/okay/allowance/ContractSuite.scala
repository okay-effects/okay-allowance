package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue


/**
 * THE AGENT'S ALLOWANCE, TESTED (specs/allowance.md §4) on the engines the page runs: the life step by step, each
 * refusal for its own reason; the attacks the life does not show; random payments against a model of the rules.
 */
class ContractSuite extends munit.FunSuite:
  import Life.{usd, invoice, Day, inOrder}
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("the life: each step for its own reason; a request moves no money; nothing to the attacker") {
    Life(Node.compiled).map { w =>
      val want = Map("created" -> None, "internet" -> None, "cloud" -> None, "soon" -> Some("TooSoon"),
        "twice" -> Some("AlreadyPaid"), "injected" -> Some("PayeeNotAllowed"), "request" -> None, "approved" -> None,
        "payeecap" -> Some("PayeeCap"), "software" -> None, "periodcap" -> Some("PeriodCap"),
        "selfallow" -> Some("NotOwner"), "nextmonth" -> None, "paused" -> Some("Paused"))
      assertEquals(w.steps.map(_.key).toSet, want.keySet)
      for s <- w.steps do assertEquals((s.ok, s.reason), (want(s.key).isEmpty, want(s.key)), s.key)
      val at = w.steps.map(s => s.key -> s).toMap
      assertEquals((at("request").balances("electricity"), at("approved").balances("electricity")), (0.0, 140.0))
      assert(w.steps.forall(_.balances("attacker") == 0.0))
    }
  }

  test("the attacks: strangers, the agent's own bounds, requests, the allowance as the total, expiry, a bad period") {
    for
      w <- Life(Node.compiled, stopAfter = "created")
      c = w.chain; p = w.parties; owner = p("owner"); agent = p("agent"); cloud = p("cloud hosting")
      deploy = (period: Long, expires: js.BigInt) => c.deploy(owner, "SpendPolicy", w.token, agent.address,
        js.BigInt(period.toString), usd(400), usd(300), usd(100), expires)
      pol <- deploy(30 * Day, c.now + js.BigInt((365 * Day).toString))
      _ <- c.call(owner, pol, "SpendPolicy", "setPayee", cloud.address, true, usd(1_000), js.BigInt(0))
      _ <- c.call(owner, w.token, "TestUSD", "approve", pol, usd(250))
      pay = (who: Party, to: Party, x: Double, id: String) =>
        c.call(who, pol, "SpendPolicy", "pay", to.address, usd(x), invoice(id))
      results <- inOrder(Vector(
        ("a stranger's key cannot pay", () => pay(p("stranger"), cloud, 10, "A1"), "NotAgent"),
        ("the owner's key is not the agent's door", () => pay(owner, cloud, 10, "A2"), "NotAgent"),
        ("the agent cannot pay itself", () => pay(agent, agent, 10, "A3"), "PayeeNotAllowed"),
        ("a zero payment", () => pay(agent, cloud, 0, "A4"), "ZeroAmount"),
        ("above maxPayment: not even a request", () => pay(agent, cloud, 301, "A5"), "OverMax")) ++
        Vector("setPayee" -> Seq[js.Any](p("attacker").address, true, usd(1e6), js.BigInt(0)),
          "setAgent" -> Seq[js.Any](p("attacker").address),
          "setLimits" -> Seq[js.Any](js.BigInt(1), usd(1e9), usd(1e9), usd(1e9), js.BigInt("1000000000000")),
          "setPaused" -> Seq[js.Any](false), "approve" -> Seq[js.Any](js.BigInt(0)),
          "reject" -> Seq[js.Any](js.BigInt(0))).map { (fn, args) =>
          (s"the agent cannot $fn", () => c.call(agent, pol, "SpendPolicy", fn, args*), "NotOwner")
        })((what, f, reason) => f().map(o => (what, o, reason)))
      _ = for (what, o, reason) <- results do assertEquals((o.ok, o.reason), (false, Some(reason)), what)
      // a request: rejected, then nothing more
      r1 <- pay(agent, cloud, 150, "R1")
      r2 <- c.call(owner, pol, "SpendPolicy", "reject", js.BigInt(0))
      r3 <- c.call(owner, pol, "SpendPolicy", "approve", js.BigInt(0))
      r4 <- c.call(owner, pol, "SpendPolicy", "approve", js.BigInt(7))
      _ = assert(r1.ok && r2.ok, s"$r1 $r2")
      _ = assertEquals((r3.reason, r4.reason), (Some("NoSuchRequest"), Some("NoSuchRequest")))
      // the allowance is the total: 250 approved; 90 + 90 paid; the third fails in the token, nothing moves
      before <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      t <- inOrder(Vector("T1", "T2", "T3"))(id => pay(agent, cloud, 90, id))
      after <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      _ = assertEquals(t.map(_.ok), Vector(true, true, false))
      _ = assertEquals((before.asInstanceOf[js.BigInt] - after.asInstanceOf[js.BigInt]).toString, usd(180).toString)
      // expiry
      short <- deploy(30 * Day, c.now + js.BigInt(10))
      _ <- c.call(owner, short, "SpendPolicy", "setPayee", cloud.address, true, usd(1_000), js.BigInt(0))
      _ = c.advance(60)
      e <- c.call(agent, short, "SpendPolicy", "pay", cloud.address, usd(10), invoice("E1"))
      _ = assertEquals(e.reason, Some("Expired"))
      bad <- deploy(0, c.now + js.BigInt(10)).map(_ => "").recover { case x => x.getMessage }
    yield assert(bad.contains("BadPeriod"), bad)
  }

  test("random payments: the contract agrees with a model of the rules, 80 of 80") {
    var seed = 11L
    def rnd(): Double = { seed = (seed * 1103515245 + 12345) % (1L << 31); seed.toDouble / (1L << 31) }
    for
      w <- Life(Node.compiled, stopAfter = "created")
      c = w.chain; p = w.parties; owner = p("owner"); agent = p("agent")
      pol <- c.deploy(owner, "SpendPolicy", w.token, agent.address, js.BigInt((30 * Day).toString), usd(300),
        usd(200), usd(1_000), c.now + js.BigInt((365 * Day).toString))
      payees = Vector((p("internet provider"), 80.0, 5L), (p("cloud hosting"), 150.0, 0L), (p("attacker"), 0.0, 0L))
      _ <- inOrder(payees.filter(_._2 > 0))((who, cap, gap) =>
        c.call(owner, pol, "SpendPolicy", "setPayee", who.address, true, usd(cap), js.BigInt((gap * Day).toString)))
      _ <- c.call(owner, w.token, "TestUSD", "approve", pol, usd(1e6))
      start = c.now.toString.toLong
      agreed <- {
        final case class Spent(spent: Double, period: Long, last: Long)
        var total = Spent(0, -1, 0); var paid = Set.empty[String]; var per = Map.empty[String, Spent]
        inOrder(0 until 80) { i =>
          c.advance(math.floor(rnd() * 6 * Day).toLong)
          val (who, cap, gap) = payees((rnd() * payees.size).toInt)
          val amount = math.round((1 + rnd() * 120) * 100) / 100.0
          val id = if rnd() < 0.15 && paid.nonEmpty then paid.toVector((rnd() * paid.size).toInt) else s"R-$i"
          val now = c.now.toString.toLong; val period = (now - start) / (30 * Day)
          val mine = per.getOrElse(who.address, Spent(0, -1, 0))
          val sum = (if total.period == period then total.spent else 0) + amount
          val own = (if mine.period == period then mine.spent else 0) + amount
          val model = cap > 0 && amount <= 200 && !paid(id) && (mine.last == 0 || now >= mine.last + gap * Day) &&
            own <= cap + 1e-9 && sum <= 300 + 1e-9
          c.call(agent, pol, "SpendPolicy", "pay", who.address, usd(amount), invoice(id)).map { o =>
            if o.ok then { total = Spent(sum, period, 0); paid += id; per += who.address -> Spent(own, period, now) }
            if o.ok != model then println(s"  disagree $i ${who.name} $amount $id model=$model ${o.reason}")
            o.ok == model
          }
        }
      }
    yield assertEquals(agreed.count(identity), 80)
  }
