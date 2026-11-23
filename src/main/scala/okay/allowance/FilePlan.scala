package okay.allowance

/** THE PLAN IN A JSON FILE (specs/allowance.md §13): real nodes — the agent's and the owner's servers share it; no
 * file yet: `seed` */
final class FilePlan(path: String, seed: => Plan) extends PlanStore:
  def load: Plan = if Files.exists(path) then Plan.parse(Files.read(path)) else seed
  def save(p: Plan): Unit = Files.write(path, p.json)
