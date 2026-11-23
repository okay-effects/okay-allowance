# SPRINT — okay-allowance

- [x] **contract** — DONE 2026-11-18: SpendPolicy.sol (money stays in the owner's wallet, an allowance; one door `pay`; payee list with caps and gaps, maxPayment, periodCap, invoice once, requests above approvalAbove, pause, expiry), life.mjs (14 steps), test.mjs: 35 checks + 80 random payments agree with the model, on @ethereumjs/vm.
- [x] **agent** — DONE 2026-11-18: agent.mjs — its own judgement (payee from the address book, never the invoice; a different address = invoice fraud), a trial call (no transaction), the payment, the events read back, every refusal in words; two ledgers (memory: tests/demo/page; RPC via viem: a testnet — NOT YET RUN on a real node). `node agent.mjs demo`: 10 invoices — 3 paid, 1 waiting, 2 refused by the agent, 4 by the contract; 15 agent checks in test.mjs.
- [x] **scala** — DONE 2026-11-18 (§5): all but the contract in Scala 3 / Scala.js on okay-scala effects; same results as the JS reference (removed).
- [x] **multichain** — DONE 2026-11-18: Book (payee → chain → address), Ledger(chain, …), `Ledger.on(nodes)` routing by chain (Node: MemoryNode, Rpc; a missing node refused as NoSuchChain), the chain chosen per invoice; the demo on two EVM chains in memory at once (12 invoices), each chain's books its own; CLI `run CONFIG DIR` with several chains. Was: one agent, several chains (§6): the address book by chain, Ledger(chain, …), handlers routed
  by chain, the chain chosen per invoice; tests with two chains in memory at once.
- [x] **prepaid** — the money on the contract on every chain (§8.1): SpendPolicy.sol holds the token, deposit,
  withdraw, close; the tests and the demo from it. DONE on EVM: pay moves the contract's own balance
  (InsufficientBalance(balance, amount) past it); deposit by anyone, withdraw/close owner only, close pauses and
  drops the agent; the owner's wallet is never touched by a payment (tested). 9/9 suites. Cardano follows the same.
- [x] **mcp** — DONE 2026-11-18: McpServer (Mcp.scala), `main.js mcp demo|CONFIG`, docs/mcp.md (Claude Desktop/Code);
  McpSuite in JSON-RPC lines (paid, AlreadyPaid, address-mismatch, waiting on Cardano, requests, status); checked
  over real stdio from another directory (Files.home). Was: the MCP server (§10): tools check_payment, pay, status, requests over the agent (Agent.on, decide,
  Node.trial) on okay-mcp 0.1.0 (sjs1) + okay-effects-async 0.1.1 — `Servers.dispatch[Async]` per stdin line to a
  Future; Node.status (EVM views / Cardano datum); `main.js mcp demo|CONFIG`; McpSuite drives JSON lines.
- [x] **rules** — DONE 2026-11-18 (§11): the EVM contract, the Cardano script and the model agree on 120 random
  payments, reason for reason; Cardano's invoices in an MPF root (forever, 32 bytes; script 7.2 KB); PolicySpec 24.
  Was: (§11) Rules + Judge (Rules.scala), Life and CardanoWorld from Rules, Cardano checks in the EVM's
  order, RulesSuite (EVM = model, Cardano = model, 120 random). NOW: Cardano invoice memory as an MPF root (§9):
  PolicyState.invoices (root) instead of paid/paidBefore; Pay(payee, amount, invoice, proof); script
  `MerklePatriciaForestry(st.invoices).insert(invoice, empty, proof).root == next.invoices`; off-chain
  scalus.crypto.trie.MerklePatriciaForestry in CardanoWorld (proveNonMembership; AlreadyPaid when a member);
  PolicySpec + CardanoSuite + RulesSuite (then the chains agree outright).
- [x] **cardano** — DONE 2026-11-18: validator 23 checks (cardano/contracts PolicySpec), 5.5 KB with error names;
  CardanoWorld on the emulator: a life (CardanoSuite) and the agent on three chains (15 invoices, Cardano: paid,
  waiting for the owner, refused PayeeNotAllowed). Not yet: a real Cardano node (Blockfrost/Koios via
  okay-cardano) — BACKLOG until a testnet is needed. Was: the policy as a Scalus validator (§9): (1) cardano/contracts — SpendPolicy validator + ScalusTest
  spec of every refusal (template: ~/work/my/okay-invest/contracts StrategyVault + VaultSpec); (2) plutus.json →
  generated PolicyCode.scala (as okay-invest scripts/vault-report.scala vaultCode, but scala-cli in this repo);
  (3) off-chain in the Scala.js build: types, TxBuilder create/deposit/pay/close, a CardanoNode on the Emulator
  (template: okay-invest cardano/Vault.scala, VaultSim.scala); (4) the demo with a third chain "cardano".
- [x] **mcp-bills** — DONE 2026-11-18: address_book (each chain, allowed + cap) and bills (windows, states, the
  contract's earliest date), checked over stdio; McpSuite 2 tests. Was: (§12) MCP tools `address_book` and `bills` (payment windows from–to, state paid/waiting/
  due/upcoming/overdue, earliest by the contract); Bill + demo/bills.json + config "bills"; Node.paid(invoice);
  McpServer(bills, today); McpSuite; docs/mcp.md.
- [x] **plan** — DONE 2026-11-18: Plan.scala (data + MemoryPlan/FilePlan), Planner (intake, payDue, refresh),
  McpServer roles Agent/Owner/Demo; PlanSuite 5 + McpSuite 2 (the whole flow incl. the owner signing on chain);
  checked over stdio. Not run: intake/pay-due/mcp agent|owner on real nodes (no testnet yet). Was: (§13) agent proposes, owner reviews (approve, check/uncheck, edit, remove), agent pays due in
  turn; monthly series roll on; Plan.scala (pure ops + PlanStore memory/file), Agent.payDue, McpServer roles
  agent/owner/demo, tools add_invoice, plan, pay_due, approve, check, uncheck, edit, remove; CLI intake, pay-due;
  McpSuite rewritten for the flow; docs/mcp.md.
- [ ] **solana** — later, only for a reason: Rust/Anchor, an SPL delegate as the allowance.
- [~] **page** — (§14) the owner's page on okay-ui, the demo world in the page. Steps: (1) sbt modules core / root /
  page; (2) Office (shared by MCP and the page); (3) Desk effect + OwnerPage screens + demo handler; (4) web
  (esbuild, index.html footer+beacon) + Playwright test; (5) publishSite to gh-pages, Pages on; site section links
  the page. Later: through a wallet on a testnet (§4.4).
- [ ] **testnet** — deploy on the hackathon's chain (Aevum, QDay) from its public docs; never run its starter kit
  here — only in an isolated environment, if at all (§4.5).
- [ ] **submission** — public repository, docs (problem, architecture, flows, technologies), the demo, a video.
