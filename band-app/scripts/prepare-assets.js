import { mkdir, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const bandRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const workspaceRoot = join(bandRoot, "..");
const destination = join(bandRoot, "src", "common", "logo.png");

await mkdir(dirname(destination), { recursive: true });
await sharp(join(workspaceRoot, "assets", "icon.svg")).png().toFile(destination);

// v0.2.0 generated full-screen gradient backgrounds for the retired perimeter UI.
// Keep fresh builds from silently packaging those obsolete resources.
await Promise.all(["normal", "low", "offline"].map((tone) =>
  rm(join(bandRoot, "src", "common", `quota-bg-${tone}.png`), { force: true }),
));
