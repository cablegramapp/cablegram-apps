// GramJS expects a few Node globals. Imported first by telegram.ts so they exist before GramJS evaluates.
import { Buffer } from "buffer";

const g = window as unknown as { Buffer?: typeof Buffer; global?: unknown };
if (!g.Buffer) g.Buffer = Buffer;
if (!g.global) g.global = window;
