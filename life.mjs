// THE AGENT'S ALLOWANCE, A LIFE (specs/allowance.md §4): the owner's policy, the agent paying bills, the owner
// approving a large one, refusals each for its own reason — on a chain in memory, signed transactions from demo keys.
// The page runs it; so does `node test.mjs`.
import { chain } from './chain.mjs';
import { keccak256, toHex } from 'viem';

export const DAY = 86_400;
const usd = x => BigInt(Math.round(x * 100)) * 10n ** 6n; // 8 decimals, as tUSD8
export const invoice = id => keccak256(toHex(id));

export async function life(compiled) {
  const c = await chain(compiled);
  const owner = await c.party('owner'), agent = await c.party('agent'), stranger = await c.party('stranger');
  const internet = await c.party('internet provider'), cloud = await c.party('cloud hosting'),
    power = await c.party('electricity'), software = await c.party('software licence'),
    attacker = await c.party('attacker');
  const token = await c.deploy(owner, 'TestUSD', ['Test USD (8 decimals)', 'tUSD8', 8]);
  await c.call(owner, token, 'TestUSD', 'mint', [owner.address, usd(5_000)]);
  const steps = [];
  let policy = null;
  const bal = async who => Number(await c.view(token, 'TestUSD', 'balanceOf', [who.address])) / 1e8;
  const step = async (key, r) => {
    steps.push({ key, ok: r.ok, reason: r.reason, gas: r.gas, owner: await bal(owner), internet: await bal(internet),
      cloud: await bal(cloud), power: await bal(power), software: await bal(software), attacker: await bal(attacker) });
    return r;
  };
  const pay = (who, payee, x, id) => c.call(who, policy, 'SpendPolicy', 'pay', [payee.address, usd(x), invoice(id)]);

  // 1. the owner's policy: the agent's key; a 30-day period, at most 400 in it, at most 300 a payment, above 100
  //    the owner approves, for a year; the payees with their caps and gaps; an allowance of 1 000 in all
  policy = await c.deploy(owner, 'SpendPolicy', [token, agent.address, 30n * BigInt(DAY), usd(400), usd(300),
    usd(100), c.now() + 365n * BigInt(DAY)]);
  for (const [p, cap, gap] of [[internet, 60, 25], [cloud, 200, 0], [power, 150, 25], [software, 100, 0]])
    await c.call(owner, policy, 'SpendPolicy', 'setPayee', [p.address, true, usd(cap), BigInt(gap * DAY)]);
  await step('created', await c.call(owner, token, 'TestUSD', 'approve', [policy, usd(1_000)]));
  // 2. the internet bill, 49.99
  await step('internet', await pay(agent, internet, 49.99, 'INT-2026-11'));
  // 3. the cloud bill, 85
  await step('cloud', await pay(agent, cloud, 85, 'CLOUD-2026-11'));
  // 4. a second internet bill the same week: the payee's gap is 25 days
  await step('soon', await pay(agent, internet, 49.99, 'INT-2026-11-B'));
  // 5. the cloud bill again, the same invoice
  await step('twice', await pay(agent, cloud, 85, 'CLOUD-2026-11'));
  // 6. an invoice slipped into the agent's mail asks to pay an unknown address
  await step('injected', await pay(agent, attacker, 50, 'URGENT-9921'));
  // 7. the electricity bill, 140: above 100, a request for the owner
  await step('request', await pay(agent, power, 140, 'POWER-2026-11'));
  // 8. the owner approves it: paid
  await step('approved', await c.call(owner, policy, 'SpendPolicy', 'approve', [0n]));
  // 9. another cloud bill, 150: the cloud's cap is 200 a period (85 already)
  await step('payeecap', await pay(agent, cloud, 150, 'CLOUD-2026-11-EXTRA'));
  // 10. a software licence, 90; then 50 more for the cloud (within its cap): the period's 400 in all
  await step('software', await pay(agent, software, 90, 'SW-2026-11'));
  await step('periodcap', await pay(agent, cloud, 50, 'CLOUD-2026-11-B')); // 364.99 + 50 > 400
  // 11. the agent tries to allow the attacker itself
  await step('selfallow', await c.call(agent, policy, 'SpendPolicy', 'setPayee', [attacker.address, true, usd(1e6),
    0n]));
  // 12. a month later: the period's count starts again, the internet bill is paid
  c.advance(31 * DAY);
  await step('nextmonth', await pay(agent, internet, 49.99, 'INT-2026-12'));
  // 13. the owner pauses the agent
  await c.call(owner, policy, 'SpendPolicy', 'setPaused', [true]);
  await step('paused', await pay(agent, cloud, 20, 'CLOUD-2026-12'));
  return { compiler: compiled.compiler, steps, chain: c, policy, token, usd,
    parties: { owner, agent, stranger, internet, cloud, power, software, attacker } };
}
