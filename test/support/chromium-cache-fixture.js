import { brotliCompressSync } from "node:zlib";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

const HEADER_SIZE = 8_192;
const BLOCK_256 = 2;
const BLOCK_1K = 3;

export async function writeResetCacheFixture(directory, { responseDate, body }) {
  await mkdir(directory, { recursive: true });
  const data1 = blockFile(256, 32, 1);
  const data2 = blockFile(1_024, 8, 2);
  const bodyBytes = brotliCompressSync(Buffer.from(JSON.stringify(body), "utf8"));
  const metadata = Buffer.from(
    `HTTP/1.1 200\0date:${responseDate}\0content-type:application/json\0content-encoding:br\0`,
    "latin1",
  );

  const entryBlock = 2;
  const metadataBlock = 1;
  const bodyBlock = 5;
  const entryOffset = HEADER_SIZE + entryBlock * 256;
  const key = Buffer.from(
    "1/0/_dk_https://chatgpt.com https://chatgpt.com https://chatgpt.com/backend-api/wham/rate-limit-reset-credits",
    "utf8",
  );
  data1.writeInt32LE(0, entryOffset + 20);
  data1.writeInt32LE(key.length, entryOffset + 32);
  data1.writeInt32LE(metadata.length, entryOffset + 40);
  data1.writeInt32LE(bodyBytes.length, entryOffset + 44);
  data1.writeUInt32LE(cacheAddress(BLOCK_1K, 2, metadataBlock, 1), entryOffset + 56);
  data1.writeUInt32LE(
    cacheAddress(BLOCK_256, 1, bodyBlock, Math.ceil(bodyBytes.length / 256)),
    entryOffset + 60,
  );
  key.copy(data1, entryOffset + 96);
  bodyBytes.copy(data1, HEADER_SIZE + bodyBlock * 256);
  metadata.copy(data2, HEADER_SIZE + metadataBlock * 1_024);

  await Promise.all([
    writeFile(join(directory, "data_1"), data1),
    writeFile(join(directory, "data_2"), data2),
  ]);
}
function blockFile(entrySize, blocks, fileNumber) {
  const buffer = Buffer.alloc(HEADER_SIZE + entrySize * blocks);
  buffer.writeUInt32LE(0xc104cac3, 0);
  buffer.writeUInt32LE(0x00020000, 4);
  buffer.writeInt16LE(fileNumber, 8);
  buffer.writeInt32LE(entrySize, 12);
  buffer.writeInt32LE(blocks, 20);
  return buffer;
}

function cacheAddress(type, file, start, blocks) {
  return (
    0x80000000 |
    (type << 28) |
    ((blocks - 1) << 24) |
    (file << 16) |
    start
  ) >>> 0;
}
