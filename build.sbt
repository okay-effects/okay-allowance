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

/** THE OWNER'S PAGE (§14): okay-ui screens over the demo's chains in the browser; `sbt web` (below) bundles it */
lazy val page = (project in file("page")).enablePlugins(ScalaJSPlugin).dependsOn(core)
  .settings(name := "okay-allowance-page", esModules,
    resolvers ++= Seq("okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
      "okay-http" at "https://okay-effects.github.io/okay-http/maven",
      "okay-ui" at "https://okay-effects.github.io/okay-ui/maven"),
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui-scala3" % "0.1.0",
      "io.github.okay-effects" %%% "okay-ui-faces" % "0.1.0",
      "io.github.okay-effects" %%% "okay-ui" % "0.1.0",
      "org.scalameta" %%% "munit" % "1.2.3" % Test),
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("okay.allowance.page.InThePage"))

/** THE PAGE: `sbt web` — index.html, the script (the linked modules and the npm ones they import, bundled by esbuild,
 * named by its content), the compiled contracts and the owner's standing orders, in page/target/web */
val web = taskKey[File]("the owner's page in page/target/web")
web := {
  val base = (ThisBuild / baseDirectory).value
  val out = (page / target).value / "web"
  val linked = (page / Compile / fullLinkJS).value.data.publicModules.find(_.moduleID == "main").get.jsFileName
  val from = (page / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / linked
  IO.delete(out)
  IO.copyDirectory(base / "page" / "web", out)
  if (scala.sys.process.Process(Seq("npx", "esbuild", from.getPath, "--bundle", "--format=esm", "--minify",
    "--platform=browser", s"--outdir=$out", "--entry-names=main-[hash]"), base).! != 0) sys.error("esbuild failed")
  val script = (out * "main-*.js").get.head.getName
  IO.write(out / "index.html", IO.read(out / "index.html").replace("src=\"main.js\"", "src=\"" + script + "\""))
  IO.copyFile(base / "out" / "contracts.json", out / "contracts.json")
  IO.copyFile(base / "demo" / "bills.json", out / "bills.json")
  out
}

/** THE PAGE TESTED IN A BROWSER: `sbt pageTest` — the page built, then page/browser/suite.mjs run on it (Playwright's
 * chromium: PW_CHROMIUM, else the newest headless shell in Playwright's cache); fails on any scenario */
val pageTest = taskKey[Unit]("the owner's page tested in a browser")
pageTest := {
  val base = (ThisBuild / baseDirectory).value
  val out = web.value
  val cache = file(sys.props("user.home")) / "Library" / "Caches" / "ms-playwright"
  val shell = sys.env.get("PW_CHROMIUM").orElse(
    Option(cache.listFiles).toVector.flatten.filter(_.getName.startsWith("chromium_headless_shell-"))
      .sortBy(_.getName).lastOption
      .flatMap(d => Option(d.listFiles).toVector.flatten.map(x => x / "chrome-headless-shell").find(_.exists))
      .map(_.getPath))
  if (scala.sys.process.Process(Seq("node", "page/browser/suite.mjs", out.getPath), base,
    shell.toVector.map("PW_CHROMIUM" -> _): _*).! != 0) sys.error("pageTest: a scenario failed in the browser")
}

/** PUBLISHING THE PAGE: `sbt publishSite` — the page built by `web` from a CLEAN tree, pushed to this repository's
 * gh-pages branch (the built files only), served at https://okay-effects.github.io/okay-allowance/ */
val publishSite = taskKey[Unit]("build the owner's page and push it to the gh-pages branch")
publishSite := Def.taskDyn {
  import scala.sys.process._
  val base = (ThisBuild / baseDirectory).value
  val dirty = Process(Seq("git", "status", "--porcelain"), base).!!.trim
  if (dirty.nonEmpty)
    sys.error(s"publishSite: the working tree has changes not committed — commit or stash them first:\n$dirty")
  Def.task {
    val log = streams.value.log
    val out = web.value
    val sha = Process(Seq("git", "rev-parse", "--short", "HEAD"), base).!!.trim
    val remote = Process(Seq("git", "remote", "get-url", "origin"), base).!!.trim
    val repo = target.value / "gh-pages"
    def git(args: String*): Unit =
      if (Process("git" +: args, repo).! != 0) sys.error(s"publishSite: git ${args.mkString(" ")} failed")
    IO.delete(repo)
    if (Process(Seq("git", "clone", "--quiet", "--branch", "gh-pages", "--depth", "1", remote, repo.getPath)).! != 0) {
      // the first publication: an empty branch of its own
      IO.createDirectory(repo)
      git("init", "--quiet", "-b", "gh-pages"); git("remote", "add", "origin", remote)
    }
    IO.listFiles(repo).filterNot(_.getName == ".git").foreach(IO.delete)
    IO.copyDirectory(out, repo)
    IO.write(repo / ".nojekyll", "")
    git("add", "-A")
    if (Process(Seq("git", "diff", "--cached", "--quiet"), repo).! == 0) log.info("publishSite: nothing changed")
    else {
      git("commit", "--quiet", "-m", s"The owner's page at $sha")
      git("push", "--quiet", "origin", "gh-pages")
      log.info(s"publishSite: the page at $sha pushed to gh-pages")
    }
  }
}.value
