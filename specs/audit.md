# okay-allowance — the internal audit (2026-11-24)

An internal audit, by the project's own author and agent: not an independent one. It looks for what an attacker —
a stranger, a compromised agent or its stolen key, a model misled by an invoice, another contract in the same
transaction — can make the policy do. An external audit comes before any real money; until then: test networks
and test tokens only.

## Scope

- `contracts/SpendPolicy.sol` (EVM), at the commit that closes this audit.
- `cardano/contracts/src/main/scala/policy/SpendPolicy.scala` (Cardano, Scalus), the same.
- The off-chain parts where they hold a guarantee: the agent's judgement (`Agent.decide`), the plan and its two MCP
  doors (`Plan`, `Planner`, `Office`, `McpServer`), the nodes.

## Method

1. A threat model: who may sign what, what each signature opens, what must never happen (money to a payee not
   allowed; more than a cap; an invoice twice; the owner's money moved by anyone but the owner).
2. Each contract read line by line as an attacker; each suspicion turned into a test that shows it before it is
   fixed (a fix without a failing test first is not counted).
3. Tools: [Aderyn](https://github.com/Cyfrin/aderyn) 0.6.8 (Cyfrin; built from the release's source, checksum
   verified) on the Solidity — its report in `specs/audit/aderyn-0.6.8.md`; okay-cardano's `Attacks` (known Cardano
   exploits tried on the validator's honest transactions) — `cardano/contracts/.../AttacksSpec.scala`.
4. Invariants over random walks: 150 steps of an honest agent, an attacker trying every door, strangers topping up,
   the clock — after each step, money only to allowed payees, the policy's balance as booked, no cap exceeded, no
   payment above the most, no invoice twice (`ContractSuite`, "AUDIT invariants"). The walk was checked against
   mutants of the contract: with the allowed-payee check and the payee cap removed, or the invoice-once check
   removed, it fails.
5. Agreement: the EVM contract, the Cardano script and a Scala model of the rules decide 120 random payments alike,
   reason for reason (`RulesSuite`; §11 of the spec).

## Findings

Severity: HIGH — money can go where the rules forbid; MEDIUM — money can be locked, or the rules bypassed under a
condition; LOW — a weakness with a narrow reach; INFO — by design, or a note.

| id | where | severity | finding | status |
|---|---|---|---|---|
| C1 | Cardano | HIGH | Double satisfaction across policies: two policies (two owners, one agent) in one transaction paying the same payee — one output counted by both, the second amount to the agent. | FIXED: the payee's output carries `Receipt(policy, invoice)`; only outputs naming this payment count. Test: the named policy paid, the other refused `NotPaid`. |
| E1 | EVM | MEDIUM | Token calls expected a `bool`: with a token that returns nothing (USDT on Ethereum) every transfer reverts — tokens sent straight to the policy could never leave. | FIXED: a low-level call, success = no revert and no data or `true`; a token address with no code refused at birth (`NotAToken`). Tests with a no-bool token. |
| C2 | Cardano | MEDIUM | Tokens sent to the policy's address by a plain transfer (not `Deposit`) were locked forever: every spend needed the NFT. | FIXED: a stray output may be spent beside the state; payments and deposits still allow one input of the policy, so only the owner's path sweeps it. Tests: the sweep, a stray alone refused, a payment taking one along refused. |
| C3 | Cardano | MEDIUM (liveness) | Anyone may `Deposit`: a griefer spending the state UTxO with tiny deposits, again and again, keeps the agent's payment from landing (each costs the griefer a fee and some token). No money lost. | OPEN. Options: deposits as separate outputs the owner merges (now possible, C2); a least deposit in the datum. |
| O1 | off-chain | MEDIUM | "The agent cannot approve its own proposals" holds at the MCP door only: the plan is a file both servers share, so a compromised agent HOST can write approvals into it. The contract still bounds every payment. | OPEN. Fix: the owner's approvals signed with the owner's key and checked by the agent, or the plan held on the owner's side. Spec §13 now says so. |
| E2 | EVM | INFO (tool) | Aderyn H-1: `close` changed state after reading the balance (a read, which cannot re-enter). | FIXED anyway: the door shut first. Pragma pinned to 0.8.30 (Aderyn L-6). |
| E3 | both | INFO (by design) | Fixed periods: a full period's cap just before a boundary and another just after — twice the cap within minutes. | KNOWN: the owner bounds it with a payee's gap or a smaller cap. Test shows it. A rolling window is the alternative. |
| E4 | both | INFO (inherent) | Invoice ids are the agent's: the same bill under two ids is two payments; a bill that does not exist, paid to an allowed payee, is a payment. | INHERENT: no contract can know a bill; bounded by the payee's cap and gap and the period's cap. The agent refuses ids already in its plan. |
| E5 | EVM | LOW | The owner is fixed for good: a lost owner key leaves the balance unwithdrawable (the agent can still pay within the rules until expiry). | OPEN: a two-step owner transfer, if wanted. On Cardano the owner is in the datum and the owner may change it. |
| E6 | EVM | LOW | A compromised agent may push requests without end (its own gas): noise for the owner, no money. | OPEN: a bound on waiting requests, if wanted. |
| E7 | EVM | INFO (deploy) | Solidity 0.8.30 targets an EVM with PUSH0 (Aderyn L-3): a chain without it rejects the bytecode. | AT DEPLOY: compile for the testnet's EVM version once it is known. |
| C4 | Cardano | INFO | A payment's time is the start of its validity window (at most an hour long): the agent may date it up to an hour back — the payee's gap is honoured to within an hour. | KNOWN. |
| C5 | Cardano | INFO (deploy) | The script is 7.4 KB with its error names: on a network, attach it as a reference script, not in each payment. | AT DEPLOY. |
| O2 | off-chain | INFO | The MCP servers speak stdio and authenticate no one: whoever runs the process is its role. | KNOWN: run the owner's door only in the owner's client. |
| O3 | off-chain | INFO | The demo's keys are derived from public names, the page's too. | DEMO ONLY: real nodes read the agent's key from a file outside the repository. |

What held, and how it was shown:

- A payee not allowed: refused twice over — the allowed-list check, and its cap of zero (the first mutant, with the
  allowed check alone removed, was still refused by the cap: defence in depth).
- The model cannot redirect money: the payee's address comes from the owner's book, an address on an invoice is
  checked against it and never used (`AgentSuite`, `McpSuite`: `address-mismatch`).
- Every known Cardano exploit the library knows — double satisfaction (same script), a redirected payment, a stake
  redirect, a stolen continuing output, token dust, a budget blown by dust, forged state, an extra mint — DEFENDED,
  each with the rule that stops it (AttacksSpec prints them). One REVIEW accepted: the payee's ada underpaid —
  harmless here: the policy owes the token, the ada is the ledger's minimum the agent adds.
- Aderyn's other notes: L-1 centralization (the owner's powers — by design), L-2 a struct's fields named as state
  variables (cosmetic), L-3 an address set without a zero check (the agent `0` means "no agent", by design),
  L-4 unsafe ERC-20 (a false positive: `tokenCall` is the safe pattern of E1).

## What this audit is not

Not independent, not formal: a person and an agent who wrote the code looked for its holes, with tools and tests.
It found one HIGH and two MEDIUM holes in a week-old code base — reason enough to expect more. Before real money:
an external audit (or an audit contest), the open items above settled, and a deployment checklist (E7, C5).
