// SCREENSHOTS of the owner's page for a person to look at: the plan on a desktop and on a phone, after a proposal
// and a payment run. `node page/browser/shots.mjs page/target/web OUT-DIR` after `sbt web`.
import { chromium, devices } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.argv[2] || 'page/target/web');
const outDir = path.resolve(process.argv[3] || 'page/target');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json' };
const server = http.createServer((q, r) => {
  const f = path.join(root, new URL(q.url, 'http://x').pathname.replace(/\/$/, '/index.html'));
  if (!f.startsWith(root) || !fs.existsSync(f)) { r.writeHead(404); r.end(); return; }
  r.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(r);
}).listen(0);
const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
for (const [name, options] of [['desktop', { viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 }],
  ['phone', devices['iPhone 13']], ['site', { viewport: { width: 1000, height: 1250 } }]]) {
  const page = await browser.newPage(options);
  await page.route(/cloudflareinsights\.com/, r => r.fulfill({ status: 200, body: '' }));
  await page.goto(`http://localhost:${server.address().port}/`);
  await page.getByRole('heading', { name: 'Bills to pay' }).waitFor({ timeout: 120000 });
  await page.getByRole('button', { name: 'The agent: take an invoice in', exact: true }).click();
  await page.getByLabel('Payee', { exact: true }).selectOption('Software licence');
  await page.getByLabel('Amount, tUSD8', { exact: true }).fill('20');
  await page.getByLabel('Invoice', { exact: true }).fill('SW-EXTRA');
  await page.getByLabel('For', { exact: true }).fill('one more seat');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByText('SW-EXTRA proposed by').waitFor();
  await page.getByRole('button', { name: 'The agent: pay what is due', exact: true }).click();
  await page.getByText('INT/2026-01: paid').waitFor();
  // the site's picture: the first screenful, 1000 × 1250, as the other apps' on okay-effects.github.io
  await page.screenshot({ path: path.join(outDir, `page-${name}.png`), fullPage: name !== 'site' });
  await page.close();
}
await browser.close();
server.close();
