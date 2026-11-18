package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * THE COMMAND LINE (node): `contracts` compiles; `demo [dir]` runs the agent on three chains; `run CONFIG DIR`
 * on real nodes; `mcp demo` serves the policy's door over MCP on the demo's three chains in memory, `mcp CONFIG` on
 * real nodes (specs/allowance.md §10). The config (JSON, outside the repository):
 * `{ "chains": [{ "name", "rpc", "chainId", "policy", "keyFile" }, …], "book": { "<payee>": { "<chain>": "0x…" } } }`,
 * the chains in the owner's order of preference; each key file outside the repository.
 */
object Main:
  private def show(e: Entry): Unit =
    println(f"${e.status}%-8s ${e.invoice}%-22s ${e.words}" + (if e.tx.isEmpty then "" else s"  [${e.tx.take(10)}…]"))

  /** a config's real nodes, the chains in order, the book */
  private def config(file: String): (Map[String, Node], Vector[String], Book) =
    val cfg = js.JSON.parse(Files.read(file))
    val abi = Files.compiled.contracts.SpendPolicy.abi
    val chains = cfg.chains.asInstanceOf[js.Array[js.Dynamic]].toVector
    val nodes = chains.map(c => c.name.toString -> (Rpc(c.rpc.toString, c.chainId.toString.toInt,
      c.policy.toString, Files.read(c.keyFile.toString).trim, abi): Node)).toMap
    val book = Book(cfg.book.asInstanceOf[js.Dictionary[js.Dictionary[String]]].toMap.map((k, v) => k -> v.toMap))
    (nodes, chains.map(_.name.toString), book)

  private val journal = (e: Entry) => Files.append("journal.jsonl", js.JSON.stringify(js.Dynamic.literal(
    at = new js.Date().toISOString(), invoice = e.invoice, chain = e.chain, status = e.status, by = e.by,
    reason = e.reason, words = e.words, tx = e.tx)) + "\n")

  def main(unused: Array[String]): Unit =
    val args = Files.args
    args.headOption match
      case Some("contracts") => println(Contracts.build())
      case Some("demo") =>
        Agent.demo(Files.compiled, Files.invoices(args.lift(1).getOrElse("demo/invoices")))
          .foreach(_.entries.foreach(show))
      case Some("run") if args.size == 3 =>
        val (nodes, chains, book) = config(args(1))
        Agent.on(nodes, chains, Files.invoices(args(2)), book, journal).foreach(_.foreach(show))
      case Some("mcp") if args.lift(1).contains("demo") =>
        Agent.demo(Files.compiled, Vector.empty).foreach(d =>
          McpServer.stdio(McpServer(d.nodes, Agent.demoChains, d.book)))
      case Some("mcp") if args.size == 2 =>
        val (nodes, chains, book) = config(args(1))
        McpServer.stdio(McpServer(nodes, chains, book, journal))
      case _ => println("usage: contracts | demo [invoices-dir] | run CONFIG.json INVOICES-DIR | mcp demo|CONFIG.json")
