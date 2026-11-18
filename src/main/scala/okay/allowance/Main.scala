package okay.allowance

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE COMMAND LINE (node): `contracts` compiles; `demo [dir]` runs the agent on the chain in memory; `run …` on a
 * real node (`sbt "run run --rpc URL --chain-id N --policy 0x… --key-file FILE --book book.json DIR"`) */
object Main:
  private def show(e: Entry): Unit =
    println(f"${e.status}%-8s ${e.invoice}%-22s ${e.words}" + (if e.tx.isEmpty then "" else s"  [${e.tx.take(10)}…]"))

  def main(unused: Array[String]): Unit =
    val args = Node.args
    args.headOption match
      case Some("contracts") => println(Contracts.build())
      case Some("demo") =>
        Agent.demo(Node.compiled, Node.invoices(args.lift(1).getOrElse("demo/invoices"))).foreach(_._1.foreach(show))
      case Some("run") =>
        def opt(k: String) = args.indexOf(k) match
          case -1 => sys.error(s"missing $k")
          case i => args(i + 1)
        val book = scala.scalajs.js.JSON.parse(Node.read(opt("--book")))
          .asInstanceOf[scala.scalajs.js.Dictionary[String]].toMap
        val node = Rpc(opt("--rpc"), opt("--chain-id").toInt, opt("--policy"), Node.read(opt("--key-file")).trim,
          Node.compiled.contracts.SpendPolicy.abi)
        val journal = (e: Entry) => Node.append("journal.jsonl", scala.scalajs.js.JSON.stringify(scala.scalajs.js
          .Dynamic.literal(at = new scala.scalajs.js.Date().toISOString(), invoice = e.invoice, status = e.status,
            by = e.by, reason = e.reason, words = e.words, tx = e.tx)) + "\n")
        okay.scala3.std.Async.toFuture(Journal.to(journal)(Ledger.rpc(node)(Agent.run[Agent.OnChain](
          Node.invoices(args.last), book)))).foreach(_.foreach(show))
      case _ => println("usage: contracts | demo [invoices-dir] | run --rpc URL --chain-id N --policy 0x… " +
        "--key-file FILE --book book.json invoices-dir")
