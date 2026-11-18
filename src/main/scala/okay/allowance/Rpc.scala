package okay.allowance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native @JSImport("viem/accounts", JSImport.Namespace) private object ViemAccounts extends js.Object

/**
 * A REAL NODE over RPC (a testnet): viem's public client for trials (`simulateContract`), its wallet client for the
 * agent's transactions, the receipt's events decoded; the contract's own errors read from viem's errors. The key is
 * the agent's, from a file outside the repository (the caller reads it).
 */
final class Rpc(url: String, chainId: Int, policy: String, privateKey: String, abi: js.Dynamic)(using ExecutionContext)
    extends Node:
  private val v = Npm.viem
  private val account = ViemAccounts.asInstanceOf[js.Dynamic].privateKeyToAccount(privateKey)
  private val chain = js.Dynamic.literal(id = chainId, name = "testnet",
    nativeCurrency = js.Dynamic.literal(name = "gas", symbol = "GAS", decimals = 18),
    rpcUrls = js.Dynamic.literal(default = js.Dynamic.literal(http = js.Array(url))))
  private val public = v.createPublicClient(js.Dynamic.literal(chain = chain, transport = v.http(url)))
  private val wallet =
    v.createWalletClient(js.Dynamic.literal(account = account, chain = chain, transport = v.http(url)))
  def agentAddress: String = account.address.asInstanceOf[String]

  private def refused(e: Any): Outcome =
    val err = e.asInstanceOf[js.Dynamic]
    val found = if js.isUndefined(err.walk) then null
      else err.walk((x: js.Dynamic) => !js.isUndefined(x.data) && !js.isUndefined(x.data.errorName))
    val (reason, args) =
      if found == null || js.isUndefined(found) then ("reverted", Vector.empty[js.Any])
      else (found.data.errorName.toString,
        if js.isUndefined(found.data.args) then Vector.empty
        else found.data.args.asInstanceOf[js.Array[js.Any]].toVector)
    Outcome(false, Some(reason), args, 0, None, Vector.empty, "")

  private def request(to: String, amount: js.BigInt, invoice: String) = js.Dynamic.literal(account = account,
    address = policy, abi = abi, functionName = "pay", args = js.Array[js.Any](to, amount, invoice))

  def status(payees: Seq[String]): Future[Status] = EvmStatus((fn, args) =>
    public.readContract(js.Dynamic.literal(address = policy, abi = abi, functionName = fn,
      args = js.Array(args*))).asInstanceOf[js.Promise[js.Dynamic]].toFuture, payees)

  def paid(invoice: String): Future[Boolean] =
    public.readContract(js.Dynamic.literal(address = policy, abi = abi, functionName = "paidInvoice",
      args = js.Array[js.Any](invoice))).asInstanceOf[js.Promise[Boolean]].toFuture

  def trial(to: String, amount: js.BigInt, invoice: String): Future[Outcome] =
    public.simulateContract(request(to, amount, invoice)).asInstanceOf[js.Promise[js.Any]].toFuture
      .map(_ => Outcome(true, None, Vector.empty, 0, None, Vector.empty, ""))
      .recover { case js.JavaScriptException(e) => refused(e) }

  def pay(to: String, amount: js.BigInt, invoice: String): Future[Outcome] =
    (for
      hash <- wallet.writeContract(request(to, amount, invoice)).asInstanceOf[js.Promise[String]].toFuture
      receipt <- public.waitForTransactionReceipt(js.Dynamic.literal(hash = hash))
        .asInstanceOf[js.Promise[js.Dynamic]].toFuture
    yield
      val ok = receipt.status.toString == "success"
      val events = v.parseEventLogs(js.Dynamic.literal(abi = abi, logs = receipt.logs))
        .asInstanceOf[js.Array[js.Dynamic]].toVector.map(e => Event(e.eventName.toString, e.args))
      Outcome(ok, if ok then None else Some("reverted"), Vector.empty,
        receipt.gasUsed.toString.toLong, None, events, hash)
    ).recover { case js.JavaScriptException(e) => refused(e) }
