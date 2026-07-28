import { pathToFileURL } from "node:url";
import { fileURLToPath } from "node:url";

const builtPagePath = fileURLToPath(
  new URL("../../build/pages/index/index.js", import.meta.url),
);

export async function withBuiltPage(callback, modules = {}) {
  globalThis.exports = {};

  globalThis.$app_require$ = (id) => {
    if (id === "@app-module/system.interconnect") {
      return modules.interconnect ?? { instance: () => ({}) };
    }
    if (id === "@app-module/system.storage") {
      return modules.storage ?? {};
    }
    throw new Error(`unexpected app module: ${id}`);
  };
  globalThis.aiot = {
    __ce__(tag, properties, children) {
      return { tag, properties, children };
    },
    __ci__(binding, render) {
      return binding.__opts__.shown() ? render() : [];
    },
    __cf__(binding, render) {
      const value = binding.__opts__.exp();
      const items = Array.isArray(value) ? value : value?.__list__ ?? [];
      return items.flatMap((item, index) => render(index, item));
    },
  };

  try {
    const moduleUrl = `${pathToFileURL(builtPagePath).href}?smoke=${Date.now()}-${Math.random()}`;
    const builtPage = await import(moduleUrl);
    const pageExports = {};

    builtPage.default(
      globalThis,
      globalThis,
      globalThis,
      pageExports,
      () => {},
    );
    return await callback(pageExports);
  } finally {
    delete globalThis.$app_require$;
    delete globalThis.aiot;
    delete globalThis.exports;
  }
}
