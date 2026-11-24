package okay.allowance

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * THE CONTRACTS COMPILED (specs/allowance.md §4): solc-js — Solidity's own compiler as WebAssembly — the optimizer on;
 * out/contracts.json holds each contract's ABI and bytecode for the tests, the agent and the page.
 */
object Contracts:
  @js.native @JSImport("solc", JSImport.Default) private object Solc extends js.Object
  val files = Vector("SpendPolicy.sol", "test/TestUSD.sol", "test/NoBoolUSD.sol")

  /** compiled from contracts/; errors stop it */
  def build(): String =
    val solc = Solc.asInstanceOf[js.Dynamic]
    val sources = js.Dictionary(files.map(f => f -> js.Dynamic.literal(content = Files.read(s"contracts/$f")))*)
    val input = js.Dynamic.literal(language = "Solidity", sources = sources, settings = js.Dynamic.literal(
      optimizer = js.Dynamic.literal(enabled = true, runs = 200), evmVersion = "cancun",
      outputSelection = js.Dictionary("*" -> js.Dictionary("*" -> js.Array("abi", "evm.bytecode.object")))))
    val out = js.JSON.parse(solc.compile(js.JSON.stringify(input)).asInstanceOf[String])
    val errors = if js.isUndefined(out.errors) then Vector.empty
      else out.errors.asInstanceOf[js.Array[js.Dynamic]].toVector
    errors.foreach(e => System.err.println(e.formattedMessage))
    if errors.exists(_.severity.toString == "error") then sys.error("the contracts did not compile")
    val contracts = js.Dictionary.empty[js.Any]
    for file <- js.Object.keys(out.contracts.asInstanceOf[js.Object]); fileOut = out.contracts.selectDynamic(file)
        name <- js.Object.keys(fileOut.asInstanceOf[js.Object]) do
      val c = fileOut.selectDynamic(name)
      contracts(name) = js.Dynamic.literal(abi = c.abi, bytecode = "0x" + c.evm.bytecode.`object`.toString)
    val version = solc.version().toString
    Files.mkdirs("out")
    Files.write("out/contracts.json", js.JSON.stringify(js.Dynamic.literal(compiler = version, contracts = contracts),
      null, 1))
    s"compiled $version ${contracts.keys.mkString(", ")}"
