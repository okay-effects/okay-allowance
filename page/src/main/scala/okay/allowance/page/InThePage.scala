package okay.allowance.page

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import org.scalajs.dom
import okay.business.scala3.*
import okay.business.faces.Skin
import okay.business.laminar.Laminar
import okay.scala3.std.Async
import okay.allowance.{Agent, Bill, Plan}

/**
 * THE OWNER'S PAGE IN THE BROWSER (specs/allowance.md §14): the compiled contracts and the owner's standing orders
 * fetched, three chains set up in the page (Ethereum and Base on the EVM in JavaScript, Cardano on Scalus's emulator,
 * a policy on each with 1 000 tUSD8), then the program — its screens on okay-ui's Laminar face, its world the demo's
 * desk. No network after the page's own files, no wallet, no money.
 */
object InThePage:
  val skin: Skin = Skin.calm(accent = "#0b7a5c", deep = "#0d3b30")
  type Page[X] = (Ui + Desk + Async)[X]

  private def fetchText(url: String): Future[String] =
    dom.fetch(url).toFuture.flatMap(r =>
      if r.ok then r.text().toFuture else Future.failed(new RuntimeException(s"$url: ${r.status}")))

  def main(args: Array[String]): Unit =
    val root = dom.document.getElementById("app")
    (for
      contracts <- fetchText("contracts.json")
      bills <- fetchText("bills.json")
      demo <- Agent.demo(js.JSON.parse(contracts), Vector.empty)
      _ = root.innerHTML = "" // the "setting up" line: the chains are up
      desk = DemoDesk(demo, Plan.seed(Bill.parse(bills)))
      _ <- Async.toFuture(Laminar.run(root, skin)(desk(OwnerPage.main[Page]())))
    yield ()).failed.foreach { e =>
      root.textContent = s"The demo could not start: ${e.getMessage}"
      dom.console.error(e.asInstanceOf[js.Any])
    }
