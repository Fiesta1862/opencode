import { stat } from "node:fs/promises";
import path from "node:path";
import type { ToolResult } from "../types.ts";
import { displayPath, resolveWorkspacePath } from "../util/paths.ts";
import { getString, ToolInputError, type Tool } from "./types.ts";

const MAX_RESULTS = 300;

export const globTool: Tool = {
  definition: {
    name: "glob",
    description:
      "Find files by glob pattern, e.g. \"**/*.ts\" or \"src/**/test_*.py\". Returns paths relative to the workspace root, sorted. Skips node_modules and .git.",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Glob pattern (supports **, *, ?, {a,b})." },
        path: { type: "string", description: "Directory to search in. Defaults to the workspace root." },
      },
      required: ["pattern"],
      additionalProperties: false,
    },
  },
  async run(input, ctx): Promise<ToolResult> {
    const pattern = getString(input, "pattern");
    const baseInput = getString(input, "path", { optional: true }) ?? ".";
    const absBase = await resolveWorkspacePath(ctx.cwd, baseInput);
    const info = await stat(absBase).catch(() => undefined);
    if (!info) throw new ToolInputError(`Directory not found: ${baseInput}`);
    if (!info.isDirectory()) throw new ToolInputError(`Not a directory: ${baseInput}`);

    const glob = new Bun.Glob(pattern);
    const results: string[] = [];
    let truncated = false;

    for await (const match of glob.scan({ cwd: absBase, onlyFiles: true, dot: true })) {
      if (ctx.signal?.aborted) break;
      const normalized = match.replaceAll("\\", "/");
      if (normalized.split("/").some((part) => part === "node_modules" || part === ".git")) continue;
      const rel = displayPath(ctx.cwd, path.join(absBase, match));
      results.push(rel);
      if (results.length >= MAX_RESULTS) {
        truncated = true;
        break;
      }
    }

    results.sort();
    let output = results.length === 0 ? `No files matched ${pattern}.` : results.join("\n");
    if (truncated) output += `\n\n[Stopped after ${MAX_RESULTS} results]`;
    return {
      output,
      summary: `${results.length} file${results.length === 1 ? "" : "s"} matched ${pattern}`,
    };
  },
};
