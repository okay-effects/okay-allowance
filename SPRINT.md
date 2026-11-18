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
- [ ] **mcp** — the policy's door as an MCP server (§8.3): check_payment, pay, status, requests; Scala on okay-mcp.
- [ ] **rules** — one Scala model of the owner's rules (§6), each chain's policy set up from it.
- [x] **cardano** — DONE 2026-11-18: validator 23 checks (cardano/contracts PolicySpec), 5.5 KB with error names;
  CardanoWorld on the emulator: a life (CardanoSuite) and the agent on three chains (15 invoices, Cardano: paid,
  waiting for the owner, refused PayeeNotAllowed). Not yet: a real Cardano node (Blockfrost/Koios via
  okay-cardano) — BACKLOG until a testnet is needed. Was: the policy as a Scalus validator (§9): (1) cardano/contracts — SpendPolicy validator + ScalusTest
  spec of every refusal (template: ~/work/my/okay-invest/contracts StrategyVault + VaultSpec); (2) plutus.json →
  generated PolicyCode.scala (as okay-invest scripts/vault-report.scala vaultCode, but scala-cli in this repo);
  (3) off-chain in the Scala.js build: types, TxBuilder create/deposit/pay/close, a CardanoNode on the Emulator
  (template: okay-invest cardano/Vault.scala, VaultSim.scala); (4) the demo with a third chain "cardano".
- [ ] **solana** — later, only for a reason: Rust/Anchor, an SPL delegate as the allowance.
- [ ] **page** — the owner's view in the browser's EVM, then through a wallet on a testnet (§4.4).
- [ ] **testnet** — deploy on the hackathon's chain (Aevum, QDay) from its public docs; never run its starter kit
  here — only in an isolated environment, if at all (§4.5).
- [ ] **submission** — public repository, docs (problem, architecture, flows, technologies), the demo, a video.
