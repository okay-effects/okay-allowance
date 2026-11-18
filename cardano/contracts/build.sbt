// THE POLICY ON CARDANO (specs/allowance.md §9): Scalus 1.4.0 on Scala 3.9.0, a build of its own beside the agent's
// Scala.js build — which takes the compiled script (the CIP-57 blueprint, plutus.json) as data.
val scalusVersion = "1.4.0"

lazy val root = (project in file("."))
  .enablePlugins(ScalusBlueprintPlugin)
  .settings(
    name := "okay-allowance-cardano",
    organization := "io.github.okay-effects",
    scalaVersion := "3.9.0",
    scalacOptions ++= Seq("-deprecation", "-feature"),
    libraryDependencies ++= Seq(
      "org.scalus" %% "scalus" % scalusVersion,
      "org.scalus" %% "scalus-cardano-ledger" % scalusVersion,
      "org.scalus" %% "scalus-testkit" % scalusVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test),
    addCompilerPlugin(("org.scalus" % "scalus-plugin" % scalusVersion).cross(CrossVersion.full)))
