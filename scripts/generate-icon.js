import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

const applicationSvg = await readFile(join(projectRoot, "assets", "icon.svg"));
const applicationPng = await sharp(applicationSvg).png().toBuffer();
const applicationMetadata = await sharp(applicationPng).metadata();
if (applicationMetadata.width !== 512 || applicationMetadata.height !== 512) {
  throw new Error("generated application icon has an invalid size");
}

const traySvg = await readFile(join(projectRoot, "assets", "tray-icon.svg"));
const trayPng = await sharp(traySvg).resize(32, 32).png().toBuffer();
const trayMetadata = await sharp(trayPng).metadata();
if (trayMetadata.width !== 32 || trayMetadata.height !== 32) {
  throw new Error("generated tray icon has an invalid size");
}

await mkdir(join(projectRoot, "build"), { recursive: true });
await writeFile(join(projectRoot, "build", "icon.png"), applicationPng);
await writeFile(join(projectRoot, "build", "tray-icon.png"), trayPng);
