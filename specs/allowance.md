# okay-allowance — an agent's allowance, its hands tied (2026-11-18)

## 1. The problem

An AI agent that pays — subscriptions, invoices, small purchases — is useful only if the owner can bound it. A
prompt, a model's good behaviour or the agent's own code are not a bound: a bug, a prompt injection in an invoice,
or a stolen agent key would spend everything it can reach. The bound must be where the money is: a contract that
says what the agent's key may do, and refuses the rest whatever the agent believes.

## 2. The idea

The money STAYS IN THE OWNER'S WALLET. The owner gives the policy contract an ERC-20 allowance on the stablecoin
(the most the agent can ever spend in total; revoking it stops everything at once) and sets the rules. The agent has
one door, `pay(payee, amount, invoice)`; the contract pulls from the owner's wallet straight to the payee only when
every rule holds:
- the payee is one the owner allowed (a payee list, each with its own cap per period and least time between
  payments — "the internet provider: at most 120 a month, once a month");
- the payment is at most `maxPayment`; the period's total (all payees) at most `periodCap`;
- the invoice id was not paid before (no double payment of one bill);
- the policy is not paused and not expired; the caller is the agent's key.
A payment above `approvalAbove` is not paid: it becomes a REQUEST the owner approves or rejects (a human in the loop
for the large ones); approving pays it, still to an allowed payee, still within the caps. Every payment, request,
approval and refusal is an event: the log is the audit.

Only the owner sets the agent, the payees, the limits, pauses and resumes; the agent can change nothing about its
own bounds. A refused call reverts with the contract's own error (NotAgent, PayeeNotAllowed, OverMax, PayeeCap,
PeriodCap, TooSoon, AlreadyPaid, Paused, Expired), which the agent reads and reports.

## 3. What it guarantees, and what not

Whatever the agent's key does — even stolen — it can pay only allowed payees, only within the caps, only once per
invoice, never more in total than the owner's allowance. It cannot make a payee allowed nor a cap higher. It does NOT
judge whether a bill is right: an allowed payee billing within its cap is paid (that is what the caps are sized for)
— the log and the owner's review are the check. Its periods are fixed windows (a month from the policy's start), not
calendar months.

## 4. The parts

