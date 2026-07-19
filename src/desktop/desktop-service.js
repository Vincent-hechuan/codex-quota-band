import { randomInt } from "node:crypto";

const PAIRING_LIFETIME_MILLISECONDS = 5 * 60 * 1_000;
const PAIRING_MAXIMUM_ATTEMPTS = 3;

function createPairing({ clock, randomInteger }) {
  const value = randomInteger(0, 1_000_000);
  if (!Number.isInteger(value) || value < 0 || value >= 1_000_000) {
    throw new Error("pairing random source returned an invalid value");
  }

  return {
    code: value.toString().padStart(6, "0"),
    expiresAt: new Date(clock().getTime() + PAIRING_LIFETIME_MILLISECONDS),
    maxAttempts: PAIRING_MAXIMUM_ATTEMPTS,
  };
}

export async function startDesktopService({
  serverFactory,
  serverOptions,
  tokenStore,
  loginItem,
  lanAddresses,
  clock = () => new Date(),
  randomInteger = randomInt,
}) {
  const initialPairing = createPairing({ clock, randomInteger });
  const server = await serverFactory({
    ...serverOptions,
    pairing: initialPairing,
    tokenStore,
  });

  let closed = false;
  const close = async () => {
    if (closed) {
      return;
    }
    closed = true;
    await server.close();
  };

  return {
    beginPairing() {
      const pairing = createPairing({ clock, randomInteger });
      server.beginPairing(pairing);
      return {
        code: pairing.code,
        expiresAt: pairing.expiresAt.toISOString(),
        endpoints: lanAddresses().map(
          (address) => `http://${address}:${server.port}`,
        ),
      };
    },
    async revokeAll() {
      await tokenStore.revokeAll();
    },
    async setLoginStartup(enabled) {
      await loginItem.setEnabled(enabled);
    },
    close,
    async [Symbol.asyncDispose]() {
      await close();
    },
  };
}
