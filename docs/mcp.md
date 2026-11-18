# The policy's door over MCP

Any MCP client (Claude Desktop, Claude Code, Cursor …) can pay the owner's bills through okay-allowance. The server
holds the agent's keys and the owner's address book; the model never sees a key and cannot name an address the
book does not hold. Every payment is checked by the agent's judgement, then by the contract on its chain
(specs/allowance.md §10).

## Build

```sh
npm install
sbt fastLinkJS
```

## Run

- `node target/scala-3.3.8/okay-allowance-fastopt/main.js mcp demo` — the demo: Ethereum, Base and Cardano in
  memory (no network, no money), the demo's address book, 1 000 tUSD8 on each policy.
- `node target/scala-3.3.8/okay-allowance-fastopt/main.js mcp CONFIG.json` — real nodes; the config as for `run`
  (chains with their RPC, chain id, policy and the agent's key file; the book), kept outside the repository.

## Claude Desktop

In `claude_desktop_config.json` (Settings → Developer → Edit Config):

```json
{
  "mcpServers": {
    "okay-allowance": {
      "command": "node",
      "args": ["/path/to/okay-allowance/target/scala-3.3.8/okay-allowance-fastopt/main.js", "mcp", "demo"]
    }
  }
}
```

Claude Code: `claude mcp add okay-allowance -- node /path/to/okay-allowance/target/scala-3.3.8/okay-allowance-fastopt/main.js mcp demo`.

## Tools

| tool | what it does |
|---|---|
| `check_payment` | the agent's judgement and a trial on the chain; nothing written — would be paid, would wait for the owner, refused and why |
| `pay` | one invoice through the agent: paid, waiting for the owner's approval, or refused (by the agent or the contract), in words |
| `status` | each chain's policy: balance, this period's spending and cap, limits; each payee's cap, spending, next possible payment |
| `requests` | the payments waiting for the owner's approval |
| `address_book` | the owner's address book: each payee's address on each chain, allowed there or not, its cap |
| `bills` | the payments to make, each with its window (from – to): paid, waiting for the owner, due now, upcoming, overdue; the earliest date the contract allows; `state` filters (due, upcoming, overdue, paid, waiting, open) |

Arguments of `check_payment` and `pay`: `payee` (a name in the book), `amount` (tUSD8, e.g. `"49.99"`), `invoice`
(its id; each is paid once), and if the invoice has them, `chain`, `address` (checked against the book, never used)
and `for`.

The bills come from the owner: `demo/bills.json` in the demo (its clock starts on 2026-01-01), `"bills"` in a
config — `{ "invoice", "payee", "amount", "chain"?, "for"?, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }`.

Try: "Pay the internet bill INT-11, 49.99 tUSD8, to the Internet provider." — then the same again (refused: already
paid), then "What bills are due?", then "Pay electricity 140 on Cardano, invoice POWER-ADA" (waits for the owner), then "What is the status?".
