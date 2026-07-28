// PROTOTYPE ONLY: local ADB-reverse event source for the Android background probe.
import net from "node:net";

const port = 17421;
const clients = new Set();
const states = ["running", "needs_authorization", "waiting_review"];
let index = 0;

const server = net.createServer((socket) => {
  clients.add(socket);
  process.stdout.write(`${new Date().toISOString()} connected clients=${clients.size}\n`);
  socket.write(`running|${Date.now()}\n`);
  socket.on("close", () => {
    clients.delete(socket);
    process.stdout.write(`${new Date().toISOString()} disconnected clients=${clients.size}\n`);
  });
  socket.on("error", () => {});
});

server.listen(port, "127.0.0.1", () => {
  process.stdout.write(`${new Date().toISOString()} listening 127.0.0.1:${port}\n`);
});

setInterval(() => {
  const state = states[index % states.length];
  index += 1;
  const line = `${state}|${Date.now()}\n`;
  for (const client of clients) client.write(line);
  process.stdout.write(
    `${new Date().toISOString()} event=${state} clients=${clients.size}\n`,
  );
}, 15_000);
