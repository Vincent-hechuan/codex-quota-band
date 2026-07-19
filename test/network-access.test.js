import assert from "node:assert/strict";
import test from "node:test";
import { isPrivateNetworkAddress } from "../src/server/network-access.js";

test("only loopback and private network source addresses are trusted", () => {
  for (const address of [
    "127.0.0.1",
    "10.0.0.8",
    "172.16.0.1",
    "172.31.255.254",
    "192.168.31.8",
    "169.254.1.2",
    "::1",
    "fe80::1",
    "fd12:3456::1",
    "::ffff:192.168.31.9",
  ]) {
    assert.equal(isPrivateNetworkAddress(address), true, address);
  }

  for (const address of [
    "8.8.8.8",
    "172.32.0.1",
    "203.0.113.2",
    "2001:4860:4860::8888",
    "not-an-address",
  ]) {
    assert.equal(isPrivateNetworkAddress(address), false, address);
  }
});
