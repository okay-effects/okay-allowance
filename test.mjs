// THE AGENT'S ALLOWANCE, TESTED (specs/allowance.md §4) on the engines the page runs: the life step by step, each
// refusal for its own reason; the attacks the life does not show; random payments against a model of the rules.
// `node build.mjs && node test.mjs` — exits 1 on any failure.
import fs from 'node:fs';
import { life, DAY, invoice } from './life.mjs';
import { demo, decide, explain } from './agent.mjs';
import path from 'node:path';

const compiled = JSON.parse(fs.readFileSync(new URL('./out/contracts.json', import.meta.url), 'utf8'));
let failed = 0;
const check = (what, cond, detail = '') => {
  if (!cond) { failed++; console.log('FAIL', what, detail); } else console.log('ok  ', what);
};

const r = await life(compiled);
const want = { created: [true], internet: [true], cloud: [true], soon: [false, 'TooSoon'],
  twice: [false, 'AlreadyPaid'], injected: [false, 'PayeeNotAllowed'], request: [true], approved: [true],
  payeecap: [false, 'PayeeCap'], software: [true], periodcap: [false, 'PeriodCap'], selfallow: [false, 'NotOwner'],
  nextmonth: [true], paused: [false, 'Paused'] };
for (const s of r.steps) {
  const [ok, reason] = want[s.key];
  check(`life: ${s.key} ${ok ? 'accepted' : 'refused: ' + reason}`, s.ok === ok && (ok || s.reason === reason),
    JSON.stringify(s));
}
const at = k => r.steps.find(s => s.key === k);
check('life: a request moves no money; the owner\'s approval pays it', at('request').power === 0 &&
  at('approved').power === 140);
check('life: the attacker got nothing', r.steps.every(s => s.attacker === 0));

// THE ATTACKS on a fresh policy in the same chain
const { chain: c, token, usd, parties: { owner, agent, stranger, internet, cloud, attacker } } = r;
const deploy = (o = {}) => c.deploy(owner, 'SpendPolicy', [token, agent.address, o.period ?? 30n * BigInt(DAY),
  usd(o.periodCap ?? 400), usd(o.maxPayment ?? 300), usd(o.approvalAbove ?? 100),
  o.expires ?? c.now() + 365n * BigInt(DAY)]);
const p = await deploy();
await c.call(owner, p, 'SpendPolicy', 'setPayee', [cloud.address, true, usd(1_000), 0n]);
await c.call(owner, token, 'TestUSD', 'approve', [p, usd(250)]);
const pay = (who, payee, x, id, policy = p) =>
  c.call(who, policy, 'SpendPolicy', 'pay', [payee.address, usd(x), invoice(id)]);
let x = await pay(stranger, cloud, 10, 'A1');
check('a stranger\'s key cannot pay', !x.ok && x.reason === 'NotAgent', x.reason);
x = await pay(owner, cloud, 10, 'A2');
check('the owner\'s key is not the agent\'s door either', !x.ok && x.reason === 'NotAgent', x.reason);
x = await pay(agent, agent, 10, 'A3');
check('the agent cannot pay itself', !x.ok && x.reason === 'PayeeNotAllowed', x.reason);
x = await pay(agent, cloud, 0, 'A4');
check('a zero payment refused', !x.ok && x.reason === 'ZeroAmount', x.reason);
x = await pay(agent, cloud, 301, 'A5');
check('above maxPayment: refused, not even a request', !x.ok && x.reason === 'OverMax', x.reason);
for (const [fn, args] of [['setPayee', [attacker.address, true, usd(1e6), 0n]], ['setAgent', [attacker.address]],
  ['setLimits', [1n, usd(1e9), usd(1e9), usd(1e9), 10n ** 12n]], ['setPaused', [false]], ['approve', [0n]],
  ['reject', [0n]]]) {
  x = await c.call(agent, p, 'SpendPolicy', fn, args);
  check(`the agent cannot ${fn}`, !x.ok && x.reason === 'NotOwner', x.reason);
}
// requests: approve once, reject, then nothing more
x = await pay(agent, cloud, 150, 'R1');
check('above approvalAbove: a request', x.ok, x.reason);
x = await c.call(owner, p, 'SpendPolicy', 'reject', [0n]);
check('the owner rejects it', x.ok, x.reason);
x = await c.call(owner, p, 'SpendPolicy', 'approve', [0n]);
check('a rejected request cannot be approved', !x.ok && x.reason === 'NoSuchRequest', x.reason);
x = await c.call(owner, p, 'SpendPolicy', 'approve', [7n]);
check('a request that does not exist', !x.ok && x.reason === 'NoSuchRequest', x.reason);
// the allowance is the total: 250 approved; 90 + 90 paid; 90 more fails in the token, nothing moves
const before = await c.view(token, 'TestUSD', 'balanceOf', [owner.address]);
x = await pay(agent, cloud, 90, 'T1'); const y = await pay(agent, cloud, 90, 'T2');
const z = await pay(agent, cloud, 90, 'T3');
const after = await c.view(token, 'TestUSD', 'balanceOf', [owner.address]);
check('never more than the owner\'s allowance', x.ok && y.ok && !z.ok && before - after === usd(180),
  `${x.ok} ${y.ok} ${z.ok} ${before - after}`);
// expiry, a bad period
const short = await deploy({ expires: c.now() + 10n });
await c.call(owner, short, 'SpendPolicy', 'setPayee', [cloud.address, true, usd(1_000), 0n]);
c.advance(60);
x = await pay(agent, cloud, 10, 'E1', short);
check('after expiry: refused', !x.ok && x.reason === 'Expired', x.reason);
let bad = null;
try { await deploy({ period: 0n }); } catch (e) { bad = String(e.message); }
check('a zero period refused at birth', bad?.includes('BadPeriod'), bad);

