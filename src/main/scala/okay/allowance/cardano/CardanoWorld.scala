package okay.allowance.cardano

import scala.concurrent.Future
import scala.scalajs.js
import scalus.cardano.address.{Address, ShelleyAddress, ShelleyDelegationPart, ShelleyPaymentPart}
import scalus.cardano.ledger.*
import scalus.cardano.node.Emulator
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{TxId, TxOutRef}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.cardano.txbuilder.TransactionSigner
import scalus.cardano.wallet.hd.HdAccount
import scalus.crypto.ed25519.given
import scalus.uplc.builtin.{ByteString, Data}
import okay.allowance.{Event, Node, Outcome}

/**
 * THE POLICY ON A CARDANO LEDGER IN MEMORY (specs/allowance.md §9): Scalus's ledger emulator — real transactions,
 * the script run by the ledger's rules, in node and in a page — with demo parties from a public mnemonic (never a
 * real wallet): the owner, the agent, the payees, an attacker. The owner's policy as on the EVM chains: a 30-day
 * window, 400 in it, 300 a payment, the owner's signature above 100, a year; the payees with their caps and gaps;
 * 1 000 tUSD8 (a test token, 8 decimals) on it. `node` is the agent's door to it.
 */
final class CardanoWorld(payees: Seq[(String, Double, Long)] = CardanoWorld.lifePayees):
  given env: CardanoInfo = CardanoInfo.mainnet
  private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
  private val accounts: Map[String, HdAccount] =
    CardanoWorld.names.zipWithIndex.map((n, i) => n -> HdAccount.fromMnemonic(mnemonic, "", i)).toMap
  private def addr(a: HdAccount): Address =
    ShelleyAddress(env.network, ShelleyPaymentPart.Key(a.paymentKeyHash), ShelleyDelegationPart.Null)
  private def signer(n: String) = new TransactionSigner(Set(accounts(n).paymentKeyPair))
  private def keyHash(n: String) = PubKeyHash(accounts(n).paymentKeyHash)
  val parties: Map[String, Address] = accounts.map((n, a) => n -> addr(a))
  /** a party's address as the owner's address book holds it (bech32) */
  def address(n: String): String = parties(n).asInstanceOf[ShelleyAddress].toBech32.get

  private val tokenPolicy = ScriptHash.fromHex("a1" * 28)
  private val tokenName = AssetName(ByteString.fromString("tUSD8"))
  private def tusd(q: BigInt) = Value.asset(tokenPolicy, tokenName, q.toLong)
  private def qty(v: Value): BigInt = BigInt(v.assets.assets.get(tokenPolicy).flatMap(_.get(tokenName)).getOrElse(0L))

  private val ledger: Emulator =
    val genesis = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
    val start = Seq("owner" -> (Value.ada(1000) + tusd(CardanoWorld.usd(5_000))), "owner" -> Value.ada(1000),
      "agent" -> Value.ada(1000), "stranger" -> (Value.ada(100) + tusd(CardanoWorld.usd(100))))
    Emulator(initialUtxos = start.zipWithIndex.map { case ((n, v), i) =>
      TransactionInput(genesis, i) -> TransactionOutput(parties(n), v) }.toMap)

  /** the emulator's answer, there at once (a ledger in memory) */
  private def got[A](f: Future[A]): A = f.value match
    case Some(scala.util.Success(a)) => a
    case Some(scala.util.Failure(e)) => throw e
    case None => sys.error("the emulator did not answer at once")
  private def utxos(a: Address): Utxos = got(ledger.findUtxos(a)).toOption.get

  // the clock: slots of a second, from 2026-01-01 as the EVM chains in memory
  private var slot: SlotNo = env.slotConfig.timeToSlot(1_767_225_600_000L)
  def now: Long = env.slotConfig.slotToTime(slot)
  def advance(seconds: Long): Unit = { slot = slot + seconds; ledger.setSlot(slot) }
  ledger.setSlot(slot)

  private var script: Script.PlutusV3 = null
  def policyAddress: Address = Policy.address(script, env.network)
  private def policy: Utxo = Utxo(utxos(policyAddress).find((_, o) => o.value.assets.assets.contains(
    script.scriptHash)).get)
  /** the policy's state, read from its datum on the ledger */
  def state: PolicyState = policy.output.inlineDatum.get.to[PolicyState]
  /** what the policy holds of the token */
  def balance: BigInt = if script == null || utxos(policyAddress).isEmpty then 0 else qty(policy.output.value)
  def holds(n: String): BigInt = utxos(parties(n)).values.map(o => qty(o.value)).sum

  /** a transaction built (its scripts run), signed, submitted: accepted, or the reason */
  private def submit(build: => Transaction): Either[String, Transaction] =
    scala.util.Try(build) match
      case scala.util.Failure(e) => Left(CardanoWorld.reason(String.valueOf(e.getMessage) + " " + e.toString))
      case scala.util.Success(tx) => got(ledger.submit(tx)) match
        case Left(e) => Left(CardanoWorld.reason(e.toString))
        case Right(_) => Right(tx)

  /** CREATE: the owner's policy, `deposit` on it */
  def create(deposit: Double = 1_000): Either[String, Transaction] =
    val seed = Utxo(utxos(parties("owner")).find((_, o) => qty(o.value) > 0).get)
    script = Policy.script(TxOutRef(TxId(seed.input.transactionId), BigInt(seed.input.index)))
    val day = BigInt(86_400_000L)
    val st = PolicyState(keyHash("owner"), keyHash("agent"), AssetClass(tokenPolicy, tokenName.bytes), day * 30,
      BigInt(now), CardanoWorld.usd(400), CardanoWorld.usd(300), CardanoWorld.usd(100), BigInt(now) + day * 365,
      false, 0, 0, PList.from(payees.map((n, cap, gap) => Payee(Policy.credential(parties(n)), CardanoWorld.usd(cap),
        day * gap, 0, 0, 0))), PList.Nil, PList.Nil)
    submit(Policy.create(env, seed, script, Value.ada(10) + tusd(CardanoWorld.usd(deposit)), st)
      .complete(availableUtxos = utxos(parties("owner")), sponsor = parties("owner")).sign(signer("owner"))
      .transaction)

  /** DEPOSIT: `who` tops the policy up */
  def deposit(who: String, amount: Double): Either[String, Transaction] =
    submit(Policy.deposit(env, policy, script, state, CardanoWorld.usd(amount))
      .complete(availableUtxos = utxos(parties(who)), sponsor = parties(who)).sign(signer(who)).transaction)

  /** CLOSE: the owner takes everything back */
  def close(): Either[String, Transaction] =
    submit(Policy.close(env, policy, script, state, parties("owner"))
      .complete(availableUtxos = utxos(parties("owner")), sponsor = parties("owner")).sign(signer("owner"))
      .transaction)

  /** A PAYMENT built by `as` (the agent, or an attacker with its own key): signed by `signers`; `send` false — a
   * trial, built (the script run) and never submitted */
  def pay(to: Address, amount: BigInt, invoice: ByteString, signers: Seq[String] = Seq("agent"),
      send: Boolean = true): Either[String, Transaction] =
    val st = state
    def build =
      val b = Policy.pay(env, policy, script, st, to, amount, invoice, slot)
      val done = b.complete(availableUtxos = utxos(parties(signers.head)), sponsor = parties(signers.head))
      signers.foldLeft(done)((d, s) => d.sign(signer(s))).transaction
    if send then submit(build)
    else scala.util.Try(build).toEither.left.map(e => CardanoWorld.reason(String.valueOf(e.getMessage) + " " +
      e.toString))

  /** the requests above approvalAbove, waiting for the owner: to, amount, invoice */
  private var requests = Vector.empty[Option[(Address, BigInt, ByteString)]]
  def waiting: Vector[(Int, Address, BigInt)] =
    requests.zipWithIndex.collect { case (Some((a, x, _)), i) => (i, a, x) }

  /** THE OWNER APPROVES request `n`: the payment signed by the agent and the owner */
  def approve(n: Int): Either[String, Transaction] = requests.lift(n).flatten match
    case None => Left("NoSuchRequest")
    case Some((to, amount, inv)) =>
      val r = pay(to, amount, inv, Seq("agent", "owner"))
      if r.isRight then requests = requests.updated(n, None)
      r

  /** THE AGENT'S DOOR (Agent's Node): a trial, a payment — above approvalAbove a request for the owner — and every
   * refusal with the numbers SpendPolicy.sol's errors carry, read from the policy's state */
  val node: Node = new Node:
    private def inv(id: String) = ByteString.fromHex(id.stripPrefix("0x"))
    private def big(x: BigInt): js.Any = js.BigInt(x.toString)
    private def refused(reason: String, to: String, amount: BigInt, id: String): Outcome =
      val st = state
      val who = Policy.credential(Address.fromBech32(to))
      val p = st.payee(who)
      val w = (BigInt(now) - st.start) / st.period
      val args: Vector[js.Any] = reason match
        case "OverMax" => Vector(big(amount), big(st.maxPayment))
        case "PayeeNotAllowed" => Vector(to)
        case "PayeeCap" => Vector(to, big(p.fold(BigInt(0))(x => if x.window == w then x.spent else BigInt(0)) + amount),
          big(p.fold(BigInt(0))(_.cap)))
        case "PeriodCap" => Vector(big((if st.window == w then st.spent else BigInt(0)) + amount), big(st.periodCap))
        case "TooSoon" => Vector(to, big(p.fold(BigInt(0))(x => (x.last + x.gap) / 1000)))
        case "InsufficientBalance" => Vector(big(balance), big(amount))
        case "AlreadyPaid" => Vector(id)
        case _ => Vector.empty
      Outcome(false, Some(reason), args, 0, None, Vector.empty, "")
    private def amountOf(a: js.BigInt) = BigInt(a.toString)
    def trial(to: String, amount: js.BigInt, invoice: String): Future[Outcome] = Future.successful {
      val x = amountOf(amount)
      CardanoWorld.this.pay(Address.fromBech32(to), x, inv(invoice), send = false) match
        case Left(r) => refused(r, to, x, invoice)
        case Right(_) => Outcome(true, None, Vector.empty, 0, None, Vector.empty, "")
    }
    def pay(to: String, amount: js.BigInt, invoice: String): Future[Outcome] = Future.successful {
      val x = amountOf(amount)
      val a = Address.fromBech32(to)
      if x > state.approvalAbove then
        CardanoWorld.this.pay(a, x, inv(invoice), send = false) match
          case Left(r) => refused(r, to, x, invoice)
          case Right(_) =>
            requests :+= Some((a, x, inv(invoice)))
            Outcome(true, None, Vector.empty, 0, None, Vector(Event("Requested",
              js.Dynamic.literal(id = requests.size - 1, amount = amount))), "")
      else CardanoWorld.this.pay(a, x, inv(invoice)) match
        case Left(r) => refused(r, to, x, invoice)
        case Right(tx) => Outcome(true, None, Vector.empty, tx.body.value.fee.value, None,
          Vector(Event("Paid", js.Dynamic.literal(amount = amount))), tx.id.toHex)
    }

