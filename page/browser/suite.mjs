// THE OWNER'S PAGE IN A BROWSER (specs/allowance.md §14): the page as a person uses it — the plan, the agent taking
// invoices in, the owner approving, unchecking, editing, signing, the agent paying in turn, the clock, the address
// book, the policies — each with what must be on the screen. `node page/browser/suite.mjs page/target/web` after
// `sbt web` (`sbt pageTest` does both); exits 1 on any failure. The browser: PW_CHROMIUM, else Playwright's own.
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.argv[2] || 'page/target/web');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json' };
const server = http.createServer((q, r) => {
  const file = path.join(root, decodeURIComponent(new URL(q.url, 'http://x').pathname));
  const f = fs.existsSync(file) && fs.statSync(file).isDirectory() ? path.join(file, 'index.html') : file;
  if (!f.startsWith(root) || !fs.existsSync(f)) { r.writeHead(404); r.end(); return; }
  r.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(r);
}).listen(0);
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
let failed = 0;
let step = ''; // the last action, named in a failure
async function scenario(name, run) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 1400 } });
  // the visit counter left out: a test is not a visit
  await page.route(/cloudflareinsights\.com/, r => r.fulfill({ status: 200, contentType: 'text/javascript', body: '' }));
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => m.type() === 'error' && errors.push(m.text()));
  try {
    await page.goto(base + '/');
    await page.getByRole('heading', { name: 'Bills to pay' }).waitFor({ timeout: 120000 });
    await run(page);
    if (errors.length) throw new Error('page errors: ' + errors.join(' | ').slice(0, 300));
    console.log(`ok    ${name}`);
  } catch (e) {
    failed++;
    console.log(`FAIL  ${name} (after: ${step}): ${String(e.message || e).split('\n')[0].slice(0, 300)}`);
    await page.screenshot({ path: path.join(root, '..', `failed-${name.replace(/\W+/g, '-')}.png`), fullPage: true });
  }
  await page.close();
}
// the text as read: the page's breaks in long words left out, its no-break spaces as spaces
const text = async p => (await p.innerText('body')).replace(/\u200b/g, '').replace(/\u00a0/g, ' ');
async function has(p, ...wanted) {
  // the screen after a press: wait until the first wanted text is there
  await p.getByText(wanted[0], { exact: false }).first().waitFor({ timeout: 60000 });
  // section titles are upper-cased by the page's style: compared without case
  const t = (await text(p)).toLowerCase();
  for (const w of wanted) if (!t.includes(w.toLowerCase())) throw new Error(`missing: ${w}`);
}
const click = (p, label) => (step = label, p).getByRole('button', { name: label, exact: true }).first().click();
// a row's own buttons: links named by the row and the action (#row-INT/2026-01/uncheck)
const row = (p, invoice, label) => (step = `${invoice} ${label}`, p).locator(`a[href="#row-${invoice}/${label.toLowerCase()}"]`).click();

await scenario('the plan: the agent proposes, the owner decides, the agent pays in turn, the owner signs', async p => {
  await has(p, 'INT/2026-01', 'CLOUD/2025-12', 'missed', 'The policies', '1000 tUSD8');
  // the agent takes an invoice in
  await click(p, 'The agent: take an invoice in');
  await p.getByLabel('Payee', { exact: true }).selectOption('Software licence');
  await p.getByLabel('Amount, tUSD8', { exact: true }).fill('20');
  await p.getByLabel('Invoice', { exact: true }).fill('SW-EXTRA');
  await p.getByLabel('For', { exact: true }).fill('one more seat');
  await click(p, 'Save');
  await has(p, 'SW-EXTRA proposed by the agent', '[?]');
  // the owner: approves it, not the licences this time
  await row(p, 'SW-EXTRA', 'Approve');
  await has(p, 'SW-EXTRA approved');
  await row(p, 'SW-2026-01', 'Uncheck');
  await has(p, 'SW-2026-01 unchecked');
  // the owner changes the extra seat to 25 before it is paid
  await row(p, 'SW-EXTRA', 'Edit');
  await p.getByLabel('Amount, tUSD8', { exact: true }).fill('25');
  await click(p, 'Save');
  await has(p, 'SW-EXTRA changed');
  // the agent pays what is due: the internet paid, the electricity above the threshold waits for the signature
  await click(p, 'The agent: pay what is due');
  await has(p, 'INT/2026-01: paid 49.99 tUSD8', 'POWER/2026-01: 140 tUSD8',
    'SW-EXTRA: paid 25 tUSD8 to Software licence on ethereum');
  if ((await text(p)).includes('SW-2026-01: paid')) throw new Error('an unchecked payment was paid');
  await row(p, 'POWER/2026-01', 'Sign');
  await has(p, 'POWER/2026-01 signed on ethereum and paid', 'POWER/2026-02');
  await click(p, 'A week later');
  await has(p, '7 day(s) later', 'Today is 2026-01-08');
  // the licences' window is open now, but unchecked: the agent leaves them
  await click(p, 'The agent: pay what is due');
  await has(p, 'The agent found nothing due today');
});

await scenario('an invoice naming another address: listed as refused, nothing to approve', async p => {
  await click(p, 'The agent: take an invoice in');
  await p.getByLabel('Payee', { exact: true }).selectOption('Cloud hosting');
  await p.getByLabel('Amount, tUSD8', { exact: true }).fill('10');
  await p.getByLabel('Invoice', { exact: true }).fill('URGENT');
  await p.getByLabel('Address on the invoice', { exact: true }).fill('0xdead');
  await click(p, 'Save');
  await has(p, 'URGENT listed as refused', 'invoice fraud');
  if (await p.locator('a[href^="#row-URGENT/"]').count() !== 0)
    throw new Error('a refused invoice has buttons');
});

await scenario('the address book and the policies', async p => {
  await click(p, 'Address book');
  await has(p, 'Gym', 'not allowed', 'addr1');
  await click(p, 'Back');
  await click(p, 'Policies and payees');
  await has(p, 'cardano', 'Your signature above', 'Cap a period');
  await click(p, 'Back');
  await has(p, 'Bills to pay');
});

await browser.close();
server.close();
if (failed) { console.log(`${failed} scenario(s) failed`); process.exit(1); }
