# The policy's doors over MCP

The agent pays the owner's bills by a PLAN (specs/allowance.md §13): it takes invoices in and proposes them; the
owner reviews the plan — approves, unchecks for this time, edits, removes —; the agent pays, one after another,
what is approved, checked and due. Every payment goes through the agent's judgement (the payee from the owner's
address book, never an address from the invoice) and then through the contract on its chain, which bounds it
whatever the plan says. The model never sees a key.

Two doors, two MCP servers:
- **the agent's** (`mcp agent CONFIG`) — it holds the agent's keys; it cannot approve its own proposals;
- **the owner's** (`mcp owner CONFIG`) — no key needed (it only reads the chains); run it only in the owner's client.

They share the plan file (`"plan"` in the config). `mcp demo` serves both doors in one process, on Ethereum, Base
and Cardano in memory (no network, no money), the demo's clock starting on 2026-01-01.

## Build

```sh
npm install
sbt fastLinkJS
```

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

For real nodes: one entry with `"mcp", "agent", "/path/to/config.json"` where the agent runs, one with
`"mcp", "owner", "/path/to/config.json"` in the owner's own client. Claude Code:
`claude mcp add okay-allowance -- node /path/to/okay-allowance/target/scala-3.3.8/okay-allowance-fastopt/main.js mcp demo`.

## Tools

| door | tool | what it does |
|---|---|---|
| agent | `add_invoice` | takes an invoice into the plan: judged, tried on its chain, PROPOSED with its window (`from`, `to`; default today and a week) and `every` (once / month), with the agent's note; refused ones listed as refused |
| agent | `pay_due` | pays in turn what is approved, checked and due today, the earliest deadline first: paid, waiting for the owner's signature, not yet (tried again), refused |
| agent | `check_payment` | would this invoice be paid now? nothing written |
| owner | `approve` | `invoices: [...]` or `all: true` |
| owner | `uncheck` / `check` | not this time (a monthly one comes again next month) / again |
| owner | `edit` | `amount`, `from`, `to`, `every` of a payment not yet made |
| owner | `remove` | gone from the plan; a monthly one stops for good |
| both | `plan` | the plan: `[x]` approved, `[ ]` unchecked, `[?]` proposed; window, state, payee, amount, chain, note, the earliest date the contract allows; `state` filters |
| both | `status` | each chain's policy: balance, this period's spending and cap, limits; each payee's |
| both | `requests` | payments waiting for the owner's signature on the chain |
| both | `address_book` | each payee's address on each chain, allowed there or not, its cap |

The owner's standing orders seed the plan, approved: `demo/bills.json` in the demo, `"bills"` in a config —
`{ "invoice", "payee", "amount", "chain"?, "for"?, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD", "every"? }`; a monthly
one's payments are `INVOICE/YYYY-MM` and come again each month (when paid, or when the window ends unpaid).

Without MCP: `main.js intake CONFIG DIR` takes a folder of invoices into the plan; `main.js pay-due CONFIG` pays
what is due (for cron).

Try in the demo: "Show the plan." — "Add the invoice SW-EXTRA: 20 tUSD8 to the Software licence, one more seat, by
the end of January." — "Uncheck the summer house electricity this time." — "Approve SW-EXTRA." — "Pay what is due."
