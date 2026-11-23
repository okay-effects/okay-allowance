//> using scala 3.3.8
// THE POLICY'S COMPILED CODE FOR THE AGENT (specs/allowance.md §9): after `sbt blueprintPin` in cardano/contracts,
// the script from plutus.json written as core/src/main/scala/okay/allowance/cardano/PolicyCode.scala — data the
// Scala.js build applies its seed to. `scala-cli run scripts/policy-code.scala` from the repository's root.
import java.nio.file.{Files, Path}

@main def policyCode(): Unit =
  val json = Files.readString(Path.of("cardano/contracts/plutus.json"))
  val code = """"compiledCode"\s*:\s*"([0-9a-f]+)"""".r.findFirstMatchIn(json).map(_.group(1)).get
  val hash = """"hash"\s*:\s*"([0-9a-f]+)"""".r.findFirstMatchIn(json).map(_.group(1)).get
  val dir = Path.of("core/src/main/scala/okay/allowance/cardano")
  Files.createDirectories(dir)
  Files.writeString(dir.resolve("PolicyCode.scala"),
    s"""package okay.allowance.cardano
       |
       |/** THE SPEND POLICY'S COMPILED CODE (cardano/contracts/plutus.json; scripts/policy-code.scala).
       | * Generated: do not edit. Unapplied: each policy applies its seed (`Policy.script`) */
       |object PolicyCode:
       |  val hash: String = "$hash"
       |  val cborHex: String =
       |""".stripMargin + code.grouped(110).map(g => "    \"" + g + "\"").mkString(" +\n") + "\n")
  println(s"PolicyCode: ${code.length / 2} bytes, hash $hash")
