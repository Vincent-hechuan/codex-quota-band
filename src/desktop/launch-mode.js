export function requiresSingleInstanceLock(argv) {
  return !argv.includes("--smoke-test") && !argv.includes("--diagnostic-service-test");
}
