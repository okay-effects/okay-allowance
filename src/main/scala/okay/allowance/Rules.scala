package okay.allowance

/** a payee the owner allows: its name (the demo's party), the most it may get a period, the least days between two
 * payments to it */
final case class PayeeRule(name: String, cap: Double, gapDays: Long)

/**
 * THE OWNER'S RULES, ONCE (specs/allowance.md §6, §11): the period's length, the most a period, a payment, the owner's
 * approval above, how long the policy lives, the payees — in dollars and days. Each chain's policy is set up from
 * them (Life on the EVM, CardanoWorld on Cardano).
 */
final case class Rules(periodDays: Long = 30, periodCap: Double = 400, maxPayment: Double = 300,
                       approvalAbove: Double = 100, days: Long = 365, payees: Vector[PayeeRule] = Rules.lifePayees)

object Rules:
  val lifePayees = Vector(PayeeRule("internet provider", 60, 25), PayeeRule("cloud hosting", 200, 0),
    PayeeRule("electricity", 150, 25), PayeeRule("software licence", 100, 0))
  /** the demo's: 30 days, 400 a period, 300 a payment, the owner above 100, a year */
  val demo: Rules = Rules()
  /** dollars in the token's 8 decimals */
  def units(x: Double): BigInt = BigInt(math.round(x * 100)) * 1_000_000

/**
 * THE RULES AS A MODEL, judged in Scala: the decision a policy gives a payment of `amount` (8 decimals) to `payee`
 * for `invoice` at `now` (seconds), in SpendPolicy.sol's order of checks — `None` paid, else the reason. The approval
 * threshold aside (a request moves nothing). `memory`: how many periods a paid invoice is remembered — forever on the
 * EVM (`None`), this period and the one before on Cardano (`Some(2)`, §9).
 */
final class Judge(rules: Rules, start: Long, var balance: BigInt, memory: Option[Int] = None):
  private final case class Mine(spent: BigInt, period: Long, last: Long)
  private var spent = BigInt(0); private var period = -1L
  private var mine = Map.empty[String, Mine]
  private var paid = Map.empty[String, Long]
  private val day = 86_400L

  def judge(payee: String, amount: BigInt, invoice: String, now: Long): Option[String] =
    val w = (now - start) / (rules.periodDays * day)
    val p = rules.payees.find(_.name == payee)
    val m = mine.getOrElse(payee, Mine(0, -1, 0))
    val own = (if m.period == w then m.spent else BigInt(0)) + amount
    val total = (if period == w then spent else BigInt(0)) + amount
    if now >= start + rules.days * day then Some("Expired")
    else if amount <= 0 then Some("ZeroAmount")
    else if p.isEmpty then Some("PayeeNotAllowed")
    else if amount > Rules.units(rules.maxPayment) then Some("OverMax")
    else if paid.get(invoice).exists(pw => memory.forall(n => w - pw < n)) then Some("AlreadyPaid")
    else if m.last != 0 && now < m.last + p.get.gapDays * day then Some("TooSoon")
    else if own > Rules.units(p.get.cap) then Some("PayeeCap")
    else if total > Rules.units(rules.periodCap) then Some("PeriodCap")
    else if amount > balance then Some("InsufficientBalance")
    else None

  /** judged, and when paid, the books kept */
  def pay(payee: String, amount: BigInt, invoice: String, now: Long): Option[String] =
    val r = judge(payee, amount, invoice, now)
    if r.isEmpty then
      val w = (now - start) / (rules.periodDays * day)
      val m = mine.getOrElse(payee, Mine(0, -1, 0))
      mine += payee -> Mine((if m.period == w then m.spent else BigInt(0)) + amount, w, now)
      spent = (if period == w then spent else BigInt(0)) + amount; period = w
      paid += invoice -> w
      balance -= amount
    r
