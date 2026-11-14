#!/usr/bin/env bun
/**
 * Build Alpine Linux .apk packages for x86_64 and aarch64.
 *
 * The package contents are the musl builds from `bun run build:all`. Packaging
 * itself runs inside alpine:3.20 with Alpine's abuild toolchain, which produces
 * signed packages with the required datahash and per-file checksums.
 *
 * Requires Docker (or a Docker-compatible CLI).
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dir, "..");
const dist = path.join(root, "dist");
const pkg = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8")) as {
  version: string;
};
const version = pkg.version;

const arches = [
  { alpine: "x86_64", binary: "minicode-linux-x64-musl" },
  { alpine: "aarch64", binary: "minicode-linux-arm64-musl" },
] as const;

for (const { binary } of arches) {
  if (!existsSync(path.join(dist, binary))) {
    throw new Error(
      `Missing dist/${binary}. Run "bun run build:all" before packaging Alpine packages.`,
    );
  }
}

const probe = Bun.spawnSync(["docker", "--version"], {
  stdout: "ignore",
  stderr: "ignore",
});
if (probe.exitCode !== 0) {
  throw new Error("Docker is required to build Alpine packages (alpine:3.20 + abuild).");
}

mkdirSync(dist, { recursive: true });

console.log("▸ building Alpine packages with abuild (alpine:3.20)");
const docker = Bun.spawn(
  [
    "docker",
    "run",
    "--rm",
    "-v",
    `${root}:/work`,
    "-w",
    "/work",
    "alpine:3.20",
    "sh",
    "/work/scripts/apk-inner.sh",
  ],
  { stdin: "inherit", stdout: "inherit", stderr: "inherit" },
);
const code = await docker.exited;
if (code !== 0) throw new Error(`Docker/abuild failed (exit ${code})`);

function sha256(file: string): string {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

const packages = arches.map(({ alpine }) =>
  path.join(dist, `minicode-${version}-r0.${alpine}.apk`),
);

for (const packageFile of packages) {
  if (!existsSync(packageFile)) {
    throw new Error(`Expected package was not produced: ${packageFile}`);
  }
  console.log(
    `  ✓ ${path.basename(packageFile)}  ${(statSync(packageFile).size / 1024 / 1024).toFixed(1)} MB`,
  );
}

// Merge the new package hashes into checksums.txt without dropping binaries.
const checksumsFile = path.join(dist, "checksums.txt");
const entries = new Map<string, string>();
if (existsSync(checksumsFile)) {
  for (const line of readFileSync(checksumsFile, "utf8").split("\n")) {
    const match = /^([a-f0-9]{64})  (.+)$/.exec(line);
    // Drop entries for files that no longer exist (e.g. packages from an older version).
    if (match && existsSync(path.join(dist, match[2]!))) entries.set(match[2]!, match[1]!);
  }
}
for (const packageFile of packages) {
  entries.set(path.basename(packageFile), sha256(packageFile));
}

const lines = [...entries.entries()]
  .sort((a, b) => a[0].localeCompare(b[0]))
  .map(([name, hash]) => `${hash}  ${name}`);
writeFileSync(checksumsFile, lines.join("\n") + "\n");
console.log(`  ✓ ${path.relative(root, checksumsFile)}`);
