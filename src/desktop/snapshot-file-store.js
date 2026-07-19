import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { assertPublicSnapshot, validatePublicSnapshot } from "../core/validate-snapshot.js";

export async function openSnapshotFileStore({ filePath }) {
  return {
    async load() {
      try {
        const snapshot = JSON.parse(await readFile(filePath, "utf8"));
        return validatePublicSnapshot(snapshot) ? snapshot : null;
      } catch (error) {
        if (error?.code === "ENOENT" || error instanceof SyntaxError) {
          return null;
        }
        throw error;
      }
    },
    async save(snapshot) {
      assertPublicSnapshot(snapshot);
      await mkdir(dirname(filePath), { recursive: true });
      const temporaryPath = `${filePath}.${process.pid}.${randomUUID()}.tmp`;
      try {
        await writeFile(temporaryPath, `${JSON.stringify(snapshot)}\n`, {
          encoding: "utf8",
          flag: "wx",
          mode: 0o600,
        });
        await rename(temporaryPath, filePath);
      } catch (error) {
        await rm(temporaryPath, { force: true });
        throw error;
      }
    },
  };
}
