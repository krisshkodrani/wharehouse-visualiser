import { mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { rollup } from "rollup";

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDirectory = resolve(appRoot, "webapp/vendor");
await mkdir(outputDirectory, { recursive: true });

const bundle = await rollup({
  input: resolve(appRoot, "scripts/babylon-entry.js"),
  treeshake: true
});

try {
  await bundle.write({
    file: resolve(outputDirectory, "babylon.js"),
    format: "iife",
    name: "WarehouseBabylon",
    generatedCode: "es2015",
    inlineDynamicImports: true,
    sourcemap: false
  });
} finally {
  await bundle.close();
}
