package okay.allowance

import scala.concurrent.Future
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
    Life(Files.compiled).map { w =>
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

  test("the attacks: strangers, the agent's bounds, requests, the balance as the total, withdraw, close, expiry") {
    for
      w <- Life(Files.compiled, stopAfter = "created")
      c = w.chain; p = w.parties; owner = p("owner"); agent = p("agent"); cloud = p("cloud hosting")
      deploy = (period: Long, expires: js.BigInt) => c.deploy(owner, "SpendPolicy", w.token, agent.address,
        js.BigInt(period.toString), usd(400), usd(300), usd(100), expires)
      pol <- deploy(30 * Day, c.now + js.BigInt((365 * Day).toString))
      _ <- c.call(owner, pol, "SpendPolicy", "setPayee", cloud.address, true, usd(1_000), js.BigInt(0))
      _ <- c.call(owner, w.token, "TestUSD", "approve", pol, usd(250))
      d <- c.call(owner, pol, "SpendPolicy", "deposit", usd(250))
      _ = assert(d.ok, d.toString)
      pay =(who: Party, to: Party, x: Double, id: String) =>
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
      // the balance is the total: 250 deposited; 90 + 90 paid; the third refused with the numbers, nothing moves;
      // the owner's wallet is never touched by a payment
      wallet <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      before <- c.view(pol, "SpendPolicy", "balance")
      t <- inOrder(Vector("T1", "T2", "T3"))(id => pay(agent, cloud, 90, id))
      after <- c.view(pol, "SpendPolicy", "balance")
      walletAfter <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      _ = assertEquals(t.map(_.ok), Vector(true, true, false))
      _ = assertEquals(t(2).reason, Some("InsufficientBalance"))
      _ = assertEquals(t(2).args.map(_.toString), Vector(usd(70).toString, usd(90).toString))
      _ = assertEquals((before.asInstanceOf[js.BigInt] - after.asInstanceOf[js.BigInt]).toString, usd(180).toString)
      _ = assertEquals(walletAfter.toString, wallet.toString)
      // a top-up from anyone; the agent cannot take the money back; the owner can, and closing stops the agent
      _ <- c.call(owner, w.token, "TestUSD", "transfer", p("stranger").address, usd(20))
      _ <- c.call(p("stranger"), w.token, "TestUSD", "approve", pol, usd(20))
      top <- c.call(p("stranger"), pol, "SpendPolicy", "deposit", usd(20))
      tw <- c.call(agent, pol, "SpendPolicy", "withdraw", usd(1), agent.address)
      tc <- c.call(agent, pol, "SpendPolicy", "close", agent.address)
      _ = assert(top.ok, top.toString)
      _ = assertEquals((tw.reason, tc.reason), (Some("NotOwner"), Some("NotOwner")))
      wd <- c.call(owner, pol, "SpendPolicy", "withdraw", usd(10), owner.address)
      cl <- c.call(owner, pol, "SpendPolicy", "close", owner.address)
      left <- c.view(pol, "SpendPolicy", "balance")
      gone <- c.view(w.token, "TestUSD", "balanceOf", owner.address)
      late <- pay(agent, cloud, 1, "T4")
      _ = assert(wd.ok && cl.ok, s"$wd $cl")
      _ = assertEquals(left.toString, "0")
      _ = assertEquals((gone.asInstanceOf[js.BigInt] - walletAfter.asInstanceOf[js.BigInt]).toString,
        usd(70).toString) // -20 to the stranger, +10 withdrawn, +80 on close
      _ = assert(late.reason.contains("Paused") || late.reason.contains("NotAgent"), late.toString)
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
      w <- Life(Files.compiled, stopAfter = "created")
      c = w.chain; p = w.parties; owner = p("owner"); agent = p("agent")
      pol <- c.deploy(owner, "SpendPolicy", w.token, agent.address, js.BigInt((30 * Day).toString), usd(300),
        usd(200), usd(1_000), c.now + js.BigInt((365 * Day).toString))
      payees = Vector((p("internet provider"), 80.0, 5L), (p("cloud hosting"), 150.0, 0L), (p("attacker"), 0.0, 0L))
      _ <- inOrder(payees.filter(_._2 > 0))((who, cap, gap) =>
        c.call(owner, pol, "SpendPolicy", "setPayee", who.address, true, usd(cap), js.BigInt((gap * Day).toString)))
      _ <- c.call(owner, w.token, "TestUSD", "approve", pol, usd(3_000))
      _ <- c.call(owner, pol, "SpendPolicy", "deposit", usd(3_000))
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

  test("AUDIT E1: a token returning nothing (as USDT on Ethereum): deposit, pay, withdraw, close all work") {
    for
      w <- Life(Files.compiled, stopAfter = "created")
      c = w.chain; p = w.parties; owner = p("owner"); agent = p("agent"); cloud = p("cloud hosting")
      t <- c.deploy(owner, "NoBoolUSD")
      _ <- c.call(owner, t, "NoBoolUSD", "mint", owner.address, usd(1_000))
      pol <- c.deploy(owner, "SpendPolicy", t, agent.address, js.BigInt((30 * Day).toString), usd(400), usd(300),
        usd(100), c.now + js.BigInt((365 * Day).toString))
      _ <- c.call(owner, pol, "SpendPolicy", "setPayee", cloud.address, true, usd(200), js.BigInt(0))
      _ <- c.call(owner, t, "NoBoolUSD", "approve", pol, usd(500))
      d <- c.call(owner, pol, "SpendPolicy", "deposit", usd(500))
      pd <- c.call(agent, pol, "SpendPolicy", "pay", cloud.address, usd(90), invoice("NB-1"))
      wd <- c.call(owner, pol, "SpendPolicy", "withdraw", usd(10), owner.address)
      cl <- c.call(owner, pol, "SpendPolicy", "close", owner.address)
      got <- c.view(t, "NoBoolUSD", "balanceOf", cloud.address)
      left <- c.view(t, "NoBoolUSD", "balanceOf", pol)
    yield
      assertEquals(Vector(d, pd, wd, cl).map(o => (o.ok, o.reason)), Vector.fill(4)((true, None)))
      assertEquals((got.toString, left.toString), (usd(90).toString, "0"))
  }

  test("AUDIT E1: a policy on an address with no token behind it is refused at its birth") {
    for
      w <- Life(Files.compiled, stopAfter = "created")
      c = w.chain; owner = w.parties("owner")
      bad <- c.deploy(owner, "SpendPolicy", owner.address, w.parties("agent").address, js.BigInt((30 * Day).toString),
        usd(400), usd(300), usd(100), c.now + js.BigInt((365 * Day).toString)).map(_ => "").recover { case e =>
          e.getMessage }
    yield assert(bad.contains("NotAToken"), bad)
  }

  test("AUDIT invariants: 150 random steps — the agent, an attacker, strangers topping up, the clock — and after each") {
    var seed = 23L
    def rnd(): Double = { seed = (seed * 1103515245 + 12345) % (1L << 31); seed.toDouble / (1L << 31) }
    def pick[A](xs: Vector[A]): A = xs((rnd() * xs.size).toInt)
    val allowed = Rules.demo.payees.map(p => p.name -> Rules.units(p.cap)).toMap
    for
      w <- Life(Files.compiled, stopAfter = "created")
      c = w.chain; p = w.parties
      start = c.now.toString.toLong
      period = 30 * Day
      bal = (n: String) => c.view(w.token, "TestUSD", "balanceOf", p(n).address).map(x => BigInt(x.toString))
      polBal = () => c.view(w.token, "TestUSD", "balanceOf", w.policy).map(x => BigInt(x.toString))
      watched = Vector("attacker", "agent", "gym", "owner", "stranger")
      before <- Life.inOrder(watched)(n => bal(n).map(n -> _)).map(_.toMap)
      problems <- {
        var expected = Rules.units(1_000) // on the policy
        var others = before // what the watched may hold: only what they put in or took out themselves
        var paid = Vector.empty[(String, BigInt, Long, String)] // payee, amount, period, invoice
        val found = scala.collection.mutable.ArrayBuffer.empty[String]
        Life.inOrder(0 until 150) { i =>
          val r = rnd()
          val step: Future[Unit] =
            if r < 0.15 then Future.successful(c.advance((rnd() * 5 * Day).toLong))
            else if r < 0.25 then // a stranger tops the policy up
              val x = Rules.units(math.round(rnd() * 50 + 1).toDouble)
              for
                _ <- c.call(p("stranger"), w.token, "TestUSD", "mint", p("stranger").address, js.BigInt(x.toString))
                _ <- c.call(p("stranger"), w.token, "TestUSD", "approve", w.policy, js.BigInt(x.toString))
                o <- c.call(p("stranger"), w.policy, "SpendPolicy", "deposit", js.BigInt(x.toString))
              yield if o.ok then expected += x
            else if r < 0.40 then // the attacker tries the owner's and the agent's doors
              val tries = Vector[() => Future[Outcome]](
                () => c.call(p("attacker"), w.policy, "SpendPolicy", "pay", p("attacker").address, usd(10),
                  invoice(s"X-$i")),
                () => c.call(p("attacker"), w.policy, "SpendPolicy", "withdraw", usd(10), p("attacker").address),
                () => c.call(p("attacker"), w.policy, "SpendPolicy", "setPayee", p("attacker").address, true, usd(1e6),
                  js.BigInt(0)),
                () => c.call(p("attacker"), w.policy, "SpendPolicy", "setAgent", p("attacker").address),
                () => c.call(p("attacker"), w.policy, "SpendPolicy", "close", p("attacker").address))
              pick(tries)().map(o => if o.ok then found += s"step $i: the attacker got through")
            else // the agent pays someone: allowed or not, any amount, an invoice new or reused
              val who = pick(Vector("internet provider", "cloud hosting", "electricity", "software licence",
                "attacker", "agent", "gym", "stranger"))
              // most of them ordinary bills; some above the threshold (requests) or the most a payment
              val x = Rules.units(math.round((if rnd() < 0.8 then 1 + rnd() * 99 else 100 + rnd() * 220) * 100) / 100.0)
              val id = if rnd() < 0.2 && paid.nonEmpty then pick(paid)._4 else s"R-$i"
              c.call(p("agent"), w.policy, "SpendPolicy", "pay", p(who).address, js.BigInt(x.toString), invoice(id))
                .map { o =>
                  if o.ok && o.events.exists(_.name == "Paid") then
                    expected -= x; paid :+= ((who, x, (c.now.toString.toLong - start) / period, id))
                }
          for
            _ <- step
            pb <- polBal()
            now <- Life.inOrder(watched)(n => bal(n).map(n -> _)).map(_.toMap)
          yield
            // the stranger's own mints are its own: what it holds may only fall by its deposits
            if pb != expected then found += s"step $i: the policy holds $pb, expected $expected"
            for n <- watched if n != "stranger" && now(n) > others(n) do found += s"step $i: $n got ${now(n) - others(n)}"
            others = now
            if paid.exists((who, _, _, _) => !allowed.contains(who)) then found += s"step $i: paid a payee not allowed"
            if paid.exists(_._2 > Rules.units(300)) then found += s"step $i: a payment above the most"
            if paid.map(_._4).distinct.size != paid.size then found += s"step $i: an invoice paid twice"
            for (per, ps) <- paid.groupBy(_._3) do
              if ps.map(_._2).sum > Rules.units(400) then found += s"step $i: period $per above its cap"
              for (who, qs) <- ps.groupBy(_._1) if qs.map(_._2).sum > allowed.getOrElse(who, BigInt(0)) do
                found += s"step $i: $who above its cap in period $per"
        }.map(_ => (found.toVector, paid.size))
      }
    yield
      val (found, payments) = problems
      assertEquals(found, Vector.empty)
      assert(payments >= 10, s"only $payments payments: the walk did not exercise paying")
  }

  test("AUDIT E3 (known, by design): fixed periods — a full cap before a boundary and another right after it") {
    for
      w <- Life(Files.compiled, stopAfter = "created")
      c = w.chain; p = w.parties; owner = p("owner"); agent = p("agent"); cloud = p("cloud hosting")
      pol <- c.deploy(owner, "SpendPolicy", w.token, agent.address, js.BigInt((30 * Day).toString), usd(400), usd(400),
        usd(400), c.now + js.BigInt((365 * Day).toString))
      _ <- c.call(owner, pol, "SpendPolicy", "setPayee", cloud.address, true, usd(1_000), js.BigInt(0))
      _ <- c.call(owner, w.token, "TestUSD", "approve", pol, usd(1_000))
      _ <- c.call(owner, pol, "SpendPolicy", "deposit", usd(1_000))
      _ = c.advance(30 * Day - 60)
      a <- c.call(agent, pol, "SpendPolicy", "pay", cloud.address, usd(400), invoice("B-1"))
      _ = c.advance(120)
      b <- c.call(agent, pol, "SpendPolicy", "pay", cloud.address, usd(400), invoice("B-2"))
    // two minutes, twice the period's cap: the owner bounds it with a payee's gap, or a smaller cap
    yield assertEquals((a.ok, b.ok), (true, true))
  }
