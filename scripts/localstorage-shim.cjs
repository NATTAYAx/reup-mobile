const m = {};
globalThis.localStorage = {
  getItem: k => (k in m ? m[k] : null),
  setItem: (k, v) => { m[k] = String(v); },
  removeItem: k => { delete m[k]; },
  clear: () => { for (const k in m) delete m[k]; },
};
