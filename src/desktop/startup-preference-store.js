import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const VERSION = 1;

export async function openStartupPreferenceStore({ filePath }) {
  const save = async (openAtLogin) => {
    if (typeof openAtLogin !== "boolean") {
      throw new TypeError("openAtLogin must be a boolean");
    }
    await mkdir(dirname(filePath), { recursive: true });
    const temporaryPath = `${filePath}.${process.pid}.${randomUUID()}.tmp`;
    try {
      await writeFile(
        temporaryPath,
        `${JSON.stringify({ version: VERSION, openAtLogin })}\n`,
        { encoding: "utf8", flag: "wx", mode: 0o600 },
      );
      await rename(temporaryPath, filePath);
    } catch (error) {
      await rm(temporaryPath, { force: true });
      throw error;
    }
  };

  return {
    async loadOrInitialize() {
      try {
        const parsed = JSON.parse(await readFile(filePath, "utf8"));
        if (parsed?.version === VERSION && typeof parsed.openAtLogin === "boolean") {
          return parsed.openAtLogin;
        }
      } catch (error) {
        if (error?.code !== "ENOENT" && !(error instanceof SyntaxError)) {
          throw error;
        }
      }
      await save(true);
      return true;
    },
    save,
  };
}
