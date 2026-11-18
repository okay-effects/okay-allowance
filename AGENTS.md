# Working in this repository (agents)

okay-allowance — an AI agent that pays bills in a stablecoin by itself, its hands tied by a contract: only to
payees the owner allowed, within limits per payment, per payee and per period, larger payments waiting for the
owner's approval, every decision logged on chain. Read specs/allowance.md first; the spec is changed before the code.

- The contract is Solidity (contracts/); everything else is Scala 3 on Scala.js (src/): the agent on okay-scala's
  effects (Ledger, Journal; handlers: the chain in memory, a real node over RPC), the chain in memory over
  @ethereumjs/vm, the contracts' build over solc-js, the tests (munit, in node). npm holds only the libraries.
- `sbt test`; `sbt fastLinkJS` then `node target/scala-3.3.8/okay-allowance-fastopt/main.js contracts|demo|run|intake|
  pay-due|mcp …` (docs/mcp.md: the plan and its two MCP doors).
- Cardano (specs/allowance.md §9): the validator in `cardano/contracts` (its own sbt build, Scala 3.9, Scalus):
  `sbt test blueprintPin` there, then `scala-cli run scripts/policy-code.scala` here regenerates PolicyCode.scala.
  `@noble/curves` stays 1.x at the top of node_modules (Scalus imports its 1.x paths; the EVM libraries nest 2.x).
- No third-party starter code is run on this machine (a hackathon's kit: only in isolation). Lines ≤ 120. Commit
  after each finished change. No secrets, no private keys in the repository: demo keys come from public names; a
  testnet key lives outside git. The board: SPRINT.md.
