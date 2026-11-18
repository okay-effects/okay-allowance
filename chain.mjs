// A CHAIN IN MEMORY (specs/allowance.md §4): @ethereumjs/vm — the Ethereum virtual machine in JavaScript, the same in
// node and in a page — with signed transactions from demo keys, a clock the demo moves, the contracts' calls and
// their own errors decoded by viem. No network, no money.
import { createVM, runTx } from '@ethereumjs/vm';
import { createLegacyTx } from '@ethereumjs/tx';
import { createBlock } from '@ethereumjs/block';
import { Common, Mainnet, Hardfork } from '@ethereumjs/common';
import { createAddressFromPrivateKey, createAccount, createAddressFromString, hexToBytes, bytesToHex }
  from '@ethereumjs/util';
import { encodeFunctionData, decodeFunctionResult, decodeErrorResult, encodeDeployData, keccak256, toHex } from 'viem';

export async function chain(compiled) {
  const common = new Common({ chain: Mainnet, hardfork: Hardfork.Cancun });
  const vm = await createVM({ common });
  let time = 1_767_225_600n; // 2026-01-01
  let number = 1n;
  const abiOf = name => compiled.contracts[name].abi;
  const block = () => createBlock({ header: { timestamp: time, number, gasLimit: 30_000_000n, baseFeePerGas: 7n } },
    { common, skipConsensusFormatValidation: true });

  /** a demo party: its key from its name (public: a demo, never a real wallet), 1000 ether for gas */
  async function party(name) {
    const key = hexToBytes(keccak256(toHex('okay-invest demo key: ' + name)));
    const address = createAddressFromPrivateKey(key);
    await vm.stateManager.putAccount(address, createAccount({ nonce: 0n, balance: 10n ** 21n }));
    return { name, key, address: address.toString() };
  }

  /** a signed transaction run in a new block: accepted, or reverted with the contract's own error */
  async function send(from, to, data, abi) {
    const account = await vm.stateManager.getAccount(createAddressFromString(from.address));
    const tx = createLegacyTx({ nonce: account.nonce, gasPrice: 10n ** 9n, gasLimit: 6_000_000n,
      to: to ? createAddressFromString(to) : undefined, data: hexToBytes(data) }, { common }).sign(from.key);
    const r = await runTx(vm, { tx, block: block(), skipBlockGasLimitValidation: true, skipHardForkValidation: true });
    number++;
    const ex = r.execResult;
    let reason = null;
    if (ex.exceptionError) {
      const ret = bytesToHex(ex.returnValue);
      try { reason = decodeErrorResult({ abi: [...abi, ...abiOf('SpendPolicy')], data: ret }).errorName; }
      catch { reason = ret === '0x' ? ex.exceptionError.error : 'reverted ' + ret.slice(0, 10); }
    }
    return { ok: !ex.exceptionError, reason, gas: Number(r.totalGasSpent), created: r.createdAddress?.toString() };
  }

  async function deploy(from, name, args = []) {
    const { abi, bytecode } = compiled.contracts[name];
    const r = await send(from, null, encodeDeployData({ abi, bytecode, args }), abi);
    if (!r.ok) throw new Error(`deploy ${name}: ${r.reason}`);
    return r.created;
  }
  const call = (from, to, name, fn, args = []) =>
    send(from, to, encodeFunctionData({ abi: abiOf(name), functionName: fn, args }), abiOf(name));
  async function view(to, name, fn, args = []) {
    const r = await vm.evm.runCall({ to: createAddressFromString(to), block: block(),
      data: hexToBytes(encodeFunctionData({ abi: abiOf(name), functionName: fn, args })) });
    if (r.execResult.exceptionError) throw new Error(`${fn}: ${r.execResult.exceptionError.error}`);
    return decodeFunctionResult({ abi: abiOf(name), functionName: fn, data: bytesToHex(r.execResult.returnValue) });
  }
  const encode = (name, fn, args) => encodeFunctionData({ abi: abiOf(name), functionName: fn, args });
  return { party, deploy, call, view, encode, advance: s => { time += BigInt(s); }, now: () => time };
}
