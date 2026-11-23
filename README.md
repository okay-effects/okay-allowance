# okay-allowance

An AI agent that pays bills in a stablecoin by itself — its hands tied by a contract. The owner puts money on a
policy, a prepaid card on the chain; the agent pays only from it: only to payees the owner allowed, within limits
per payment, per payee and per period, with a least time between payments to a payee, each invoice once, larger
payments waiting for the owner's signature. The contract checks every payment; whatever the agent decides — or a
model reading an invoice, or anyone who stole the agent's key — the most it can ever lose is the balance on the
policy, within the owner's rules.

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
  `pay_due`, `check_payment`, …), which holds the agent's keys and cannot approve its own proposals, and the
  owner's (`approve`, `uncheck`, `edit`, `remove`, …), which needs no key. See [docs/mcp.md](docs/mcp.md).

The design, decision by decision: [specs/allowance.md](specs/allowance.md).

## Run

Node 20+, sbt, JDK 17+.

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

## Status

Built for the Aevum Builder Challenge (a tUSD8 payment flow), independent of any chain's starter kit. Not audited:
test networks and test tokens only.

Apache-2.0.

---

Built on [okay-effects](https://okay-effects.github.io/) · Need more? [okay-effects.github.io/#author](https://okay-effects.github.io/#author)
