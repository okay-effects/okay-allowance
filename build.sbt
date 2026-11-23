/** okay-allowance: an agent's allowance in a stablecoin, its hands tied by a contract (specs/allowance.md). Scala 3 on
 * Scala.js — the agent on okay-scala's effects, the chains in memory, the plan; the contracts' build, the command line
 * and MCP over stdio in node; the owner's page in the browser — over npm's solc-js, @ethereumjs/vm and viem, and
 * Scalus for Cardano (ES modules from node_modules) */
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")
ThisBuild / resolvers ++= Seq("okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven")

lazy val esModules = scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
  .withESFeatures(_.withESVersion(ESVersion.ES2020)))

/** THE CODE NODE AND THE PAGE SHARE (§14): the chains in memory, the agent, the rules, the plan, Cardano's off-chain
 * side — no node module imported */
lazy val core = (project in file("core")).enablePlugins(ScalaJSPlugin)
  .settings(name := "okay-allowance-core", esModules,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "org.scalus" %%% "scalus-cardano-ledger" % "1.4.0"))

/** NODE: the contracts' build (solc-js), the command line, MCP over stdio, the plan in a file; the tests */
lazy val root = (project in file(".")).enablePlugins(ScalaJSPlugin).dependsOn(core)
  .settings(name := "okay-allowance", esModules,
    libraryDependencies ++= Seq(
      // the MCP server (specs/allowance.md §10): okay-mcp on okay-simple, its async module on the machine
      "io.github.okay-effects" %%% "okay-mcp" % "0.1.0",
      "io.github.okay-effects" %%% "okay-effects-async" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-machine" % "0.1.1",
      "org.scalameta" %%% "munit" % "1.2.3" % Test),
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("okay.allowance.Main"))
