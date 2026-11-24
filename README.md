# okay-allowance

An AI agent that pays bills in a stablecoin by itself — its hands tied by a contract. The owner puts money on a
policy, a prepaid card on the chain; the agent pays only from it: only to payees the owner allowed, within limits
per payment, per payee and per period, with a least time between payments to a payee, each invoice once, larger
payments waiting for the owner's signature. The contract checks every payment; whatever the agent decides — or a
model reading an invoice, or anyone who stole the agent's key — the most it can ever lose is the balance on the
policy, within the owner's rules.

**Try it: [the owner's page](https://okay-effects.github.io/okay-allowance/)** — three chains in your browser (Ethereum, Base,
Cardano), the agent's plan of bills to review: approve, uncheck, edit, remove, sign; play the agent too. No wallet,
no money.

One agent, several chains at once: the same rules as a Solidity contract on EVM chains and as a Scalus validator
on Cardano. On a random 120 payments the EVM contract, the Cardano script and a Scala model of the rules agree on
every decision and its reason.

## How it works

- **The policy** — `contracts/SpendPolicy.sol` (EVM) and `cardano/contracts` (Cardano, Scalus): the money on the
  contract; `pay` by the agent's key only; deposit by anyone; withdraw, close, pause, the rules by the owner's.
  On Cardano the state is the datum of the UTxO carrying the policy's NFT; the invoices ever paid, the root of a
  Merkle Patricia Forestry (32 bytes, however many).
- **The agent** — Scala 3 on Scala.js, a program on effects ([okay-scala](https://github.com/okay-effects/okay-scala)):
  the payee taken from the owner's address book, never from the invoice (another address there is refused as
  invoice fraud); the chain chosen; a trial of the payment, then the payment; every refusal said in words, with the
  contract's numbers.
- **The plan** — the agent takes invoices in and proposes them; the owner reviews: approves, unchecks for this
  time, edits, removes; the agent pays, one after another, what is approved and due in its window. Monthly
  payments come again each month.
- **MCP** — two doors for any AI client (Claude Desktop, Claude Code, Cursor …): the agent's (`add_invoice`,
  `pay_due`, `check_payment`, …), which holds the agent's keys and has no tool to approve its own proposals, and the
  owner's (`approve`, `uncheck`, `edit`, `remove`, …), which needs no key. See [docs/mcp.md](docs/mcp.md).
- **The owner's page** — the plan in the browser on [okay-ui](https://github.com/okay-effects/okay-ui): each payment
  with its checkbox and its own buttons, the policies, the address book. `sbt pageTest` drives it in Chromium;
  `sbt publishSite` publishes it.

The design, decision by decision: [specs/allowance.md](specs/allowance.md).

## Run

Node.js, sbt and a JDK.

```sh
npm install
sbt test                         # the contracts, the agent, the plan, MCP — on chains in memory
sbt fastLinkJS
node target/scala-3.3.8/okay-allowance-fastopt/main.js demo       # fifteen invoices on Ethereum, Base, Cardano
node target/scala-3.3.8/okay-allowance-fastopt/main.js mcp demo   # both MCP doors, chains in memory
```

The chains in the demo and the tests are in memory: the EVM in JavaScript (@ethereumjs/vm) and Scalus's Cardano
ledger emulator — real transactions, no network, no money; the demo's keys come from public names, never a wallet.
The Cardano validator: `cd cardano/contracts && sbt test` (Scala 3.9, Scalus 1.4).

On real nodes (`run`, `intake`, `pay-due`, `mcp agent|owner` with a config of RPC endpoints, the policies and key
files kept outside the repository) the EVM side is written but not yet run against a testnet; the Cardano side runs
on the emulator only.

## Beside Solana AI

On 2026-11-23 the Solana Foundation launched [Solana AI](https://solana.ai) for autonomous agents: Payment Channels
(funds put into a contract with a spending limit, spends authorised by signed messages off-chain, settled in
batches, the rest returned), pay.sh (pay per call in USDC), wallets with a spending policy in a platform API, the
Agent Kit, an agent registry.

The same choice at the heart: money on a contract with a limit, the agent spending only from it. Different work:

| | Solana AI | okay-allowance |
|---|---|---|
| Pays for | machines: metered calls, data, compute | bills: people and companies the owner pays |
| To whom | whoever asks for payment | only payees in the owner's address book; another address on an invoice is invoice fraud |
| Rules | a channel's limit; a daily limit in the platform's API | all in the contract: per payment, per payee, per period, a payee's least gap, each invoice once, the owner's signature above a threshold |
| The owner | approves a spend | reviews a plan: approve, uncheck for this time, edit, remove; monthly bills come again |
| Chains | Solana | EVM chains and Cardano at once, the same rules (Solana next) |
| MCP | for developers' IDEs | the payments' doors: the agent's (cannot approve), the owner's (no key) |

They have what this does not: real infrastructure, partners, micropayments at scale. The two fit together — a channel
for metered use, the policy for bills; Solana is to become a chain of okay-allowance
([specs/allowance.md §15](specs/allowance.md)).

## Status

Built for the Aevum Builder Challenge (a tUSD8 payment flow), independent of any chain's starter kit.

**Internal audit done, no external one yet** ([specs/audit.md](specs/audit.md)): both contracts read as an attacker,
Aderyn on the Solidity, okay-cardano's known Cardano exploits on the validator, invariants over random walks checked
against mutants of the contract. It found and fixed one HIGH hole (double satisfaction across Cardano policies) and
two MEDIUM ones (tokens that return no `bool`; tokens sent to the Cardano script by a plain transfer); two MEDIUM
items are open and listed. Test networks and test tokens only until an external audit.

Apache-2.0.

---

Built on [okay-effects](https://okay-effects.github.io/) · Need more? [okay-effects.github.io/#author](https://okay-effects.github.io/#author)
