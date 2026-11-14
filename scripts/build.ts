#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dir, "..");
const dist = path.join(root, "dist");
const pkg = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8")) as {
  version: string;
};
const version = pkg.version;

const targets: Record<string, string> = {
  "linux-x64": "bun-linux-x64",
  "linux-x64-musl": "bun-linux-x64-musl",
  "linux-arm64": "bun-linux-arm64",
  "linux-arm64-musl": "bun-linux-arm64-musl",
  "darwin-x64": "bun-darwin-x64",
  "darwin-arm64": "bun-darwin-arm64",
  "windows-x64": "bun-windows-x64",
};

function currentTarget(): string {
  const platform = process.platform === "win32" ? "windows" : process.platform;
  const key = `${platform}-${process.arch}`;
  if (!(key in targets)) {
    throw new Error(
      `Unsupported build platform ${key}. Supported targets: ${Object.keys(targets).join(", ")}`,
    );
  }
  return key;
}

function assetName(target: string): string {
  return `minicode-${target}${target.startsWith("windows") ? ".exe" : ""}`;
}

const buildAll = process.argv.includes("--all");
const selected = buildAll ? Object.keys(targets) : [currentTarget()];
const built: string[] = [];

mkdirSync(dist, { recursive: true });

for (const target of selected) {
  const bunTarget = targets[target]!;
  const outfile = path.join(dist, assetName(target));
  console.log(`\n▸ building ${assetName(target)} (${bunTarget})`);

  const proc = Bun.spawn(
    [
      process.execPath,
      "build",
      "--compile",
      "--minify",
      `--target=${bunTarget}`,
      "--outfile",
      outfile,
      "--define",
      `process.env.MINICODE_VERSION=${JSON.stringify(version)}`,
      "src/cli.ts",
    ],
    { cwd: root, stdout: "inherit", stderr: "inherit" },
  );

  const code = await proc.exited;
  if (code !== 0) throw new Error(`Build failed for ${target} (exit ${code})`);

  const size = statSync(outfile).size;
  console.log(`  ✓ ${assetName(target)}  ${(size / 1024 / 1024).toFixed(1)} MB`);
  built.push(outfile);
}

const checksumLines = built
  .map((file) => {
    const name = path.basename(file);
    const hash = createHash("sha256").update(readFileSync(file)).digest("hex");
    return `${hash}  ${name}`;
  })
  .join("\n");

const checksums = path.join(dist, "checksums.txt");
writeFileSync(checksums, checksumLines + "\n");
console.log(`\n  ✓ ${path.relative(root, checksums)}`);

const unexpected = readdirSync(dist).filter(
  (name) => name.startsWith("minicode-") && !built.some((file) => path.basename(file) === name),
);
if (unexpected.length > 0) {
  console.log(
    `\n  note: dist also contains older artifacts: ${unexpected.join(", ")}\n  run "rm -rf dist" before a release build.`,
  );
}
