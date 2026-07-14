import assert from "node:assert/strict";
import test from "node:test";
import { refreshTokenOnce } from "./auth-refresh.ts";

test("shares one refresh request among concurrent callers", async () => {
  let calls = 0;
  let release;
  const request = () => {
    calls += 1;
    return new Promise((resolve) => {
      release = () => resolve({ accessToken: "next", refreshToken: "next-refresh" });
    });
  };

  const first = refreshTokenOnce(request);
  const second = refreshTokenOnce(request);

  assert.equal(calls, 1);
  release();
  assert.deepEqual(await first, { accessToken: "next", refreshToken: "next-refresh" });
  assert.deepEqual(await second, { accessToken: "next", refreshToken: "next-refresh" });
});
