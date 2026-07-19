import { isIP } from "node:net";
import { isPrivateNetworkAddress } from "../server/network-access.js";

const PAIRING_PROTOCOL_VERSION = 1;
const PAIRING_KIND = "codex-quota-pairing";
const MAXIMUM_ENDPOINTS = 8;
export const FORMAL_ASTROBOX_PLUGIN_NAME = "Codex 额度桥接";

function addressPriority(address) {
  const octets = address.split(".").map(Number);
  if (octets[0] === 192 && octets[1] === 168) return 0;
  if (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) return 1;
  if (octets[0] === 10) return 2;
  if (octets[0] === 169 && octets[1] === 254) return 3;
  return 4;
}

export function prioritizePairingAddresses(addresses) {
  return [...new Set(addresses)]
    .sort(
      (left, right) =>
        addressPriority(left) - addressPriority(right) ||
        left.localeCompare(right, "en", { numeric: true }),
    )
    .slice(0, MAXIMUM_ENDPOINTS);
}

function assertPrivateEndpoint(endpoint) {
  let url;
  try {
    url = new URL(endpoint);
  } catch {
    throw new TypeError("pairing endpoint must be a URL");
  }
  if (
    url.protocol !== "http:" ||
    isIP(url.hostname) !== 4 ||
    !isPrivateNetworkAddress(url.hostname) ||
    url.port === "" ||
    url.username !== "" ||
    url.password !== "" ||
    url.pathname !== "/" ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new TypeError("pairing endpoint must be a private numeric HTTP endpoint");
  }
}

export function createAstroBoxPairingDeepLink({
  pluginName,
  endpoints,
  code,
}) {
  if (typeof pluginName !== "string" || pluginName.length === 0 || pluginName.length > 80) {
    throw new TypeError("plugin name is invalid");
  }
  if (
    !Array.isArray(endpoints) ||
    endpoints.length === 0 ||
    endpoints.length > MAXIMUM_ENDPOINTS
  ) {
    throw new TypeError("pairing endpoints are invalid");
  }
  for (const endpoint of endpoints) {
    assertPrivateEndpoint(endpoint);
  }
  if (typeof code !== "string" || !/^\d{6}$/.test(code)) {
    throw new TypeError("pairing code must contain six digits");
  }

  const url = new URL("astrobox://open");
  url.searchParams.set("source", "plugdata");
  url.searchParams.set("name", pluginName);
  url.searchParams.set(
    "payload",
    JSON.stringify({
      protocolVersion: PAIRING_PROTOCOL_VERSION,
      kind: PAIRING_KIND,
      baseUrls: endpoints,
      code,
    }),
  );
  return url.toString();
}
