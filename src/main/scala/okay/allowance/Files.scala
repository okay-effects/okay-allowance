package okay.allowance

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** node's files, for the command line and the tests (never the page) */
object Files:
  @js.native @JSImport("node:fs", JSImport.Namespace) private object Fs extends js.Object
  private def fs = Fs.asInstanceOf[js.Dynamic]
  def read(path: String): String = fs.readFileSync(path, "utf8").asInstanceOf[String]
  def write(path: String, text: String): Unit = fs.writeFileSync(path, text)
  def append(path: String, text: String): Unit = fs.appendFileSync(path, text)
  def mkdirs(path: String): Unit = fs.mkdirSync(path, js.Dynamic.literal(recursive = true))
  def list(dir: String): Vector[String] = fs.readdirSync(dir).asInstanceOf[js.Array[String]].toVector.sorted
  def args: Vector[String] = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]].toVector.drop(2)

  /** THE REPOSITORY: the working directory if it holds out/contracts.json, else the one above the running script
   * (target/scala-3.3.8/okay-allowance-fastopt/main.js) — an MCP client starts the server from anywhere */
  lazy val home: String =
    val script = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]].lift(1).getOrElse("")
    val above = Iterator.iterate(script)(p => p.take(p.lastIndexOf("/") max 0)).drop(1).takeWhile(_.nonEmpty)
    ("." +: above.toVector).find(d => fs.existsSync(s"$d/out/contracts.json").asInstanceOf[Boolean]).getOrElse(".")

  /** the compiled contracts, written by `contracts` */
  def compiled: js.Dynamic = js.JSON.parse(read(s"$home/out/contracts.json"))
  /** the demo's bills to pay (§12) */
  def bills: Vector[Bill] = Bill.parse(read(s"$home/demo/bills.json"))
  /** a folder of invoices (*.json), in name order */
  def invoices(dir: String): Vector[Invoice] =
    list(dir).filter(_.endsWith(".json")).map(f => Invoice.parse(read(s"$dir/$f")))
