package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** npm's modules, as ES modules: the EVM in JavaScript (@ethereumjs) and viem's encoding */
private[allowance] object Npm:
  @js.native @JSImport("@ethereumjs/vm", JSImport.Namespace) object Vm extends js.Object
  @js.native @JSImport("@ethereumjs/tx", JSImport.Namespace) object Tx extends js.Object
  @js.native @JSImport("@ethereumjs/block", JSImport.Namespace) object Block extends js.Object
  @js.native @JSImport("@ethereumjs/common", JSImport.Namespace) object Common extends js.Object
  @js.native @JSImport("@ethereumjs/util", JSImport.Namespace) object Util extends js.Object
  @js.native @JSImport("viem", JSImport.Namespace) object Viem extends js.Object
  def vm: js.Dynamic = Vm.asInstanceOf[js.Dynamic]
  def tx: js.Dynamic = Tx.asInstanceOf[js.Dynamic]
  def block: js.Dynamic = Block.asInstanceOf[js.Dynamic]
  def common: js.Dynamic = Common.asInstanceOf[js.Dynamic]
  def util: js.Dynamic = Util.asInstanceOf[js.Dynamic]
  def viem: js.Dynamic = Viem.asInstanceOf[js.Dynamic]

/** a party: its name, its key (a demo key derived from its name — public, never a real wallet), its address */
final case class Party(name: String, key: js.Any, address: String)

/** a contract's event, decoded: its name and its arguments by name */
final case class Event(name: String, args: js.Dynamic)

/** what a transaction did: accepted, or refused with the contract's own error and its arguments; the gas, the
 * contract it created, its events, its hash */
final case class Outcome(ok: Boolean, reason: Option[String], args: Vector[js.Any], gas: Long, created: Option[String],
                         events: Vector[Event], hash: String)

/**
 * A CHAIN IN MEMORY (specs/allowance.md §4): @ethereumjs/vm — the Ethereum virtual machine in JavaScript, the same in
 * node and in a page — signed transactions from demo keys, a clock the demo moves, the contracts' calls and their own
 * errors decoded by viem. No network, no money.
 */
