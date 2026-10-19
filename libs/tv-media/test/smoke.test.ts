import { expect, it } from "vitest";
import { VERSION } from "../src/index";

it("loads", () => {
  expect(VERSION).toBe("0.0.0");
});
