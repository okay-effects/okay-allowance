package policy

import scalus.cardano.blueprint.{Blueprint, Contract}
import scalus.cardano.onchain.plutus.v3.TxOutRef
import scalus.compiler.Options
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.Data

/** the policy compiled, and its CIP-57 blueprint (`sbt blueprint`; pinned at ./plutus.json by `sbt blueprintPin`) */
object SpendPolicyContract extends Contract {
    // release, but the refusals keep their names: a transaction refused on-chain says which rule, as the EVM
    // contract's errors do (specs/allowance.md §9)
    private given Options = Options.release.copy(generateErrorTraces = true, removeTraces = false)
    lazy val compiled = PlutusV3.compile(SpendPolicy.validate)
    lazy val blueprint = Blueprint.plutusV3[TxOutRef, PolicyState, PolicyAction](
      title = "Spend policy",
      description = "An agent's allowance in a stablecoin: a budget at the script, the owner's rules on it.",
      version = "0.1.0",
      license = Some("Apache-2.0"),
      // the seed is applied as Data; the cast only relabels the phantom type (as okay-invest's vault)
      compiled = compiled.asInstanceOf[PlutusV3[TxOutRef => Data => Unit]])
}
