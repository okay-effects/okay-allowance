package okay.allowance

import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * THE COMMAND LINE (node): `contracts` compiles; `demo [dir]` runs the agent on three chains; `run CONFIG DIR` pays a
 * folder of invoices at once on real nodes. THE PLAN (specs/allowance.md §13): `intake CONFIG DIR` takes a folder of
 * invoices into the plan (proposed); `pay-due CONFIG` pays what the owner approved and is due (for cron); `mcp demo`
 * serves both doors over MCP on the demo's three chains in memory; `mcp agent CONFIG`, `mcp owner CONFIG` each door on
 * real nodes, the plan file shared. The config (JSON, outside the repository):
 * `{ "chains": [{ "name", "rpc", "chainId", "policy", "keyFile"? }, …], "book": { "<payee>": { "<chain>": "0x…" } },
 * "bills": [{ "invoice", "payee", "amount", "chain"?, "for"?, "from", "to", "every"? }, …], "plan": "plan.json" }`
 * — the chains in the owner's order of preference; each key file outside the repository (none for the owner's door:
 * it only reads); the bills seed the plan, approved, the first time.
 */
object Main:
  private def show(e: Entry): Unit =
    println(f"${e.status}%-8s ${e.invoice}%-22s ${e.words}" + (if e.tx.isEmpty then "" else s"  [${e.tx.take(10)}…]"))

  private final case class Config(nodes: Map[String, Node], chains: Vector[String], book: Book, plan: PlanStore)

  private def config(file: String): Config =
    val cfg = js.JSON.parse(Files.read(file))
    val abi = Files.compiled.contracts.SpendPolicy.abi
    val chains = cfg.chains.asInstanceOf[js.Array[js.Dynamic]].toVector
    def key(c: js.Dynamic) = if js.isUndefined(c.keyFile) then "" else Files.read(c.keyFile.toString).trim
    val nodes = chains.map(c => c.name.toString -> (Rpc(c.rpc.toString, c.chainId.toString.toInt,
      c.policy.toString, key(c), abi): Node)).toMap
    val book = Book(cfg.book.asInstanceOf[js.Dictionary[js.Dictionary[String]]].toMap.map((k, v) => k -> v.toMap))
    val path = if js.isUndefined(cfg.plan) then "plan.json" else cfg.plan.toString
    Config(nodes, chains.map(_.name.toString), book, FilePlan(path, Plan.seed(Bill.from(cfg.bills))))

  private val journal = (e: Entry) => Files.append("journal.jsonl", js.JSON.stringify(js.Dynamic.literal(
    at = new js.Date().toISOString(), invoice = e.invoice, chain = e.chain, status = e.status, by = e.by,
    reason = e.reason, words = e.words, tx = e.tx)) + "\n")
  private def now = (js.Date.now() / 1000).toLong

  /** a folder of invoices, each with its window and once/monthly if it names them */
  private def windows(dir: String): Vector[(Invoice, Option[String], Option[String], String)] =
    Files.list(dir).filter(_.endsWith(".json")).map { f =>
      val text = Files.read(s"$dir/$f")
      val o = js.JSON.parse(text)
      def opt(k: String) = Option(o.selectDynamic(k)).filterNot(js.isUndefined).map(_.toString)
      (Invoice.parse(text), opt("from"), opt("to"), opt("every").getOrElse("once"))
    }

  def main(unused: Array[String]): Unit =
    val args = Files.args
    args.headOption match
      case Some("contracts") => println(Contracts.build())
      case Some("demo") =>
        Agent.demo(Files.compiled, Files.invoices(args.lift(1).getOrElse("demo/invoices")))
          .foreach(_.entries.foreach(show))
      case Some("run") if args.size == 3 =>
        val c = config(args(1))
        Agent.on(c.nodes, c.chains, Files.invoices(args(2)), c.book, journal).foreach(_.foreach(show))
      case Some("intake") if args.size == 3 =>
        val c = config(args(1))
        Life.inOrder(windows(args(2))) { (inv, from, to, every) =>
          Planner.intake(inv, from, to, every, now, c.nodes, c.chains, c.book).map { p =>
            c.plan.load.add(p) match
              case Left(e) => println(s"skipped  ${inv.id}: $e")
              case Right(plan) => c.plan.save(plan); println(f"${p.state}%-8s ${p.invoice} ${p.note}".trim)
          }
        }
      case Some("pay-due") if args.size == 2 =>
        val c = config(args(1))
        Planner.payDue(c.plan, c.nodes, c.chains, c.book, now, journal)
          .foreach(_.foreach(p => println(f"${p.state}%-8s ${p.invoice}%-22s ${p.note}")))
      case Some("mcp") if args.lift(1).contains("demo") =>
        Agent.demo(Files.compiled, Vector.empty).foreach(d =>
          McpServer.stdio(McpServer(d.nodes, Agent.demoChains, d.book, MemoryPlan(Plan.seed(Files.bills)),
            Role.Demo, today = () => d.worlds("ethereum").chain.now.toString.toLong)))
      case Some("mcp") if args.size == 3 && Set("agent", "owner")(args(1)) =>
        val c = config(args(2))
        McpServer.stdio(McpServer(c.nodes, c.chains, c.book, c.plan,
          if args(1) == "agent" then Role.Agent else Role.Owner, journal))
      case _ => println("usage: contracts | demo [invoices-dir] | run CONFIG.json INVOICES-DIR | intake CONFIG DIR |" +
        " pay-due CONFIG | mcp demo | mcp agent|owner CONFIG.json")