final class Chain private (compiled: js.Dynamic, vm: js.Dynamic, common: js.Dynamic)(using ExecutionContext):
  private var time = js.BigInt("1767225600") // 2026-01-01
  private var number = js.BigInt(1)
  private def abiOf(name: String): js.Dynamic = compiled.contracts.selectDynamic(name).abi
  private def allAbis: js.Array[js.Dynamic] =
    js.Object.keys(compiled.contracts.asInstanceOf[js.Object]).map(k => abiOf(k))
  private def newBlock(): js.Dynamic = Npm.block.createBlock(
    js.Dynamic.literal(header = js.Dynamic.literal(timestamp = time, number = number,
      gasLimit = js.BigInt("30000000"), baseFeePerGas = js.BigInt(7))),
    js.Dynamic.literal(common = common, skipConsensusFormatValidation = true))
  private def address(s: String): js.Dynamic = Npm.util.createAddressFromString(s)
  private def hex(bytes: js.Any): String = Npm.util.bytesToHex(bytes).asInstanceOf[String]

  def now: js.BigInt = time
  def advance(seconds: Long): Unit = time = time + js.BigInt(seconds.toString)

  /** a demo party: its key from its name, 1000 ether for gas */
  def party(name: String): Future[Party] =
    val key = Npm.util.hexToBytes(Npm.viem.keccak256(Npm.viem.toHex(s"okay-invest demo key: $name")))
    val addr = Npm.util.createAddressFromPrivateKey(key)
    vm.stateManager.putAccount(addr, Npm.util.createAccount(js.Dynamic.literal(nonce = js.BigInt(0),
      balance = js.BigInt("1000000000000000000000")))).asInstanceOf[js.Promise[Unit]].toFuture
      .map(_ => Party(name, key, addr.toString().asInstanceOf[String]))

  /** a contract's own error, its name and arguments, from a call's result */
  private def why(ex: js.Dynamic, abi: js.Dynamic): (Option[String], Vector[js.Any]) =
    if js.isUndefined(ex.exceptionError) || ex.exceptionError == null then (None, Vector.empty)
    else
      val ret = hex(ex.returnValue)
      try
        val e = Npm.viem.decodeErrorResult(js.Dynamic.literal(abi = js.Array(abi, abiOf("SpendPolicy")).asInstanceOf[
          js.Dynamic].flat(), data = ret))
        val args = if js.isUndefined(e.args) then Vector.empty else e.args.asInstanceOf[js.Array[js.Any]].toVector
        (Some(e.errorName.asInstanceOf[String]), args)
      catch case _: Throwable =>
        (Some(if ret == "0x" then ex.exceptionError.error.asInstanceOf[String] else "reverted " + ret.take(10)),
          Vector.empty)

  /** a signed transaction run in a new block */
  private def send(from: Party, to: Option[String], data: String, abi: js.Dynamic): Future[Outcome] =
    for
      account <- vm.stateManager.getAccount(address(from.address)).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      tx = Npm.tx.createLegacyTx(js.Dynamic.literal(nonce = account.nonce, gasPrice = js.BigInt("1000000000"),
        gasLimit = js.BigInt("6000000"), to = to.fold(js.undefined: js.Any)(a => address(a)),
        data = Npm.util.hexToBytes(data)), js.Dynamic.literal(common = common)).sign(from.key)
      r <- Npm.vm.runTx(vm, js.Dynamic.literal(tx = tx, block = newBlock(), skipBlockGasLimitValidation = true,
        skipHardForkValidation = true)).asInstanceOf[js.Promise[js.Dynamic]].toFuture
    yield
      number = number + js.BigInt(1)
      val (reason, args) = why(r.execResult, abi)
      val logs = if js.isUndefined(r.receipt) then js.Array[js.Dynamic]()
        else r.receipt.logs.asInstanceOf[js.Array[js.Dynamic]]
      val events = logs.toVector.flatMap { log =>
        val topics = log.asInstanceOf[js.Array[js.Any]](1).asInstanceOf[js.Array[js.Any]].map(t => hex(t))
        val data = hex(log.asInstanceOf[js.Array[js.Any]](2))
        allAbis.iterator.flatMap { a =>
          try
            val e = Npm.viem.decodeEventLog(js.Dynamic.literal(abi = a, topics = topics, data = data))
            Some(Event(e.eventName.asInstanceOf[String], e.args))
          catch case _: Throwable => None
        }.nextOption()
      }
      Outcome(reason.isEmpty, reason, args, r.totalGasSpent.toString().asInstanceOf[String].toLong,
        Option(r.createdAddress).filterNot(js.isUndefined).map(_.toString().asInstanceOf[String]), events,
        hex(tx.hash()))

  def deploy(from: Party, name: String, args: js.Any*): Future[String] =
    val c = compiled.contracts.selectDynamic(name)
    send(from, None, Npm.viem.encodeDeployData(js.Dynamic.literal(abi = c.abi, bytecode = c.bytecode,
      args = js.Array(args*))).asInstanceOf[String], c.abi).map { r =>
      if !r.ok then throw new RuntimeException(s"deploy $name: ${r.reason.getOrElse("")}")
      r.created.get
    }

  def encode(name: String, fn: String, args: js.Any*): String =
    Npm.viem.encodeFunctionData(js.Dynamic.literal(abi = abiOf(name), functionName = fn, args = js.Array(args*)))
      .asInstanceOf[String]

  def call(from: Party, to: String, name: String, fn: String, args: js.Any*): Future[Outcome] =
    send(from, Some(to), encode(name, fn, args*), abiOf(name))

  def view(to: String, name: String, fn: String, args: js.Any*): Future[js.Dynamic] =
    vm.evm.runCall(js.Dynamic.literal(to = address(to), block = newBlock(),
      data = Npm.util.hexToBytes(encode(name, fn, args*)))).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { r =>
      if !js.isUndefined(r.execResult.exceptionError) && r.execResult.exceptionError != null then
        throw new RuntimeException(s"$fn: ${r.execResult.exceptionError.error}")
      Npm.viem.decodeFunctionResult(js.Dynamic.literal(abi = abiOf(name), functionName = fn,
        data = hex(r.execResult.returnValue)))
    }

  /** A TRIAL: the call run as `from` would run it, rolled back — would it pass, and why not; nothing written */
  def simulate(from: Party, to: String, name: String, fn: String, args: js.Any*): Future[Outcome] =
    for
      _ <- vm.stateManager.checkpoint().asInstanceOf[js.Promise[Unit]].toFuture
      r <- vm.evm.runCall(js.Dynamic.literal(to = address(to), caller = address(from.address),
        origin = address(from.address), block = newBlock(), data = Npm.util.hexToBytes(encode(name, fn, args*))))
        .asInstanceOf[js.Promise[js.Dynamic]].toFuture
      _ <- vm.stateManager.revert().asInstanceOf[js.Promise[Unit]].toFuture
    yield
      val (reason, a) = why(r.execResult, abiOf(name))
      Outcome(reason.isEmpty, reason, a, 0, None, Vector.empty, "")

object Chain:
  def apply(compiled: js.Dynamic)(using ExecutionContext): Future[Chain] =
    val common = js.Dynamic.newInstance(Npm.common.Common)(js.Dynamic.literal(chain = Npm.common.Mainnet,
      hardfork = Npm.common.Hardfork.Cancun))
    Npm.vm.createVM(js.Dynamic.literal(common = common)).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .map(vm => new Chain(compiled, vm, common))
