import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import type { ApprovalRequest, DiffLine, ToolResult } from "../types.ts";
import { displayPath, resolveWorkspacePath } from "../util/paths.ts";
import { makeDiff, truncateMiddle } from "../util/text.ts";
import { getBoolean, getInteger, getString, ToolInputError, type Tool, type ToolContext } from "./types.ts";

const MAX_READ_BYTES = 2 * 1024 * 1024;
const MAX_WRITE_BYTES = 5 * 1024 * 1024;

async function readTextFile(ctx: ToolContext, inputPath: string): Promise<{ abs: string; text: string }> {
  const abs = await resolveWorkspacePath(ctx.cwd, inputPath);
  const info = await stat(abs).catch(() => undefined);
  if (!info) throw new ToolInputError(`File not found: ${inputPath}`);
  if (info.isDirectory()) throw new ToolInputError(`Path is a directory, not a file: ${inputPath}`);
  if (info.size > MAX_READ_BYTES) {
    throw new ToolInputError(
      `File is too large to read (${(info.size / 1024 / 1024).toFixed(1)} MB; limit is 2 MB).`,
    );
  }
  const buffer = await readFile(abs);
  if (buffer.subarray(0, 8192).includes(0)) {
    throw new ToolInputError(`File appears to be binary: ${inputPath}`);
  }
  return { abs, text: buffer.toString("utf8") };
}

function clampDiff(lines: DiffLine[], max = 120): DiffLine[] {
  if (lines.length <= max) return lines;
  return [
    ...lines.slice(0, max),
    { type: "ctx", text: `… ${lines.length - max} more diff lines …` },
  ];
}

/** Read an existing file for diffing, skipping huge or binary files. */
async function readForDiff(abs: string): Promise<string | undefined> {
  const info = await stat(abs).catch(() => undefined);
  if (!info?.isFile() || info.size > MAX_READ_BYTES) return undefined;
  const buffer = await readFile(abs).catch(() => undefined);
  if (!buffer || buffer.subarray(0, 8192).includes(0)) return undefined;
  return buffer.toString("utf8");
}

export const readFileTool: Tool = {
  definition: {
    name: "read_file",
    description:
      "Read a text file from the workspace. Returns the raw contents. Use offset/limit for large files.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "Path relative to the workspace root." },
        offset: { type: "integer", description: "1-based line to start reading from.", minimum: 1 },
        limit: { type: "integer", description: "Maximum number of lines to return (default 2000).", minimum: 1, maximum: 5000 },
      },
      required: ["path"],
      additionalProperties: false,
    },
  },
  async run(input, ctx): Promise<ToolResult> {
    const inputPath = getString(input, "path");
    const offset = getInteger(input, "offset", { optional: true, min: 1 }) ?? 1;
    const limit = getInteger(input, "limit", { optional: true, min: 1, max: 5000 }) ?? 2000;

    const { abs, text } = await readTextFile(ctx, inputPath);
    const rel = displayPath(ctx.cwd, abs);
    if (text.length === 0) {
      return {
        output: "(empty file)",
        summary: `read ${rel} (0 lines)`,
      };
    }

    const lines = text.split("\n");
    const totalLines = text.endsWith("\n") && lines.length > 1 ? lines.length - 1 : lines.length;
    const lineLabel = `${totalLines} line${totalLines === 1 ? "" : "s"}`;

    if (offset > totalLines) {
      return {
        output: `[Offset ${offset} is past the end of the file (${lineLabel})]`,
        summary: `read ${rel} (${lineLabel})`,
      };
    }

    const start = offset - 1;
    const selected = lines.slice(start, start + limit);
    const shownTo = Math.min(start + selected.length, totalLines);

    let output = selected.join("\n");
    if (offset > 1 || shownTo < totalLines) {
      output += `\n\n[Showing lines ${start + 1}-${shownTo} of ${totalLines}]`;
    }

    return {
      output: truncateMiddle(output, 60_000),
      summary: `read ${rel} (${lineLabel})`,
    };
  },
};

