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
spending, the last payment's time; the invoices paid in this window and the one before (32-byte ids).

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

Invoices once, compactly: kept for this window and the one before, then forgotten. The agent's own journal refuses
an older one; on-chain an invoice older than two periods could be paid again — bounded by the payee's cap and gap.
(SpendPolicy.sol keeps every id forever: storage on EVM is a map, on Cardano a datum that every payment rewrites.)

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
