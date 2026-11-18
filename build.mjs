// THE CONTRACTS COMPILED (specs/allowance.md §4): solc-js (Solidity's compiler as WebAssembly), the optimizer on;
// out/contracts.json holds each contract's ABI and bytecode for the tests, the agent and the page. `node build.mjs`
import solc from 'solc';
import fs from 'node:fs';
import path from 'node:path';

const here = path.dirname(new URL(import.meta.url).pathname);
const read = p => fs.readFileSync(path.join(here, 'contracts', p), 'utf8');
const files = ['SpendPolicy.sol', 'test/TestUSD.sol'];
const input = {
  language: 'Solidity',
  sources: Object.fromEntries(files.map(f => [f, { content: read(f) }])),
  settings: { optimizer: { enabled: true, runs: 200 }, evmVersion: 'cancun',
    outputSelection: { '*': { '*': ['abi', 'evm.bytecode.object'] } } }
};
const out = JSON.parse(solc.compile(JSON.stringify(input)));
for (const e of out.errors || []) console.error(e.formattedMessage);
if ((out.errors || []).some(e => e.severity === 'error')) process.exit(1);
const contracts = {};
for (const file of Object.values(out.contracts))
  for (const [name, c] of Object.entries(file))
    contracts[name] = { abi: c.abi, bytecode: '0x' + c.evm.bytecode.object };
fs.mkdirSync(path.join(here, 'out'), { recursive: true });
fs.writeFileSync(path.join(here, 'out', 'contracts.json'),
  JSON.stringify({ compiler: solc.version(), contracts }, null, 1));
console.log('compiled', solc.version(), Object.keys(contracts).join(', '));
