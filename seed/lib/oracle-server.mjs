#!/usr/bin/env node
// Oracle server for the Java oracle harness (task 4.2, design.md decision 5; usage in docs/oracle.md).
//
// Loads the prototype with lib/load-prototype.mjs and answers one JSON request per stdin line with
// one JSON response per stdout line:
//
//   {"id": 1, "op": "call",    "name": "index", "args": [{"$js": "person('ps')"}]}
//   {"id": 2, "op": "eval",    "expr": "S.rules.weights.delivery = 40"}
//   {"id": 3, "op": "resolve", "args": [{"$js": "person('ps')"}, 4]}   // the args as JSON, for the port
//   {"id": 4, "op": "reset"}                                             // reload: undo every eval
//
//   -> {"id": 1, "ok": true, "value": 3.9241607142857147}
//   -> {"id": 1, "ok": false, "error": "TypeError: ..."}
//
// An argument {"$js": "<expr>"} is evaluated inside the prototype closure (so it can name PEOPLE,
// person('ps'), S.rules ...); any other argument is passed as plain JSON. NaN, Infinity and undefined
// survive the trip as {"$nonFinite": "NaN" | "Infinity" | "-Infinity"} and {"$undefined": true} so
// the harness can fail on them instead of seeing null. Run with TZ=Asia/Kolkata, like the prototype.
import { createInterface } from 'node:readline';
import { loadPrototype } from './load-prototype.mjs';

let proto = loadPrototype();

function resolveArgs(args) {
  return (args ?? []).map((a) => (a && typeof a === 'object' && !Array.isArray(a) && typeof a.$js === 'string'
    ? proto.get(`(${a.$js})`)
    : a));
}

function encode(value) {
  return JSON.stringify(value, (_key, v) => {
    if (typeof v === 'number' && !Number.isFinite(v)) return { $nonFinite: String(v) };
    if (v === undefined) return { $undefined: true };
    if (typeof v === 'function') return { $function: v.name || 'anonymous' };
    return v;
  }) ?? JSON.stringify({ $undefined: true });
}

function handle(req) {
  switch (req.op) {
    case 'call': {
      const fn = proto.get(req.name);
      if (typeof fn !== 'function') throw new Error(`${req.name} is not a function in app.js`);
      return fn(...resolveArgs(req.args));
    }
    case 'eval':
      return proto.get(req.expr);
    case 'resolve':
      return resolveArgs(req.args);
    case 'reset':
      proto = loadPrototype();
      return true;
    default:
      throw new Error(`Unknown op: ${req.op}`);
  }
}

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });

rl.on('line', (line) => {
  if (!line.trim()) return;
  let id = null;
  let out;
  try {
    const req = JSON.parse(line);
    id = req.id ?? null;
    out = `{"id":${JSON.stringify(id)},"ok":true,"value":${encode(handle(req))}}`;
  } catch (e) {
    out = JSON.stringify({ id, ok: false, error: String(e && e.stack ? e.stack.split('\n')[0] : e) });
  }
  process.stdout.write(`${out}\n`);
});

process.stdout.write('{"ready":true}\n');
