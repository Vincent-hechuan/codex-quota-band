function parseIpv4(address) {
  const parts = address.split(".");
  if (
    parts.length !== 4 ||
    parts.some((part) => !/^\d{1,3}$/.test(part) || Number(part) > 255)
  ) {
    return null;
  }
  return parts.map(Number);
}

export function isPrivateNetworkAddress(input) {
  if (typeof input !== "string") {
    return false;
  }
  const address = input.toLowerCase().split("%", 1)[0];

  if (address.startsWith("::ffff:")) {
    return isPrivateNetworkAddress(address.slice("::ffff:".length));
  }

  const ipv4 = parseIpv4(address);
  if (ipv4) {
    const [first, second] = ipv4;
    return (
      first === 10 ||
      first === 127 ||
      (first === 172 && second >= 16 && second <= 31) ||
      (first === 192 && second === 168) ||
      (first === 169 && second === 254)
    );
  }

  if (address === "::1") {
    return true;
  }
  const firstHextet = Number.parseInt(address.split(":", 1)[0], 16);
  if (!Number.isInteger(firstHextet)) {
    return false;
  }
  return (
    (firstHextet & 0xfe00) === 0xfc00 ||
    (firstHextet & 0xffc0) === 0xfe80
  );
}