object CardanoWorld:
  val names = Vector("owner", "agent", "stranger", "internet provider", "cloud hosting", "electricity",
    "software licence", "attacker", "gym")
  /** the EVM life's payees: name, cap a window, the least days between payments */
  val lifePayees = Vector(("internet provider", 60.0, 25L), ("cloud hosting", 200.0, 0L), ("electricity", 150.0, 25L),
    ("software licence", 100.0, 0L))
  /** dollars in 8 decimals */
  def usd(x: Double): BigInt = BigInt(math.round(x * 100)) * 1_000_000
  /** the script's error, by its name (the trace "Name: words"), else the ledger's message, short */
  val errors = Vector("NotAgent", "Paused", "Expired", "ZeroAmount", "OverMax", "PayeeNotAllowed", "NotStarted",
    "BadTime", "AlreadyPaid", "PayeeCap", "PeriodCap", "TooSoon", "NeedsOwner", "InsufficientBalance",
    "StateChanged", "BalanceChanged", "NotPaid", "DepositNothing", "NotOwner", "OnlyTheToken", "SameAddress",
    "OneOutput", "OneInput", "NoMint", "WindowTooLong", "NotThePolicy", "BadPeriod")
  def reason(message: String): String =
    errors.map(e => e -> message.indexOf(e + ":")).filter(_._2 >= 0).sortBy(_._2).headOption.map(_._1)
      .getOrElse(message.take(200))
