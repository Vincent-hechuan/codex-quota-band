import { join } from "node:path";

export function resolveTrayIconPath({ isPackaged, appPath, resourcesPath }) {
  return isPackaged
    ? join(resourcesPath, "tray-icon.png")
    : join(appPath, "build", "tray-icon.png");
}

export function loadTrayIcon({ nativeImage, filePath }) {
  const icon = nativeImage.createFromPath(filePath);
  if (icon.isEmpty()) {
    throw new Error(`Tray icon could not be loaded: ${filePath}`);
  }
  return icon;
}
