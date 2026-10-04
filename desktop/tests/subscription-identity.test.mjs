import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { isValidHwid } from "../src/subscriptionIdentity.ts";

test("HWID validation matches Android and Rust fixtures without normalization", () => {
  const { cases } = JSON.parse(readFileSync(new URL("../../tests/fixtures/subscription-hwid.json", import.meta.url), "utf8"));
  for (const { value, valid } of cases) assert.equal(isValidHwid(value), valid, JSON.stringify(value));
});
