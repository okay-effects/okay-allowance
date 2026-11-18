package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

/** a step of the life: its key, accepted or refused (the contract's own error), the gas, every party's balance */
final case class LifeStep(key: String, ok: Boolean, reason: Option[String], gas: Long, balances: Map[String, Double])

/** the life's world: its chain, the policy, the token, its parties by name, the steps */
final case class World(chain: Chain, policy: String, token: String, parties: Map[String, Party],
                       steps: Vector[LifeStep], compiler: String)

/**
 * THE AGENT'S ALLOWANCE, A LIFE (specs/allowance.md §4): the owner's policy, the agent paying bills, the owner
 * approving a large one, refusals each for its own reason — on the chain in memory, signed transactions from demo keys.
 */
object Life:
  val Day = 86_400L
  /** dollars in tUSD8's 8 decimals */
  def usd(x: Double): js.BigInt = js.BigInt(math.round(x * 100).toString) * js.BigInt("1000000")
  def invoice(id: String): String = Npm.viem.keccak256(Npm.viem.toHex(id)).asInstanceOf[String]
  /** one after another: a chain's transactions are ordered (a sender's nonce), never run side by side */
  def inOrder[A, B](xs: Seq[A])(f: A => Future[B])(using ExecutionContext): Future[Vector[B]] =
    xs.foldLeft(Future.successful(Vector.empty[B]))((acc, x) => acc.flatMap(v => f(x).map(v :+ _)))
  val names = Vector("owner", "agent", "stranger", "internet provider", "cloud hosting", "electricity",
    "software licence", "attacker", "gym")

  /** `stopAfter = "created"`: the policy set up, no bill paid (the agent's demo plays its own) */
  def apply(compiled: js.Dynamic, stopAfter: String = "")(using ExecutionContext): Future[World] =
    for
      c <- Chain(compiled)
      parties <- inOrder(names)(c.party).map(ps => names.zip(ps).toMap)
      w <- run(c, parties, compiled, stopAfter)
    yield w

  private def run(c: Chain, p: Map[String, Party], compiled: js.Dynamic, stopAfter: String)(using ExecutionContext)
      : Future[World] =
    val owner = p("owner"); val agent = p("agent")
    var steps = Vector.empty[LifeStep]
    var policy = ""
    var token = ""
    def balances: Future[Map[String, Double]] =
      inOrder(Vector("owner", "internet provider", "cloud hosting", "electricity", "software licence",
        "attacker"))(n => c.view(token, "TestUSD", "balanceOf", p(n).address)
          .map(b => n -> b.toString().asInstanceOf[String].toDouble / 1e8)).map(_.toMap)
    def step(key: String, r: Future[Outcome]): Future[Unit] =
      for o <- r; b <- balances yield steps :+= LifeStep(key, o.ok, o.reason, o.gas, b)
    def pay(payee: String, x: Double, id: String) =
      c.call(agent, policy, "SpendPolicy", "pay", p(payee).address, usd(x), invoice(id))
    def world = World(c, policy, token, p, steps, compiled.compiler.asInstanceOf[String])
    for
      t <- c.deploy(owner, "TestUSD", "Test USD (8 decimals)", "tUSD8", 8)
      _ = token = t
      _ <- c.call(owner, token, "TestUSD", "mint", owner.address, usd(5_000))
      // 1. the owner's policy: the agent's key; a 30-day period, at most 400 in it, at most 300 a payment, above 100
      //    the owner approves, for a year; the payees with their caps and gaps; an allowance of 1 000 in all
      pol <- c.deploy(owner, "SpendPolicy", token, agent.address, js.BigInt((30 * Day).toString), usd(400), usd(300),
        usd(100), c.now + js.BigInt((365 * Day).toString))
      _ = policy = pol
      _ <- inOrder(Vector(("internet provider", 60.0, 25L), ("cloud hosting", 200.0, 0L),
        ("electricity", 150.0, 25L), ("software licence", 100.0, 0L))) { (n, cap, gap) =>
        c.call(owner, policy, "SpendPolicy", "setPayee", p(n).address, true, usd(cap), js.BigInt((gap * Day).toString))
      }.flatMap(_ => step("created", c.call(owner, token, "TestUSD", "approve", policy, usd(1_000))))
      rest <- if stopAfter == "created" then Future.successful(world) else
        for
          _ <- step("internet", pay("internet provider", 49.99, "INT-2026-11"))
          _ <- step("cloud", pay("cloud hosting", 85, "CLOUD-2026-11"))
          // a second internet bill the same week: the payee's gap is 25 days
          _ <- step("soon", pay("internet provider", 49.99, "INT-2026-11-B"))
          _ <- step("twice", pay("cloud hosting", 85, "CLOUD-2026-11"))
          // an invoice slipped into the agent's mail asks to pay an unknown address
          _ <- step("injected", pay("attacker", 50, "URGENT-9921"))
          // above 100: a request for the owner, who approves it
          _ <- step("request", pay("electricity", 140, "POWER-2026-11"))
          _ <- step("approved", c.call(owner, policy, "SpendPolicy", "approve", js.BigInt(0)))
          // the cloud's cap is 200 a period (85 already)
          _ <- step("payeecap", pay("cloud hosting", 150, "CLOUD-2026-11-EXTRA"))
          _ <- step("software", pay("software licence", 90, "SW-2026-11"))
          _ <- step("periodcap", pay("cloud hosting", 50, "CLOUD-2026-11-B")) // 364.99 + 50 > 400
          // the agent tries to allow the attacker itself
          _ <- step("selfallow", c.call(agent, policy, "SpendPolicy", "setPayee", p("attacker").address, true,
            usd(1e6), js.BigInt(0)))
          // a month later the period's count starts again
          _ = c.advance(31 * Day)
          _ <- step("nextmonth", pay("internet provider", 49.99, "INT-2026-12"))
          _ <- c.call(owner, policy, "SpendPolicy", "setPaused", true)
          _ <- step("paused", pay("cloud hosting", 20, "CLOUD-2026-12"))
        yield world
    yield rest