1. `contracts/SpendPolicy.sol` (Solidity 0.8, no dependencies); test tokens and a test payee in contracts/test.
2. Tests (src/test, munit on Scala.js in node): the life (the agent pays bills, a large one waits for the owner, refusals each for its own
   reason), the attacks (a stranger's key, the agent paying itself, a payee over its cap, a bill paid twice, after a
   pause, after expiry, the agent trying the owner's functions, more than the allowance), and random payments
   against a model of the rules.
3. The agent (src/…/Agent.scala): a program on two effects — Ledger (a trial, a payment) and Journal — whose
   handlers are the chain in memory (Chain.scala over @ethereumjs/vm) or a real node (Rpc.scala over viem); reads
   invoices (JSON), pays through the contract, explains each refusal; a model may read free-text invoices, the
   contract is what bounds it.
4. The page: the owner's rules, the payments, the requests to approve — in the browser's own EVM (demo), and on a
   testnet through a wallet.
5. A testnet deployment (the hackathon's chain: its RPC, chain id and test stablecoin from its public docs).

## 5. In Scala (2026-11-18)

The owner: "rewrite in Scala all you can". The contract stays Solidity (no Scala → EVM compiler); everything else is
Scala 3 on Scala.js, the same code in node (the command line, the tests) and in a browser (the page): Chain.scala
(the EVM in memory, a trial rolled back by a state checkpoint), Contracts.scala (solc-js; the same bytecode as before,
checked), Life.scala, Agent.scala (effects Ledger and Journal; handlers memory and rpc), Rpc.scala (viem), Main.scala
(contracts | demo | run). The JavaScript first version was the reference: the Scala one gives the same decisions, the
same transaction hashes, and passes the same checks (7 munit suites' tests: the life, 18 attacks, 80 random payments,
the agent's 10 invoices, its judgement, a trial writing nothing, the words). Transactions are run one after another
(`Life.inOrder`): a sender's nonce forbids side by side.

## 6. One agent, several chains (2026-11-18)

The owner: "this must not depend on a blockchain; one agent can work with several at once". The idea is chain-free:
the owner's rules are kept by the chain the money is on, not by the agent. So:
- THE ADDRESS BOOK names each payee's address ON EACH CHAIN it accepts (`Payee(name, Map(chain → address))`);
  the owner has a policy on each chain the money is on.
- THE LEDGER EFFECT carries its chain: `Trial(chain, to, amount, invoice)`, `Pay(chain, …)`; a handler answers for
  each chain (the chain in memory, a node over RPC, a Cardano emulator, …) and `Ledger.chains(handlers)` routes by it.
- THE AGENT chooses the chain per invoice: the invoice's chain if it names one (and the payee accepts it there),
  else the first of the owner's chains where the payee has an address; the trial decides — a refusal on one chain
  (the payee's cap there, say) is not retried on another (the caps are per chain on purpose: the owner sized them).
- ONE MODEL OF THE RULES in Scala (`Rules`: payees with caps and gaps, the period, its cap, the most a payment, the
  approval threshold, the expiry) from which each chain's policy is set up; the page shows it once.
- THE CONTRACTS, per chain: EVM — SpendPolicy.sol (the money stays in the owner's wallet, an ERC-20 allowance);
  CARDANO — a Scalus validator (Scala; the money a budget at the script's address — Cardano has no allowance over
  someone's wallet; the period's spending and each payee's in the datum; an invoice paid once kept compact; above
  the threshold the owner's signature); SOLANA — later, Rust/Anchor (an SPL token's delegate is the allowance), only
  for a reason (a client, a job).
The hackathon's submission is the EVM one (its chain is EVM); the others extend it.

## 7. What it is for

AI agents begin to act — book, buy, pay subscriptions and bills. To be useful an agent must reach money; today the
choices are bad: its own key or card (a bug, an injected invoice or a stolen key spends everything) or every payment
approved by hand (then the agent is useless). okay-allowance is the agent's allowance with rules the chain enforces,
not the agent: whom, how much at once, a month, per payee, how often, an invoice once, the large ones by the owner,
every step in a public log. Whatever the agent does — mistaken, deceived, broken into — it cannot leave those bounds;
the owner pauses or revokes at once. Who needs it: people letting an agent pay their bills; companies whose agents
buy cloud, APIs, data; services where agents pay each other. Money lives on several chains, so one agent with one
owner's rules pays on each, each chain's own contract keeping them there.

## 8. Decisions of 2026-11-18 (the owner)

1. THE MONEY ON THE CONTRACT, on every chain — a prepaid card: the owner tops the policy up from time to time; the
   agent pays only from what is there; the owner withdraws what is left or closes at any time. Clearer and safer
   than an allowance over the owner's wallet: the most that can ever be lost is the balance, visible at a glance;
   and it is the same on every chain (Cardano has no allowances: a budget at the script's address is its only way).
   SpendPolicy.sol changes: it holds the token; `deposit` (anyone may top it up), `withdraw` and `close` (the owner),
   `pay` transfers from its own balance. Every rule as before.
2. THE AGENT'S MODEL: the agent is a deterministic program; a language model, if any, only reads free-text invoices
   into the invoice's fields — any model (Claude, a local one through okay-llm). It is not trusted: the contract
   bounds whatever it decides.
3. AN MCP SERVER as the other way in: the policy's door offered as MCP tools — `check_payment` (a trial), `pay`,
   `status` (the balance, the period's spending, the payees and their caps), `requests` — so any AI agent (Claude
   Desktop, Claude Code, Cursor …) pays through it; the agent's key lives in the server, never with the model, and
   the contract bounds every call. In Scala, on okay-mcp; okay-guard's policy may sit in front of it.

## 9. On Cardano (2026-11-18)

No allowances on Cardano: the budget is a UTxO at the policy's script address, carrying the policy's NFT (minted
once, from a seed, as okay-invest's vault) and the state as its inline datum. Scalus 1.4.0, a build of its own in
`cardano/contracts/` (Scala 3.9, the compiler plugin); the off-chain side in the Scala.js build on
scalus-cardano-ledger (the ledger emulator runs in node and in a page), the same types field by field.

The datum: owner, agent (key hashes), the token (an asset class), period (ms), start, periodCap, maxPayment,
approvalAbove, expires, paused; this window's index and spending; the payees — credential, cap, gap, this window's
spending, the last payment's time; the invoices ever paid, as the 32-byte root of a Merkle Patricia Forestry (MPF).

Three ways to spend it:
- PAY (the agent signs): the payee's credential, the amount, the invoice. The same checks as SpendPolicy.sol with
  the same names as errors (NotAgent, Paused, Expired, PayeeNotAllowed, ZeroAmount, OverMax, PayeeCap, PeriodCap,
  TooSoon, AlreadyPaid, InsufficientBalance) — the agent's words for them work on both. Time from the validity
  interval (at most an hour; its start is "now", its end before `expires`). Above approvalAbove the owner signs too
  (`NeedsOwner`) — on Cardano a request is a transaction waiting for the owner's signature, not a record. The one
  output back carries exactly the next state (compared as data) and the balance less the amount; the payee's outputs
  get at least the amount.
- DEPOSIT (anyone): the same state, more of the token, no less ada.
- OWNER (the owner signs): anything — the rules, withdraw, pause; closing burns the NFT.

Invoices once, forever, in 32 bytes (§11 changed this): the datum holds the root of a Merkle Patricia Forestry of
the invoices paid (Scalus's on-chain MPF, Aiken-compatible); Pay carries a proof that its invoice is not in it, and
the next state's root is the old one with the invoice inserted — the script checks both by the proof. The trie
itself lives off-chain with the payer (rebuilt from the chain's Pay redeemers if lost). Was: the invoices of this
window and the one before, then forgotten — the rules test (§11) showed old invoices paid again on Cardano (3 in
120 random payments), so the memory became the EVM's.

## 10. The MCP server (2026-11-18)

The other way in (§8.3): the policy's door as MCP tools, so any AI agent — Claude Desktop, Claude Code, Cursor —
pays bills through it. The server holds the agent's keys (one a chain) and the owner's address book; the model
holds neither. Every call goes through the same judgement as the agent's (the payee by name from the book, never
an address from the model; an address it names must match the book's) and then through the contract.

Tools:
- `check_payment` {payee, amount, invoice, chain?, address?} — the judgement and a TRIAL on the chain; nothing
  written: would it be paid, wait for the owner, or be refused — and why, in words.
- `pay` {same} — one invoice through the agent (Agent.on, the same program): paid, waiting (a request for the
  owner) or refused, in words, with the chain and the transaction.
- `status` {chain?} — each chain's policy: its balance, this period's spending and cap, the most a payment, the
  approval threshold, paused, the end; each payee of the book there: its cap, this period's spending, when next.
- `requests` — the payments this server asked the owner to approve, still waiting.

On okay-mcp (okay-simple effects): `Servers.dispatch` on the machine with the async module — each line from stdin
one dispatch run to a Future, the tools' chain calls `fromFuture`; stdout only for the protocol, logs to stderr.
`main.js mcp demo` serves the three chains in memory (the demo's book); `main.js mcp CONFIG.json` real nodes, the
config as `run`'s. Each chain's node gains `status` (EVM: the contract's views; Cardano: the datum).

## 11. One set of rules (2026-11-18)

`Rules` (Scala): the period, the caps, the most a payment, the approval threshold, the life, the payees — the
policy on every chain is set up from it (Life on the EVM, CardanoWorld on Cardano). `Judge` is the rules as a
model, in SpendPolicy.sol's order of checks; the Cardano validator checks in the same order, so a payment breaking
two rules is refused for the same one on both. RulesSuite: 120 random payments on the EVM contract, on the Cardano
script and through the model — the three agree on every decision and reason. Its first run found the two-window
invoice memory on Cardano letting old invoices be paid again; §9 now keeps them forever in an MPF root.

## 12. The address book and the bills to pay, over MCP (2026-11-18, the owner)

Two more MCP tools:
- `address_book` — each payee of the owner's book: its address on each chain, and whether that chain's policy
  allows it (with its cap a period).
- `bills` {state?} — THE PAYMENTS TO MAKE: the owner's list of bills, each with its payee, amount, invoice, chain
  (optional), what it is for and its payment window — from and to (dates). Each shown with its state: paid (the
  chain's own record: EVM `paidInvoice`, Cardano the invoices' trie), waiting for the owner, due now (today within
  the window), upcoming (before it), overdue (after it, unpaid); and, when the policy's gap for the payee ends later
  than the window opens, the earliest date the contract allows. `state` filters ("due", "upcoming", "overdue",
  "paid", "waiting", "open" = not paid). Sorted by the window's start.

The bills come from the owner: `demo/bills.json` for `mcp demo`, `"bills": [...]` in a config. A bill:
`{ "invoice", "payee", "amount", "chain"?, "for"?, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }`. Today: the
chain's clock in the demo (2026-01-01 on), the computer's on real nodes. Each node gains `paid(invoice)`.
The window is shown, not enforced: `pay` of a bill outside it is judged as any invoice (BACKLOG: the agent refusing
outside the window).

## 13. The plan: the agent proposes, the owner reviews, the agent pays in turn (2026-11-18, the owner)

The agent no longer pays an invoice the moment it reads it. It works through a PLAN, data both sides share:

1. INTAKE (the agent): each invoice judged as before (the payee from the book, never the invoice's address; the
   chain chosen) and tried on its chain; added to the plan as PROPOSED with its window (from – to; an invoice
   without one: from today, to its due date or a week on) and the agent's note (e.g. "the contract would refuse it
   now: the payee's cap"). An invoice the agent refuses (fraud, unknown payee) is listed as refused, never payable.
   An invoice already in the plan is not added twice.
2. REVIEW (the owner): the plan listed — each payment with its checkbox; APPROVE (some or all), UNCHECK for this
   time / CHECK again, EDIT (amount, window, monthly or once), REMOVE (gone; a monthly one stops for good).
3. PAYMENT (the agent): `pay_due` pays, one after another, the payments approved, checked, not yet paid, whose
   window is open today — the earliest deadline first. Each through the agent's judgement and the contract, as
   before: paid (the transaction), waiting for the owner's signature (above the contract's threshold), not yet
   (TooSoon, a cap, the balance, paused: tried again on the next run while the window is open), refused (for good:
   not allowed, the agent's own refusal). AlreadyPaid on the chain is recorded as paid.
4. MONTHLY payments: a plan entry `every: month` comes again: when paid, or when its window ends unpaid (skipped:
   unchecked; missed: not approved or never possible), the next month's is added — approved as the standing order
   was, checked. Its invoice id: the series and the month (`INT/2026-02`), so the contract's once-only holds.

The plan is the owner's control above the contract: the contract still bounds every payment, whatever the plan
says. The roles are separate doors: `mcp agent` (check_payment, add_invoice, plan, pay_due, status, requests,
address_book) and `mcp owner` (plan, approve, check, uncheck, edit, remove, status, requests, address_book) — the
agent's door has no way to approve its own proposals; in a real setting the owner's server runs only in the
owner's client and needs no key. (The plan file is shared: a compromised agent HOST could write approvals into
it — the contract still bounds every payment; signed approvals are the fix, audit O1.)
`mcp demo` serves both in one process (three chains in memory). The plan is a JSON file for real
nodes (`"plan"` in the config, shared by the two servers), memory in the demo. Direct `pay` is gone from MCP.
The owner's standing orders (`demo/bills.json`, `"bills"` in a config) seed the plan, approved. `bills` (§12)
becomes `plan`. The CLI: `intake CONFIG DIR` adds a folder of invoices; `pay-due CONFIG` runs the payments (cron).

## 14. The owner's page (2026-11-23)

A page in the browser for the owner (and, in the demo, for playing the agent too), on okay-ui: the plan with each
payment's checkbox and its own buttons — approve, uncheck/check, edit, remove, and for a payment waiting on the
chain, sign it —, "approve all"; each chain's policy (balance, the period's spending and cap, the payees); the
address book. In the demo the agent's side is on the page as well, marked as the agent's: take an invoice in (a
form), pay what is due; and the demo's clock: a day, a week later. The whole demo world runs in the page: the EVM in
JavaScript, Scalus's Cardano emulator, the plan in memory — no network, no wallet, no money.

How:
- THE OFFICE: the plan's operations as data (`Office`: plan rows with their labels and the contract's earliest
  date, approve, check, edit, remove, intake, pay due, the requests, the status, the address book) — one class both
  the MCP server (which words them) and the page (which shows them) use.
- THE PAGE'S WORLD AS AN EFFECT: `Desk[X]` (Plan, Approve, Check, Edit, Remove, Intake, PayDue, Sign, Status,
  Book, Later(days), Today) — the page program `OwnerPage.main[R: Has[Ui]: Has[Desk]]` asks it; the demo's handler
  answers from an Office over the chains in memory (`Async`), a test's from the same. Its screens are okay-ui values.
- THE BUILD: the code shared by node and the page in a module `core` (no node imports); the command line, MCP over
  stdio and the plan file in the root (node); `page` (Scala.js, okay-ui) its own module. `sbt web` writes
  page/target/web (index.html with the footer and Cloudflare's beacon, the script bundled by esbuild, the compiled
  contracts); `sbt publishSite` pushes it to the repository's gh-pages branch, served at
  okay-effects.github.io/okay-allowance. A Playwright test drives the page in Chromium.

## 15. Beside Solana AI (2026-11-24)

On 2026-11-23 the Solana Foundation launched Solana AI (solana.ai): Payment Channels (funds put into a contract
with a spending limit, the agent authorising spends by signed off-chain messages, the sum settled on-chain in
batches, the rest returned; launch partner Alibaba Cloud; over a million messages a second inside channels in a test
of 100,000 wallets), pay.sh (an agent pays per call in USDC for services and data, HTTP 402), a Developer Platform
(wallets, payments, a spending policy such as a daily limit, set through its API), the Agent Kit, an agent
registry, a developers' MCP server, Milo (a trading agent) and Confidential Compute (testnet, not audited).

THE SAME CHOICE: money put on a contract with a limit, the agent spending only from it, the rest the owner's — §8.1.

DIFFERENT WORK: theirs is a machine paying machines — metered calls, data, compute, at a huge rate; ours is an
agent paying people's and companies' bills under the owner's rules. What okay-allowance has that the material
published does not describe: payees only from the owner's address book (an address on an invoice is never used;
another one is invoice fraud); every rule in the contract, not in a platform's API — per payment, per payee, per
period, the least time between payments to a payee, each invoice once, the owner's signature above a threshold;
the plan the owner reviews (approve, uncheck for this time, edit, remove; monthly bills come again); one agent on
EVM chains and Cardano under the same rules (§11); MCP as the payments' doors — the agent's, which cannot approve,
and the owner's, which needs no key (§13). What they have and it does not: real infrastructure, partners, a rate
of micropayments, pay-per-call, an agent registry, confidential compute; okay-allowance runs in memory only so far,
unaudited, one author.

THE TWO FIT TOGETHER: a channel for metered use, the policy for bills. Solana becomes a chain of okay-allowance
(SPRINT solana) — the reason the board asked for.