export const writeFileTool: Tool = {
  needsApproval: true,
  definition: {
    name: "write_file",
    description:
      "Create or overwrite a file with the given content. Parent directories are created automatically. Prefer edit_file for small changes to existing files.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "Path relative to the workspace root." },
        content: { type: "string", description: "Full content to write." },
      },
      required: ["path", "content"],
      additionalProperties: false,
    },
  },
  async preview(input, ctx): Promise<ApprovalRequest> {
    const inputPath = getString(input, "path");
    const content = getString(input, "content", { allowEmpty: true });
    const abs = await resolveWorkspacePath(ctx.cwd, inputPath);
    const rel = displayPath(ctx.cwd, abs);
    const before = await readForDiff(abs);
    const lines = content === "" ? 0 : content.split("\n").length;

    if (before === undefined) {
      const preview = content.split("\n").slice(0, 40).join("\n");
      return {
        tool: "write_file",
        summary: `create ${rel} (${lines} lines)`,
        details: preview + (content.split("\n").length > 40 ? "\n…" : ""),
      };
    }

    return {
      tool: "write_file",
      summary: `overwrite ${rel} (${lines} lines)`,
      diff: clampDiff(makeDiff(before, content)),
    };
  },
  async run(input, ctx): Promise<ToolResult> {
    const inputPath = getString(input, "path");
    const content = getString(input, "content", { allowEmpty: true });

    if (Buffer.byteLength(content, "utf8") > MAX_WRITE_BYTES) {
      throw new ToolInputError("Refusing to write more than 5 MB in a single call.");
    }

    const abs = await resolveWorkspacePath(ctx.cwd, inputPath);
    const info = await stat(abs).catch(() => undefined);
    if (info?.isDirectory()) throw new ToolInputError(`Path is a directory: ${inputPath}`);

    const before = info ? await readForDiff(abs) : undefined;
    await mkdir(path.dirname(abs), { recursive: true });
    await writeFile(abs, content, "utf8");

    const rel = displayPath(ctx.cwd, abs);
    const lines = content === "" ? 0 : content.split("\n").length;
    const action = info ? "Overwrote" : "Created";
    return {
      output: `${action} ${rel} (${lines} line${lines === 1 ? "" : "s"}, ${Buffer.byteLength(content, "utf8")} bytes).`,
      summary: `${info ? "overwrote" : "created"} ${rel}`,
      diff: before === undefined ? undefined : clampDiff(makeDiff(before, content)),
    };
  },
};

function countOccurrences(haystack: string, needle: string): number {
  let count = 0;
  let index = 0;
  for (;;) {
    index = haystack.indexOf(needle, index);
    if (index === -1) return count;
    count++;
    index += needle.length;
  }
}

export const editFileTool: Tool = {
  needsApproval: true,
  definition: {
    name: "edit_file",
    description:
      "Replace an exact string in a file. old_string must match the file exactly, including whitespace and indentation. If old_string occurs more than once, set replace_all or include more surrounding context to make it unique. Read the file first.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "Path relative to the workspace root." },
        old_string: { type: "string", description: "Exact text to replace." },
        new_string: { type: "string", description: "Replacement text." },
        replace_all: { type: "boolean", description: "Replace every occurrence instead of exactly one." },
      },
      required: ["path", "old_string", "new_string"],
      additionalProperties: false,
    },
  },
  async preview(input, ctx): Promise<ApprovalRequest> {
    const inputPath = getString(input, "path");
    const oldString = getString(input, "old_string", { allowEmpty: true });
    const newString = getString(input, "new_string", { allowEmpty: true });
    const replaceAll = getBoolean(input, "replace_all", { optional: true }) ?? false;
    const { abs, text } = await readTextFile(ctx, inputPath);
    const count = countOccurrences(text, oldString);
    const rel = displayPath(ctx.cwd, abs);

    let next = text;
    if (count > 0 && (count === 1 || replaceAll)) {
      next = replaceAll ? text.split(oldString).join(newString) : text.replace(oldString, newString);
    }

    return {
      tool: "edit_file",
      summary: `edit ${rel} (${count} match${count === 1 ? "" : "es"})`,
      diff: clampDiff(makeDiff(text, next)),
    };
  },
  async run(input, ctx): Promise<ToolResult> {
    const inputPath = getString(input, "path");
    const oldString = getString(input, "old_string", { allowEmpty: true });
    const newString = getString(input, "new_string", { allowEmpty: true });
    const replaceAll = getBoolean(input, "replace_all", { optional: true }) ?? false;

    if (oldString === newString) {
      throw new ToolInputError("old_string and new_string are identical; nothing to change.");
    }
    if (oldString === "") {
      throw new ToolInputError("old_string must not be empty. Use write_file to create or replace a file.");
    }

    const { abs, text } = await readTextFile(ctx, inputPath);
    const count = countOccurrences(text, oldString);

    if (count === 0) {
      throw new ToolInputError(
        `old_string was not found in ${inputPath}. Read the file and copy the exact text, including indentation.`,
      );
    }
    if (count > 1 && !replaceAll) {
      throw new ToolInputError(
        `old_string occurs ${count} times in ${inputPath}. Add more surrounding context to make it unique, or set replace_all=true.`,
      );
    }

    const next = replaceAll ? text.split(oldString).join(newString) : text.replace(oldString, newString);
    await writeFile(abs, next, "utf8");

    const diff = makeDiff(text, next);
    const added = diff.filter((line) => line.type === "add").length;
    const removed = diff.filter((line) => line.type === "del").length;
    const rel = displayPath(ctx.cwd, abs);

    return {
      output: `Edited ${rel}: replaced ${replaceAll ? count : 1} occurrence${(replaceAll ? count : 1) === 1 ? "" : "s"} (+${added} -${removed}).`,
      summary: `edited ${rel} +${added} -${removed}`,
      diff: clampDiff(diff),
    };
  },
};
