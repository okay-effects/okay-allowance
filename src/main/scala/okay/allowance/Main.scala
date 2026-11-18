package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * THE COMMAND LINE (node): `contracts` compiles; `demo [dir]` runs the agent on two chains in memory; `run CONFIG DIR`
 * on real nodes — the config (JSON, outside the repository):
 * `{ "chains": [{ "name", "rpc", "chainId", "policy", "keyFile" }, …], "book": { "<payee>": { "<chain>": "0x…" } } }`,
 * the chains in the owner's order of preference; each key file outside the repository.
 */
object Main:
  private def show(e: Entry): Unit =
    println(f"${e.status}%-8s ${e.invoice}%-22s ${e.words}" + (if e.tx.isEmpty then "" else s"  [${e.tx.take(10)}…]"))

  def main(unused: Array[String]): Unit =
    val args = Files.args
    args.headOption match
      case Some("contracts") => println(Contracts.build())
      case Some("demo") =>
        Agent.demo(Files.compiled, Files.invoices(args.lift(1).getOrElse("demo/invoices"))).foreach(_._1.foreach(show))
      case Some("run") if args.size == 3 =>
        val cfg = js.JSON.parse(Files.read(args(1)))
        val abi = Files.compiled.contracts.SpendPolicy.abi
        val chains = cfg.chains.asInstanceOf[js.Array[js.Dynamic]].toVector
        val nodes = chains.map(c => c.name.toString -> (Rpc(c.rpc.toString, c.chainId.toString.toInt,
          c.policy.toString, Files.read(c.keyFile.toString).trim, abi): Node)).toMap
        val book = Book(cfg.book.asInstanceOf[js.Dictionary[js.Dictionary[String]]].toMap.map((k, v) => k -> v.toMap))
        val journal = (e: Entry) => Files.append("journal.jsonl", js.JSON.stringify(js.Dynamic.literal(
          at = new js.Date().toISOString(), invoice = e.invoice, chain = e.chain, status = e.status, by = e.by,
          reason = e.reason, words = e.words, tx = e.tx)) + "\n")
        Agent.on(nodes, chains.map(_.name.toString), Files.invoices(args(2)), book, journal).foreach(_.foreach(show))
      case _ => println("usage: contracts | demo [invoices-dir] | run CONFIG.json INVOICES-DIR")
