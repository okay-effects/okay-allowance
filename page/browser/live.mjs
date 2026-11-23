// THE PUBLISHED PAGE, as a visitor opens it: it starts (three chains set up), the plan is there, no page error.
// `node page/browser/live.mjs [URL]` (default the GitHub Pages address); exits 1 if not.
import { chromium } from 'playwright';
const url = process.argv[2] || 'https://okay-effects.github.io/okay-allowance/';
const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
const page = await browser.newPage();
await page.route(/cloudflareinsights\.com/, r => r.fulfill({ status: 200, contentType: 'text/javascript', body: '' }));
const errors = [];
page.on('pageerror', e => errors.push(String(e)));
const started = Date.now();
await page.goto(url);
await page.getByRole('heading', { name: 'Bills to pay' }).waitFor({ timeout: 120000 });
const text = (await page.innerText('body')).replace(/​/g, '');
await browser.close();
const ok = text.includes('INT/2026-01') && text.toLowerCase().includes('the policies') && errors.length === 0;
console.log(`${ok ? 'ok' : 'FAIL'}  ${url} started in ${((Date.now() - started) / 1000).toFixed(1)} s` +
  (errors.length ? ` — ${errors.join(' | ').slice(0, 300)}` : ''));
process.exit(ok ? 0 : 1);
