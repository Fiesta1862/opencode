import { readdir, readFile, stat } from "node:fs/promises";
import path from "node:path";
import type { ToolResult } from "../types.ts";
import { displayPath, resolveWorkspacePath } from "../util/paths.ts";
import { getBoolean, getInteger, getString, ToolInputError, type Tool } from "./types.ts";

const MAX_MATCHES = 150;
const MAX_FILE_BYTES = 1024 * 1024;
const SKIP_DIRS = new Set([".git", "node_modules", ".minicode"]);

async function* walkFiles(root: string, signal?: AbortSignal): AsyncGenerator<string> {
  let entries;
  try {
    entries = await readdir(root, { withFileTypes: true });
  } catch {
    return;
  }
  entries.sort((a, b) => a.name.localeCompare(b.name));

  for (const entry of entries) {
    if (signal?.aborted) return;
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      yield* walkFiles(path.join(root, entry.name), signal);
    } else if (entry.isFile()) {
      yield path.join(root, entry.name);
    }
  }
}

function limitLine(line: string, max = 300): string {
  const clean = line.replace(/\r$/, "");
  return clean.length > max ? clean.slice(0, max) + "…" : clean;
}

export const grepTool: Tool = {
  definition: {
    name: "grep",
    description:
      "Search file contents using a regular expression. Returns matching lines as path:line: text. Skips node_modules, .git and binary files. Use include to limit files, e.g. \"*.ts\".",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string", description: "JavaScript regular expression to search for." },
        path: { type: "string", description: "File or directory to search. Defaults to the workspace root." },
        include: { type: "string", description: "Glob filter for file names, e.g. \"*.ts\" or \"src/**/*.ts\"." },
        ignore_case: { type: "boolean", description: "Case-insensitive search." },
      },
      required: ["pattern"],
      additionalProperties: false,
    },
  },
  async run(input, ctx): Promise<ToolResult> {
    const pattern = getString(input, "pattern");
    const pathInput = getString(input, "path", { optional: true }) ?? ".";
    const include = getString(input, "include", { optional: true });
    const ignoreCase = getBoolean(input, "ignore_case", { optional: true }) ?? false;

    let regex: RegExp;
    try {
      regex = new RegExp(pattern, ignoreCase ? "i" : "");
    } catch (err) {
      throw new ToolInputError(`Invalid regular expression: ${(err as Error).message}`);
    }

    const absTarget = await resolveWorkspacePath(ctx.cwd, pathInput);
    const info = await stat(absTarget).catch(() => undefined);
    if (!info) throw new ToolInputError(`Path not found: ${pathInput}`);

    const files: AsyncIterable<string> = info.isDirectory()
      ? walkFiles(absTarget, ctx.signal)
      : (async function* () {
          yield absTarget;
        })();

    const includeGlob = include ? new Bun.Glob(include) : undefined;
    const matches: string[] = [];
    let truncated = false;
    let filesScanned = 0;

    for await (const file of files) {
      if (ctx.signal?.aborted) break;
      filesScanned++;

      const rel = displayPath(ctx.cwd, file).replaceAll("\\", "/");
      if (includeGlob && include) {
        const target = include.includes("/") ? rel : path.basename(file);
        if (!includeGlob.match(target)) continue;
      }

      let buffer: Buffer;
      try {
        const size = (await stat(file)).size;
        if (size > MAX_FILE_BYTES) continue;
        buffer = await readFile(file);
      } catch {
        continue;
      }
      if (buffer.subarray(0, 4096).includes(0)) continue;

      const lines = buffer.toString("utf8").split("\n");
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i]!;
        if (!regex.test(line)) continue;
        matches.push(`${rel}:${i + 1}: ${limitLine(line.trim())}`);
        if (matches.length >= MAX_MATCHES) {
          truncated = true;
          break;
        }
      }
      if (truncated) break;
    }

    let output =
      matches.length === 0
        ? `No matches for /${pattern}/ in ${displayPath(ctx.cwd, absTarget)} (${filesScanned} files scanned).`
        : matches.join("\n");
    if (truncated) output += `\n\n[Stopped after ${MAX_MATCHES} matches]`;

    return {
      output,
      summary: `${matches.length} match${matches.length === 1 ? "" : "es"} for /${pattern}/`,
    };
  },
};

const MAX_ENTRIES = 300;

export const listFilesTool: Tool = {
  definition: {
    name: "list_files",
    description:
      "List files and directories under a path, as an indented tree. Useful for getting oriented in a repository.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "Directory to list. Defaults to the workspace root." },
        depth: { type: "integer", description: "How many levels deep to recurse (default 2, max 4).", minimum: 1, maximum: 4 },
      },
      additionalProperties: false,
    },
  },
  async run(input, ctx): Promise<ToolResult> {
    const pathInput = getString(input, "path", { optional: true }) ?? ".";
    const depth = getInteger(input, "depth", { optional: true, min: 1, max: 4 }) ?? 2;
    const abs = await resolveWorkspacePath(ctx.cwd, pathInput);
    const info = await stat(abs).catch(() => undefined);
    if (!info) throw new ToolInputError(`Directory not found: ${pathInput}`);
    if (!info.isDirectory()) throw new ToolInputError(`Not a directory: ${pathInput}`);

    const lines: string[] = [];
    let count = 0;
    let truncated = false;

    async function visit(dir: string, level: number, prefix: string): Promise<void> {
      if (truncated || level > depth || ctx.signal?.aborted) return;
      let entries;
      try {
        entries = await readdir(dir, { withFileTypes: true });
      } catch {
        return;
      }
      entries.sort((a, b) => {
        if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.name.localeCompare(b.name);
      });

      for (const entry of entries) {
        if (entry.name === ".git") continue;
        if (count >= MAX_ENTRIES) {
          truncated = true;
          return;
        }
        const isDir = entry.isDirectory();
        lines.push(`${prefix}${entry.name}${isDir ? "/" : ""}`);
        count++;
        if (isDir && entry.name !== "node_modules") {
          await visit(path.join(dir, entry.name), level + 1, prefix + "  ");
        }
      }
    }

    const label = displayPath(ctx.cwd, abs) || ".";
    lines.push(`${label}/`);
    await visit(abs, 1, "  ");
    if (truncated) lines.push(`… [stopped after ${MAX_ENTRIES} entries]`);

    return {
      output: lines.join("\n"),
      summary: `listed ${label} (${count} entries)`,
    };
  },
};
