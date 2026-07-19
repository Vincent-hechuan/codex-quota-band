import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { assertPublicSnapshot } from "../core/validate-snapshot.js";
import { isPrivateNetworkAddress } from "./network-access.js";
import { createMemoryHashedTokenStore } from "./token-store.js";

const JSON_HEADERS = {
  "cache-control": "no-store",
  "content-type": "application/json; charset=utf-8",
};

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest();
}

function safelyLog(logger, event) {
  try {
    logger(event);
  } catch {
    // Diagnostics must never make the quota endpoint unavailable.
  }
}

function sendJson(response, statusCode, body) {
  response.writeHead(statusCode, JSON_HEADERS);
  response.end(JSON.stringify(body));
}

async function readJsonBody(request, maximumBytes = 4_096) {
  const chunks = [];
  let bytesRead = 0;

  for await (const chunk of request) {
    bytesRead += chunk.length;
    if (bytesRead > maximumBytes) {
      const error = new Error("request body is too large");
      error.code = "BODY_TOO_LARGE";
      throw error;
    }
    chunks.push(chunk);
  }

  if (chunks.length === 0) {
    return {};
  }

  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function bearerToken(request) {
  const header = request.headers.authorization;
  if (typeof header !== "string" || !header.startsWith("Bearer ")) {
    return null;
  }

  const token = header.slice("Bearer ".length);
  return token.length > 0 ? token : null;
}

export async function startQuotaServer({
  host,
  port,
  pairing,
  clock = () => new Date(),
  snapshotProvider,
  logger = () => {},
  tokenStore = createMemoryHashedTokenStore(),
  allowRemoteAddress = isPrivateNetworkAddress,
}) {
  let pairingCodeHash = sha256(pairing.code);
  const pairingState = {
    attempts: 0,
    consumed: false,
    expiresAt: pairing.expiresAt,
    maxAttempts: pairing.maxAttempts,
  };
  const beginPairing = (nextPairing) => {
    pairingCodeHash = sha256(nextPairing.code);
    pairingState.attempts = 0;
    pairingState.consumed = false;
    pairingState.expiresAt = nextPairing.expiresAt;
    pairingState.maxAttempts = nextPairing.maxAttempts;
    safelyLog(logger, { event: "pairing_started", status: 200 });
  };
  const server = createServer((request, response) => {
    void handleRequest(request, response).catch((error) => {
      safelyLog(logger, { event: "request_failed", status: 500 });
      if (!response.headersSent) {
        sendJson(response, 500, { error: "internal_error" });
      } else {
        response.end();
      }
    });
  });

  async function handleRequest(request, response) {
    if (!allowRemoteAddress(request.socket.remoteAddress ?? "")) {
      safelyLog(logger, { event: "network_rejected", status: 403 });
      sendJson(response, 403, { error: "private_network_required" });
      return;
    }

    const url = new URL(request.url ?? "/", "http://quota.invalid");

    if (request.method === "POST" && url.pathname === "/v1/pair") {
      if (pairingState.consumed || clock().getTime() >= pairingState.expiresAt.getTime()) {
        safelyLog(logger, { event: "pair_rejected", status: 410 });
        sendJson(response, 410, { error: "pairing_unavailable" });
        return;
      }

      if (pairingState.attempts >= pairingState.maxAttempts) {
        safelyLog(logger, { event: "pair_rejected", status: 429 });
        sendJson(response, 429, { error: "attempt_limit_reached" });
        return;
      }

      const contentType = request.headers["content-type"]?.split(";", 1)[0].trim().toLowerCase();
      if (contentType !== "application/json") {
        safelyLog(logger, { event: "pair_rejected", status: 415 });
        sendJson(response, 415, { error: "unsupported_media_type" });
        return;
      }

      let body;
      try {
        body = await readJsonBody(request);
      } catch (error) {
        const status = error?.code === "BODY_TOO_LARGE" ? 413 : 400;
        safelyLog(logger, { event: "pair_rejected", status });
        sendJson(response, status, { error: status === 413 ? "payload_too_large" : "invalid_json" });
        return;
      }

      const keys = body && typeof body === "object" && !Array.isArray(body)
        ? Object.keys(body)
        : [];
      if (
        keys.length !== 1 ||
        keys[0] !== "code" ||
        typeof body.code !== "string" ||
        !/^\d{6}$/.test(body.code)
      ) {
        safelyLog(logger, { event: "pair_rejected", status: 400 });
        sendJson(response, 400, { error: "invalid_pairing_request" });
        return;
      }

      pairingState.attempts += 1;
      const suppliedCodeHash = sha256(body.code);
      if (!timingSafeEqual(pairingCodeHash, suppliedCodeHash)) {
        safelyLog(logger, { event: "pair_rejected", status: 401 });
        sendJson(response, 401, { error: "invalid_pairing_code" });
        return;
      }

      const token = randomBytes(32).toString("base64url");
      await tokenStore.add(token);
      pairingState.consumed = true;
      safelyLog(logger, { event: "pair_succeeded", status: 201 });
      sendJson(response, 201, { token });
      return;
    }

    if (request.method === "GET" && url.pathname === "/v1/snapshot") {
      const token = bearerToken(request);
      if (token === null || !(await tokenStore.authenticate(token))) {
        safelyLog(logger, { event: "snapshot_rejected", status: 401 });
        sendJson(response, 401, { error: "unauthorized" });
        return;
      }

      const snapshot = assertPublicSnapshot(await snapshotProvider());
      safelyLog(logger, { event: "snapshot_served", status: 200 });
      sendJson(response, 200, snapshot);
      return;
    }

    safelyLog(logger, { event: "route_not_found", status: 404 });
    sendJson(response, 404, { error: "not_found" });
  }

  await new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.off("error", onError);
      resolve();
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen({ host, port });
  });

  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("quota server did not bind a TCP address");
  }

  let closed = false;
  const close = async () => {
    if (closed) {
      return;
    }
    closed = true;
    await new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()));
    });
  };

  return {
    baseUrl: `http://${address.address}:${address.port}`,
    port: address.port,
    beginPairing,
    close,
    async [Symbol.asyncDispose]() {
      await close();
    },
  };
}
