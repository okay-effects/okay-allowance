/** okay-allowance: an agent's allowance in a stablecoin, its hands tied by a contract (specs/allowance.md). Scala 3 on
 * Scala.js — the agent on okay-scala's effects, the chain in memory, the contracts' build, the tests — over npm's
 * solc-js, @ethereumjs/vm and viem (ES modules from node_modules) */
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")
ThisBuild / resolvers ++= Seq("okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven")

lazy val root = (project in file(".")).enablePlugins(ScalaJSPlugin)
  .settings(name := "okay-allowance",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "org.scalus" %%% "scalus-cardano-ledger" % "1.4.0",
      "org.scalameta" %%% "munit" % "1.2.3" % Test),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule).withESFeatures(_.withESVersion(ESVersion.ES2020))),
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("okay.allowance.Main"))
