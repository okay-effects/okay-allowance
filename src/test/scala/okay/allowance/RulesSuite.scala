package okay.allowance

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scalus.uplc.builtin.ByteString
import okay.allowance.cardano.CardanoWorld

/** ONE SET OF RULES, TWO CHAINS (specs/allowance.md §11): the same random payments on the EVM contract, on the
 * Cardano validator and through the Scala model of the rules — the three decide alike, reason for reason. (Its first
 * run found Cardano forgetting old invoices and paying them again; they are kept in an MPF root since, §9.) */
class RulesSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  test("120 random payments: the EVM contract, the Cardano script and the model agree on each, reason too") {
    var seed = 7L
    def rnd(): Double = { seed = (seed * 1103515245 + 12345) % (1L << 31); seed.toDouble / (1L << 31) }
    // no approvals (a request moves nothing on either), ten years (no expiry)
    val rules = Rules(periodDays = 30, periodCap = 300, maxPayment = 200, approvalAbove = 200, days = 3650,
      payees = Vector(PayeeRule("internet provider", 80, 5), PayeeRule("cloud hosting", 150, 0)))
    val names = Vector("internet provider", "cloud hosting", "attacker")
    for
      w <- Life(Files.compiled, stopAfter = "created", rules)
      ada = CardanoWorld(rules)
      _ = assert(ada.create().isRight)
      start = w.chain.now.toString.toLong
      _ = assertEquals(ada.now / 1000, start)
      evmModel = Judge(rules, start, Rules.units(1_000))
      adaModel = Judge(rules, start, Rules.units(1_000))
      results <- {
        var paid = Vector.empty[String]
        Life.inOrder(0 until 120) { i =>
          val dt = math.floor(rnd() * 4 * 86_400).toLong
          w.chain.advance(dt); ada.advance(dt)
          val who = names((rnd() * names.size).toInt)
          val amount = math.round((1 + rnd() * 110) * 100) / 100.0
          val id = if rnd() < 0.2 && paid.nonEmpty then paid((rnd() * paid.size).toInt) else s"R-$i"
          val now = w.chain.now.toString.toLong
          w.chain.call(w.parties("agent"), w.policy, "SpendPolicy", "pay", w.parties(who).address, Life.usd(amount),
            Life.invoice(id)).map { o =>
            val onAda = ada.pay(ada.parties(who), Rules.units(amount),
              ByteString.fromHex(Life.invoice(id).stripPrefix("0x"))).left.toOption
            if o.ok || onAda.isEmpty then paid :+= id
            (i, who, amount, id, o.reason, evmModel.pay(who, Rules.units(amount), id, now), onAda,
              adaModel.pay(who, Rules.units(amount), id, now))
          }
        }
      }
    yield
      val evmOff = results.filter(r => r._5 != r._6)
      val adaOff = results.filter(r => r._7 != r._8)
      for r <- evmOff ++ adaOff do println(s"  disagree $r")
      assertEquals((evmOff.size, adaOff.size), (0, 0))
      assertEquals(results.filter(r => r._5 != r._7), Vector.empty, "the chains apart")
      val reasons = results.flatMap(_._5).groupBy(identity).view.mapValues(_.size).toMap
      println(s"  paid ${results.count(_._5.isEmpty)}, refused $reasons")
      assert(Set("PayeeNotAllowed", "AlreadyPaid", "PayeeCap", "TooSoon", "InsufficientBalance")
        .subsetOf(reasons.keySet), reasons.toString)
  }
