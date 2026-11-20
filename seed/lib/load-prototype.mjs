// Loads the prototype (reference/2-prototype/src/app.js) in a sandboxed Node vm context and gives
// read access to every binding inside its closure. Used by the seed extractor (task 2.2) and meant
// to be reused by the oracle harness (task 4.2).
//
// app.js is one IIFE that ends with `render();`. We replace that final call with an accessor,
// `globalThis.__pulse = name => eval(name)`, so callers can read datasets and call functions by
// name. The DOM is a do-nothing stub; Math.random and the clock are pinned so every run produces
// the same output.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(here, '..', '..');
export const APP_JS = path.join(REPO_ROOT, 'reference', '2-prototype', 'src', 'app.js');

/** The prototype's "now": 28 Sep 2026 15:30 IST (app.js line 4). */
export const PROTOTYPE_NOW = Date.parse('2026-09-28T15:30:00+05:30');

const BOOT_TAIL = /render\(\);\s*\}\)\(\);\s*$/;

/** Returns `get(name)`: evaluates `name` (an identifier or expression) inside the prototype closure. */
export function loadPrototype(file = APP_JS) {
  const source = readFileSync(file, 'utf8');
  if (!BOOT_TAIL.test(source)) {
    throw new Error(`${file} no longer ends with "render();})();" - update load-prototype.mjs`);
  }
  const patched = source.replace(BOOT_TAIL, 'globalThis.__pulse = function (n) { return eval(n); };\n})();\n');

  const context = vm.createContext(sandboxGlobals());
  vm.runInContext(patched, context, { filename: 'app.js', timeout: 30_000 });
  const get = context.__pulse;
  // Values created inside the vm belong to its realm; JSON round-trip makes them plain host values.
  return {
    get: (name) => get(name),
    json: (name) => {
      const value = get(name);
      return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
    },
  };
}

function sandboxGlobals() {
  const element = domStub();
  const document = new Proxy({
    body: { getAttribute: () => null, setAttribute() {}, classList: classListStub(), appendChild() {} },
    querySelector: () => element,
    querySelectorAll: () => [],
    getElementById: () => element,
    createElement: () => element,
    addEventListener() {},
    removeEventListener() {},
    documentElement: element,
  }, { get: (t, k) => (k in t ? t[k] : element) });

  const NativeDate = Date;
  class PinnedDate extends NativeDate {
    constructor(...args) {
      if (args.length === 0) super(PROTOTYPE_NOW); else super(...args);
    }
    static now() { return PROTOTYPE_NOW; }
  }

  const random = mulberry32(20260928);
  const math = Object.create(Math, { random: { value: random } });

  return {
    console,
    document,
    window: {},
    location: { hash: '', href: 'about:blank' },
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} }, userAgent: 'node' },
    setTimeout: () => 0,
    clearTimeout() {},
    setInterval: () => 0,
    clearInterval() {},
    requestAnimationFrame: () => 0,
    alert() {},
    confirm: () => false,
    Date: PinnedDate,
    Math: math,
    Intl,
  };
}

// Any property is another stub, any call returns a stub: absorbs DOM chains such as
// $('#x').addEventListener(...).classList.add(...).
function domStub() {
  const fn = function () { return proxy; };
  const proxy = new Proxy(fn, {
    get(_t, key) {
      if (key === Symbol.toPrimitive) return () => '';
      if (key === 'getAttribute') return () => null;
      if (key === 'value' || key === 'innerHTML' || key === 'textContent') return '';
      if (key === 'files' || key === 'children' || key === 'childNodes') return [];
      return proxy;
    },
    set: () => true,
    apply: () => proxy,
  });
  return proxy;
}

function classListStub() {
  return { add() {}, remove() {}, toggle() {}, contains: () => false };
}

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
