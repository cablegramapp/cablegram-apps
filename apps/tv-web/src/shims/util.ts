// GramJS only needs util.inspect (and its `custom` symbol) for debug printing; Node's util is not available in a browser.
export const inspect = Object.assign((value: unknown): string => String(value), { custom: Symbol.for("nodejs.util.inspect.custom") });