// RANDOM PAYMENTS against a model of the rules: accepted exactly when the model says so
let seed = 11; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31;
const rp = await deploy({ periodCap: 300, maxPayment: 200, approvalAbove: 1_000 }); // no requests: paid or refused
const payees = [{ who: internet, cap: 80, gap: 5 }, { who: cloud, cap: 150, gap: 0 }, { who: attacker }];
for (const q of payees.filter(q => q.cap))
  await c.call(owner, rp, 'SpendPolicy', 'setPayee', [q.who.address, true, usd(q.cap), BigInt(q.gap * DAY)]);
await c.call(owner, token, 'TestUSD', 'approve', [rp, usd(1e6)]);
const PERIOD = 30 * DAY, startAt = Number(c.now());
const m = { spent: 0, period: -1, paid: new Set(), per: new Map() };
let agreed = 0; const n = 80;
for (let i = 0; i < n; i++) {
  c.advance(Math.floor(rnd() * 6 * DAY));
  const q = payees[Math.floor(rnd() * payees.length)];
  const amount = Math.round((1 + rnd() * 120) * 100) / 100;
  const id = rnd() < 0.15 && m.paid.size ? [...m.paid][Math.floor(rnd() * m.paid.size)] : `R-${i}`;
  const now = Number(c.now()), period = Math.floor((now - startAt) / PERIOD);
  const per = m.per.get(q.who.address) ?? { spent: 0, period: -1, last: 0 };
  const total = (m.period === period ? m.spent : 0) + amount;
  const mine = (per.period === period ? per.spent : 0) + amount;
  const model = !!q.cap && amount <= 200 && !m.paid.has(id) && (per.last === 0 || now >= per.last + q.gap * DAY) &&
    mine <= q.cap + 1e-9 && total <= 300 + 1e-9;
  x = await pay(agent, q.who, amount, id, rp);
  if (x.ok) {
    m.spent = total; m.period = period; m.paid.add(id);
    m.per.set(q.who.address, { spent: mine, period, last: now });
  }
  if (x.ok === model) agreed++; else console.log('  disagree', { i, payee: q.who.name, amount, id, model, x });
}
check(`random payments: the contract agreed with the model ${agreed}/${n}`, agreed === n);
// THE AGENT (agent.mjs) on the demo's invoices: its own refusals, the contract's, the payments, the request
const dir = path.join(path.dirname(new URL(import.meta.url).pathname), 'demo', 'invoices');
const invoices = fs.readdirSync(dir).filter(f => f.endsWith('.json')).sort()
  .map(f => JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')));
const d = await demo(compiled, invoices);
const got = Object.fromEntries(d.results.map((e, i) => [`${i + 1}:${e.invoice}`, e]));
const expect = { '1:INT-2026-11': ['paid'], '2:CLOUD-2026-11': ['paid'], '3:POWER-2026-11': ['waiting'],
  '4:URGENT-9921': ['refused', 'agent', 'address-mismatch'], '5:CI-77': ['refused', 'agent', 'unknown-payee'],
  '6:CLOUD-2026-11': ['refused', 'contract', 'AlreadyPaid'],
  '7:CLOUD-2026-11-EXTRA': ['refused', 'contract', 'PayeeCap'], '8:INT-2026-11-B': ['refused', 'contract', 'TooSoon'],
  '9:SW-2026-11': ['paid'], '10:GYM-2026-11': ['refused', 'contract', 'PayeeNotAllowed'] };
for (const [k, [status, by, reason]] of Object.entries(expect)) {
  const e = got[k];
  check(`agent: ${k} ${status}${reason ? ` (${by}: ${reason})` : ''}`,
    e && e.status === status && (!reason || (e.by === by && e.reason === reason)), JSON.stringify(e));
}
check('agent: every refusal said in words', d.results.filter(e => e.status === 'refused').every(e =>
  e.words && !/undefined|NaN/.test(e.words)));
check('agent: the payee is never taken from the invoice', decide({ payee: 'Cloud hosting', address: '0xdead',
  amount: '1' }, { 'Cloud hosting': '0x00000000000000000000000000000000000000c1' }).refuse === 'address-mismatch');
// a trial writes nothing: the owner's balance and the policy's books the same after it
const { chain: dc, policy: dp, parties: pp } = d;
const tokenOf = await dc.view(dp, 'SpendPolicy', 'token');
const was = await dc.view(tokenOf, 'TestUSD', 'balanceOf', [pp.owner.address]);
const spentWas = await dc.view(dp, 'SpendPolicy', 'spent');
const t = await dc.simulate(pp.agent, dp, 'SpendPolicy', 'pay', [pp.software.address, 5n * 10n ** 8n,
  invoice('TRIAL-ONLY')]);
check('agent: a trial passes and writes nothing', t.ok &&
  (await dc.view(tokenOf, 'TestUSD', 'balanceOf', [pp.owner.address])) === was &&
  (await dc.view(dp, 'SpendPolicy', 'spent')) === spentWas &&
  !(await dc.view(dp, 'SpendPolicy', 'paidInvoice', [invoice('TRIAL-ONLY')])));
check('agent: explanations name the contract\'s numbers', explain('PeriodCap', [41_499_000_000n, 40_000_000_000n])
  .includes('414.99 tUSD8') && explain('PeriodCap', [41_499_000_000n, 40_000_000_000n]).includes('400 tUSD8'));

console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
