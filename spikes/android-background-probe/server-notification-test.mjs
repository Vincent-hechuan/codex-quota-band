// PROTOTYPE ONLY: emits one quiet and one vibrating notification event.
import net from "node:net";

const port = 17421;
const timers = new Set();

function schedule(socket, delayMs, state) {
  const timer = setTimeout(() => {
    timers.delete(timer);
    if (socket.destroyed) return;
    socket.write(`${state}|${Date.now()}\n`);
    process.stdout.write(`${new Date().toISOString()} event=${state}\n`);
  }, delayMs);
  timers.add(timer);
}

const server = net.createServer((socket) => {
  process.stdout.write(`${new Date().toISOString()} connected\n`);
  socket.write(`running|${Date.now()}\n`);
  schedule(socket, 20_000, "waiting_review");
  schedule(socket, 40_000, "needs_authorization");
  socket.on("close", () => {
    process.stdout.write(`${new Date().toISOString()} disconnected\n`);
  });
  socket.on("error", () => {});
});

server.listen(port, "127.0.0.1", () => {
  process.stdout.write(`${new Date().toISOString()} listening 127.0.0.1:${port}\n`);
});

function shutdown() {
  for (const timer of timers) clearTimeout(timer);
  server.close(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
