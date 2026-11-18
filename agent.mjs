// THE AGENT (specs/allowance.md §4.3): invoices in, payments out, every refusal explained. Its own judgement first
// (the payee looked up in the owner's address book, never taken from the invoice; a different address there is
// refused as invoice fraud), then a TRIAL of the payment (the contract asked without a transaction: would it pass,
// and why not), then the payment itself, read back from the contract's events (Paid, or Requested for the owner).
// The bound is the contract, not this program: whatever this code does, the policy refuses what is outside it.
//
// The decisions are separate from the chain: a LEDGER is either the chain in memory (chain.mjs: tests, the demo, the
// page) or a real node over RPC (viem: a testnet). `node agent.mjs demo` runs the demo; `node agent.mjs run …` a
// testnet (see usage below).
import fs from 'node:fs';
import path from 'node:path';
import { keccak256, toHex, formatUnits, parseUnits } from 'viem';

const DECIMALS = 8; // tUSD8

/** an invoice's id as the contract keeps it */
export const invoiceId = id => keccak256(toHex(id));

/** THE AGENT'S OWN JUDGEMENT, before any chain: the payee from the owner's address book by name; the invoice's own
 * address, if any, must be that one; the currency the policy's. Returns a payment or a refusal */
export function decide(invoice, book) {
  const name = String(invoice.payee ?? '').trim().toLowerCase();
  const known = Object.entries(book).find(([n]) => n.toLowerCase() === name);
  if (!known) return { refuse: 'unknown-payee', detail: invoice.payee };
  const [payee, address] = known;
  if (invoice.address && invoice.address.toLowerCase() !== address.toLowerCase())
    return { refuse: 'address-mismatch', detail: `${invoice.address} ≠ ${address}` };
  if (invoice.currency && !/^t?usd8?$/i.test(invoice.currency)) return { refuse: 'currency', detail: invoice.currency };
  const amount = parseUnits(String(invoice.amount), DECIMALS);
  if (amount <= 0n) return { refuse: 'amount', detail: String(invoice.amount) };
  return { payee, address, amount };
}

/** a refusal in words: the agent's own, or the contract's error with its arguments */
export function explain(reason, args = [], payeeName = '') {
  const money = x => `${formatUnits(BigInt(x), DECIMALS)} tUSD8`;
  const date = t => new Date(Number(t) * 1000).toISOString().slice(0, 10);
  switch (reason) {
    case 'unknown-payee': return 'the payee is not in the owner\'s address book';
    case 'address-mismatch': return 'the invoice names an address other than the address book\'s — possible invoice ' +
      'fraud; not paid';
    case 'currency': return 'the invoice is not in the policy\'s currency';
    case 'amount': return 'the invoice has no positive amount';
    case 'NotAgent': return 'this key is not the policy\'s agent';
    case 'Paused': return 'the owner has paused the agent';
    case 'Expired': return 'the policy has expired';
    case 'PayeeNotAllowed': return 'the payee is not on the policy\'s list of allowed payees';
    case 'OverMax': return `above the most one payment may be (${money(args[1])})`;
    case 'PayeeCap': return `${payeeName || 'this payee'} would get ${money(args[1])} this period; its cap is ` +
      money(args[2]);
    case 'PeriodCap': return `all payments this period would come to ${money(args[0])}; the cap is ${money(args[1])}`;
    case 'TooSoon': return `${payeeName || 'this payee'} was paid recently; the next payment is possible from ` +
      date(args[1]);
    case 'AlreadyPaid': return 'this invoice has already been paid';
    case 'ZeroAmount': return 'a payment of zero';
    default: return `refused by the contract (${reason}) — e.g. the owner's wallet or allowance does not cover it`;
  }
}

/** THE RUN: each invoice judged, tried, paid or refused; one entry per invoice in the journal */
export async function run({ ledger, invoices, book, journal = () => {} }) {
  const out = [];
  for (const inv of invoices) {
    const d = decide(inv, book);
    let entry;
    if (d.refuse) entry = { invoice: inv.id, status: 'refused', by: 'agent', reason: d.refuse,
      words: explain(d.refuse) + (d.detail ? ` (${d.detail})` : '') };
    else {
      const args = [d.address, d.amount, invoiceId(inv.id)];
      const trial = await ledger.trial(args);
      if (!trial.ok) entry = { invoice: inv.id, status: 'refused', by: 'contract', reason: trial.reason,
        words: explain(trial.reason, trial.args, d.payee) };
      else {
        const tx = await ledger.pay(args);
        const paid = tx.events.find(e => e.eventName === 'Paid');
        const asked = tx.events.find(e => e.eventName === 'Requested');
        entry = !tx.ok ? { invoice: inv.id, status: 'refused', by: 'contract', reason: tx.reason,
            words: explain(tx.reason, tx.args, d.payee), tx: tx.hash }
          : asked ? { invoice: inv.id, status: 'waiting', request: Number(asked.args.id), tx: tx.hash,
            words: `${formatUnits(d.amount, DECIMALS)} tUSD8 to ${d.payee}: above the approval threshold — waiting ` +
              `for the owner (request ${Number(asked.args.id)})` }
          : { invoice: inv.id, status: 'paid', tx: tx.hash, amount: formatUnits(paid.args.amount, DECIMALS),
            payee: d.payee, words: `paid ${formatUnits(paid.args.amount, DECIMALS)} tUSD8 to ${d.payee}` };
      }
    }
    journal(entry);
    out.push(entry);
  }
  return out;
}

