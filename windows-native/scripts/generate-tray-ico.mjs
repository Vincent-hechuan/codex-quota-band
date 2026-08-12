import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const windowsRoot = resolve(scriptDirectory, "..");
const repositoryRoot = resolve(windowsRoot, "..");
const inputPath = join(repositoryRoot, "build", "tray-icon.png");
const outputPath = join(windowsRoot, "assets", "tray-icon.ico");
const applicationInputPath = join(repositoryRoot, "build", "icon.png");
const applicationOutputPath = join(windowsRoot, "assets", "app-icon.ico");
const png = await readFile(inputPath);

if (!png.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) {
  throw new Error(`tray icon is not a PNG: ${inputPath}`);
}

// An ICO can carry a PNG image directly. Windows reads the embedded image
// through CreateIconFromResourceEx, so no bitmap conversion is needed.
const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(1, 4);

const entry = Buffer.alloc(16);
entry.writeUInt8(32, 0);
entry.writeUInt8(32, 1);
entry.writeUInt8(0, 2);
entry.writeUInt8(0, 3);
entry.writeUInt16LE(1, 4);
entry.writeUInt16LE(32, 6);
entry.writeUInt32LE(png.length, 8);
entry.writeUInt32LE(22, 12);

await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, Buffer.concat([header, entry, png]));
console.log(`generated ${outputPath}`);

const applicationSource = await readFile(applicationInputPath);
const sizes = [16, 32, 48, 256];
const applicationImages = await Promise.all(
  sizes.map((size) => sharp(applicationSource).resize(size, size).png().toBuffer()),
);
const applicationHeader = Buffer.alloc(6);
applicationHeader.writeUInt16LE(0, 0);
applicationHeader.writeUInt16LE(1, 2);
applicationHeader.writeUInt16LE(applicationImages.length, 4);
let imageOffset = 6 + applicationImages.length * 16;
const applicationEntries = applicationImages.map((image, index) => {
  const size = sizes[index];
  const iconEntry = Buffer.alloc(16);
  iconEntry.writeUInt8(size === 256 ? 0 : size, 0);
  iconEntry.writeUInt8(size === 256 ? 0 : size, 1);
  iconEntry.writeUInt8(0, 2);
  iconEntry.writeUInt8(0, 3);
  iconEntry.writeUInt16LE(1, 4);
  iconEntry.writeUInt16LE(32, 6);
  iconEntry.writeUInt32LE(image.length, 8);
  iconEntry.writeUInt32LE(imageOffset, 12);
  imageOffset += image.length;
  return iconEntry;
});
await writeFile(
  applicationOutputPath,
  Buffer.concat([applicationHeader, ...applicationEntries, ...applicationImages]),
);
console.log(`generated ${applicationOutputPath}`);
