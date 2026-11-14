import { stat } from "node:fs/promises";
import type { ApprovalRequest, ToolResult } from "../types.ts";
import { displayPath, resolveWorkspacePath } from "../util/paths.ts";
import { oneLine, truncateMiddle } from "../util/text.ts";
import { getInteger, getString, ToolInputError, type Tool } from "./types.ts";

const DEFAULT_TIMEOUT_MS = 120_000;
const MAX_TIMEOUT_MS = 600_000;

async function readLimited(
  stream: ReadableStream<Uint8Array>,
  limit: number,
): Promise<{ text: string; truncated: boolean }> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let text = "";
  let truncated = false;

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      if (text.length <= limit) {
        text += decoder.decode(value, { stream: true });
        if (text.length > limit) {
          text = text.slice(0, limit);
          truncated = true;
        }
      } else {
        truncated = true;
      }
    }
    if (!truncated) {
      text += decoder.decode();
      if (text.length > limit) {
        text = text.slice(0, limit);
        truncated = true;
      }
    }
  } catch {
    // Stream was interrupted (e.g. process killed); keep what we have.
  } finally {
    reader.releaseLock();
  }

  return { text, truncated };
}

function shellCommand(command: string): string[] {
  if (process.platform === "win32") {
    const shell = Bun.which("pwsh") ?? Bun.which("powershell") ?? "powershell.exe";
    return [shell, "-NoProfile", "-NonInteractive", "-Command", command];
  }
  const shell = Bun.which("bash") ?? Bun.which("sh") ?? "sh";
  return [shell, "-c", command];
}

export const runCommandTool: Tool = {
  needsApproval: true,
  definition: {
    name: "run_command",
    description:
      "Run a shell command in the workspace and return its exit code, stdout and stderr. Commands are non-interactive (stdin is closed). Use for builds, tests, git, package managers, etc. Prefer dedicated file tools over cat/sed.",
    parameters: {
      type: "object",
      properties: {
        command: { type: "string", description: "Shell command to execute." },
        timeout_ms: {
          type: "integer",
          description: "Timeout in milliseconds (default 120000, max 600000).",
          minimum: 1000,
          maximum: MAX_TIMEOUT_MS,
        },
        workdir: { type: "string", description: "Working directory relative to the workspace root." },
      },
      required: ["command"],
      additionalProperties: false,
    },
  },
  async preview(input, ctx): Promise<ApprovalRequest> {
    const command = getString(input, "command");
    const workdir = getString(input, "workdir", { optional: true });
    const abs = workdir ? await resolveWorkspacePath(ctx.cwd, workdir) : ctx.cwd;
    return {
      tool: "run_command",
      summary: oneLine(command, 100),
      details: `$ ${command}\n\ncwd: ${displayPath(ctx.cwd, abs) || "."}`,
    };
  },
  async run(input, ctx): Promise<ToolResult> {
    const command = getString(input, "command");
    const timeout = getInteger(input, "timeout_ms", {
      optional: true,
      min: 1000,
      max: MAX_TIMEOUT_MS,
    }) ?? DEFAULT_TIMEOUT_MS;
    const workdirInput = getString(input, "workdir", { optional: true });

    let cwd = ctx.cwd;
    if (workdirInput) {
      cwd = await resolveWorkspacePath(ctx.cwd, workdirInput);
      const info = await stat(cwd).catch(() => undefined);
      if (!info) throw new ToolInputError(`Working directory not found: ${workdirInput}`);
      if (!info.isDirectory()) throw new ToolInputError(`Not a directory: ${workdirInput}`);
    }

    const env: Record<string, string> = {};
    for (const [key, value] of Object.entries(process.env)) {
      if (value !== undefined) env[key] = value;
    }
    env.MINICODE = "1";
    env.NO_COLOR ??= "1";
    env.TERM = "dumb";
    env.PAGER = "cat";
    env.GIT_PAGER = "cat";

    let proc: ReturnType<typeof Bun.spawn>;
    try {
      proc = Bun.spawn(shellCommand(command), {
        cwd,
        env,
        stdin: "ignore",
        stdout: "pipe",
        stderr: "pipe",
      });
    } catch (err) {
      return {
        output: `Failed to start command: ${(err as Error).message}`,
        summary: "failed to start",
        isError: true,
      };
    }

    let timedOut = false;
    let cancelled = false;

    const kill = () => {
      try {
        proc.kill();
      } catch {
        // already exited
      }
      setTimeout(() => {
        try {
          proc.kill(9);
        } catch {
          // already exited
        }
      }, 1500);
    };

    const timer = setTimeout(() => {
      timedOut = true;
      kill();
    }, timeout);

    const onAbort = () => {
      cancelled = true;
      kill();
    };
    ctx.signal?.addEventListener("abort", onAbort, { once: true });
    if (ctx.signal?.aborted) onAbort();

    const [stdout, stderr, exitCode] = await Promise.all([
      readLimited(proc.stdout as ReadableStream<Uint8Array>, 60_000),
      readLimited(proc.stderr as ReadableStream<Uint8Array>, 40_000),
      proc.exited,
    ]);

    clearTimeout(timer);
    ctx.signal?.removeEventListener("abort", onAbort);

    const parts: string[] = [`exit code: ${exitCode}`];
    if (timedOut) parts.push(`[timed out after ${timeout} ms]`);
    if (cancelled) parts.push("[cancelled by user]");
    if (stdout.text.trim()) {
      parts.push(`--- stdout ---\n${stdout.text.replace(/\s+$/, "")}${stdout.truncated ? "\n[stdout truncated]" : ""}`);
    }
    if (stderr.text.trim()) {
      parts.push(`--- stderr ---\n${stderr.text.replace(/\s+$/, "")}${stderr.truncated ? "\n[stderr truncated]" : ""}`);
    }
    if (!stdout.text.trim() && !stderr.text.trim()) {
      parts.push("(no output)");
    }

    return {
      output: truncateMiddle(parts.join("\n"), 30_000),
      summary: `exit ${exitCode} · ${oneLine(command, 80)}`,
      isError: timedOut || cancelled || exitCode !== 0,
    };
  },
};