/** A LEDGER ON THE CHAIN IN MEMORY (chain.mjs) — the tests', the demo's, the page's */
export const memoryLedger = (c, policy, agent) => ({
  trial: args => c.simulate(agent, policy, 'SpendPolicy', 'pay', args),
  pay: args => c.call(agent, policy, 'SpendPolicy', 'pay', args)
});

/** A LEDGER ON A REAL NODE (a testnet) over RPC, viem's clients; the agent's key from a file outside the repository */
export async function rpcLedger({ rpc, chainId, policy, keyFile, abi }) {
  const { createPublicClient, createWalletClient, http, parseEventLogs, BaseError, ContractFunctionRevertedError } =
    await import('viem');
  const { privateKeyToAccount } = await import('viem/accounts');
  const account = privateKeyToAccount(fs.readFileSync(keyFile, 'utf8').trim());
  const chain = { id: Number(chainId), name: 'testnet', nativeCurrency: { name: 'gas', symbol: 'GAS', decimals: 18 },
    rpcUrls: { default: { http: [rpc] } } };
  const pub = createPublicClient({ chain, transport: http(rpc) });
  const wallet = createWalletClient({ account, chain, transport: http(rpc) });
  const reverted = e => {
    const r = e instanceof BaseError ? e.walk(x => x instanceof ContractFunctionRevertedError) : null;
    return { ok: false, reason: r?.data?.errorName ?? 'reverted', args: r?.data?.args ?? [] };
  };
  return {
    trial: async args => {
      try {
        await pub.simulateContract({ account, address: policy, abi, functionName: 'pay', args });
        return { ok: true };
      }
      catch (e) { return reverted(e); }
    },
    pay: async args => {
      try {
        const hash = await wallet.writeContract({ address: policy, abi, functionName: 'pay', args });
        const receipt = await pub.waitForTransactionReceipt({ hash });
        return { ok: receipt.status === 'success', hash, events: parseEventLogs({ abi, logs: receipt.logs }),
          reason: receipt.status === 'success' ? null : 'reverted', args: [] };
      } catch (e) { return { ...reverted(e), events: [] }; }
    }
  };
}

/** THE DEMO: a policy on the chain in memory (as in life.mjs), the owner's address book, a folder of invoices */
export async function demo(compiled, invoices) {
  const { life } = await import('./life.mjs');
  const r = await life(compiled, { stopAfter: 'created' });
  const { chain: c, policy, parties: p } = r;
  const book = { 'Internet provider': p.internet.address, 'Cloud hosting': p.cloud.address,
    'Electricity': p.power.address, 'Software licence': p.software.address,
    'Gym': p.gym.address }; // in the book, not on the policy's list: the contract's to refuse
  const results = await run({ ledger: memoryLedger(c, policy, p.agent), invoices, book });
  return { results, book, chain: c, policy, parties: p };
}

const usage = `usage:
  node agent.mjs demo [invoices-dir]          the demo on the chain in memory (default: demo/invoices)
  node agent.mjs run --rpc URL --chain-id N --policy 0x… --key-file FILE --book book.json invoices-dir
                                              a real node; the key file outside the repository`;

if (import.meta.url === `file://${process.argv[1]}`) {
  const here = path.dirname(new URL(import.meta.url).pathname);
  const [cmd, ...rest] = process.argv.slice(2);
  const readDir = d => fs.readdirSync(d).filter(f => f.endsWith('.json')).sort()
    .map(f => JSON.parse(fs.readFileSync(path.join(d, f), 'utf8')));
  const compiled = JSON.parse(fs.readFileSync(path.join(here, 'out', 'contracts.json'), 'utf8'));
  const show = e => console.log(`${e.status.padEnd(8)} ${String(e.invoice).padEnd(22)} ${e.words}` +
    (e.tx ? `  [${e.tx.slice(0, 10)}…]` : ''));
  if (cmd === 'demo') {
    const { results } = await demo(compiled, readDir(rest[0] ?? path.join(here, 'demo', 'invoices')));
    results.forEach(show);
  } else if (cmd === 'run') {
    const opt = k => { const i = rest.indexOf(k); return i >= 0 ? rest[i + 1] : undefined; };
    const ledger = await rpcLedger({ rpc: opt('--rpc'), chainId: opt('--chain-id'), policy: opt('--policy'),
      keyFile: opt('--key-file'), abi: compiled.contracts.SpendPolicy.abi });
    const book = JSON.parse(fs.readFileSync(opt('--book'), 'utf8'));
    const journal = e =>
      fs.appendFileSync('journal.jsonl', JSON.stringify({ at: new Date().toISOString(), ...e }) + '\n');
    (await run({ ledger, invoices: readDir(rest[rest.length - 1]), book, journal })).forEach(show);
  } else console.log(usage);
}
