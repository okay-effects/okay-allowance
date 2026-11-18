# Working in this repository (agents)

okay-allowance — an AI agent that pays bills in a stablecoin by itself, its hands tied by a contract: only to
payees the owner allowed, within limits per payment, per payee and per period, larger payments waiting for the
owner's approval, every decision logged on chain. Read specs/allowance.md first; the spec is changed before the code.

- Solidity contracts in contracts/, compiled by solc-js (build.mjs), tested on @ethereumjs/vm (test.mjs) — the same
  engines the page runs. No third-party starter code is run on this machine (a hackathon's kit: only in isolation).
- Lines ≤ 120. Commit after each finished change. No secrets, no private keys in the repository: demo keys are
  derived from public names; a testnet key lives outside git.
- The board: SPRINT.md.
