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
