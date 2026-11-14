#!/usr/bin/env bun
import { ConfigError, loadConfig, redactKey, requireApiKey, type ConfigFlags, type ResolvedConfig } from "./config.ts";
import {
  createSession,
  findSession,
  formatRelativeTime,
  listSessions,
  SessionError,
  type Session,
} from "./session.ts";
import { style } from "./ui/ansi.ts";
import { runInteractive } from "./ui/interactive.ts";
import { runOnce } from "./ui/run.ts";
import { VERSION } from "./version.ts";

class UsageError extends Error {}

interface Flags {
  help: boolean;
  version: boolean;
  model?: string;
  baseUrl?: string;
  apiKey?: string;
  maxSteps?: number;
  autoApprove?: boolean;
  continue?: boolean;
  session?: string;
}

interface ParsedArgs {
  command?: string;
  positionals: string[];
  flags: Flags;
}

const COMMANDS = new Set(["run", "sessions", "config", "help", "version"]);

function parsePositiveInt(value: string, name: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 200) {
    throw new UsageError(`${name} must be an integer between 1 and 200.`);
  }
  return parsed;
}

function parseArgs(argv: string[]): ParsedArgs {
  const flags: Flags = { help: false, version: false };
  const positionals: string[] = [];
  let command: string | undefined;

  const valueFor = (args: string[], index: number, name: string): [string, number] => {
    const value = args[index + 1];
    if (value === undefined) throw new UsageError(`Missing value for ${name}.`);
    return [value, index + 1];
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!;
    if (arg === "--") {
      for (let j = i + 1; j < argv.length; j++) positionals.push(argv[j]!);
      break;
    }

    const eq = arg.startsWith("--") ? arg.indexOf("=") : -1;
    const name = eq === -1 ? arg : arg.slice(0, eq);
    const inline = eq === -1 ? undefined : arg.slice(eq + 1);
    let value: string | undefined = inline;

    switch (name) {
      case "-h":
      case "--help":
        flags.help = true;
        break;
      case "-v":
      case "--version":
        flags.version = true;
        break;
      case "-y":
      case "--yes":
        flags.autoApprove = true;
        break;
      case "-c":
      case "--continue":
        flags.continue = true;
        break;
      case "-m":
      case "--model":
        if (value === undefined) [value, i] = valueFor(argv, i, name);
        flags.model = value;
        break;
      case "-s":
      case "--session":
        if (value === undefined) [value, i] = valueFor(argv, i, name);
        flags.session = value;
        break;
      case "--base-url":
        if (value === undefined) [value, i] = valueFor(argv, i, name);
        flags.baseUrl = value;
        break;
      case "--api-key":
        if (value === undefined) [value, i] = valueFor(argv, i, name);
        flags.apiKey = value;
        break;
      case "--max-steps":
        if (value === undefined) [value, i] = valueFor(argv, i, name);
        flags.maxSteps = parsePositiveInt(value, "--max-steps");
        break;
      default:
        if (name.startsWith("-")) throw new UsageError(`Unknown option: ${name}`);
        if (command === undefined) command = arg;
        else positionals.push(arg);
    }
  }

  if (command !== undefined && !COMMANDS.has(command)) {
    throw new UsageError(
      `Unknown command: ${command}\nRun ${style.cyan("minicode --help")} for usage.`,
    );
  }

  return { command, positionals, flags };
}

function printHelp(): void {
  const b = style.bold;
  const c = style.cyan;
  const d = style.dim;
  const out = process.stdout;
  out.write(
    [
      "",
      `  ${b(c("minicode"))} ${d(VERSION)} — a minimal AI coding agent for your terminal`,
      "",
      `  ${b("Usage")}`,
      `    ${c("minicode")}                     Start an interactive session`,
      `    ${c("minicode run [prompt]")}        Run one task and exit (reads stdin if no prompt)`,
      `    ${c("minicode sessions")}            List saved sessions for this directory`,
      `    ${c("minicode config")}              Show the resolved configuration`,
      "",
      `  ${b("Options")}`,
      `    ${c("-m, --model <name>")}           Model to use (default: gpt-4o-mini)`,
      `        ${c("--base-url <url>")}         OpenAI-compatible API root, e.g. https://api.openai.com/v1`,
      `        ${c("--api-key <key>")}          API key (prefer the OPENAI_API_KEY env var)`,
      `        ${c("--max-steps <n>")}          Max model turns per message (default: 25)`,
      `    ${c("-y, --yes")}                    Auto-approve file edits and commands`,
      `    ${c("-c, --continue")}               Resume the most recent session in this directory`,
      `    ${c("-s, --session <id>")}           Resume a specific session`,
      `    ${c("-h, --help")}                   Show this help`,
      `    ${c("-v, --version")}                Show the version`,
      "",
      `  ${b("Environment")}`,
      `    ${c("OPENAI_API_KEY")} or ${c("MINICODE_API_KEY")}     API key`,
      `    ${c("OPENAI_BASE_URL")} or ${c("MINICODE_BASE_URL")}   API root`,
      `    ${c("MINICODE_MODEL")}                      Default model`,
      `    ${c("MINICODE_MAX_STEPS")}                  Default max steps`,
      `    ${c("MINICODE_AUTO_APPROVE")}               Set to 1 to auto-approve actions`,
      `    ${c("NO_COLOR")}                            Disable colors`,
      "",
      `  ${b("Configuration")}`,
      `    Project: ${d("./minicode.json")}   Global: ${d("~/.config/minicode/config.json")}`,
      `    Keys: ${d("model, base_url, api_key, max_steps, temperature, auto_approve, system_prompt_extra")}`,
      "",
      `  ${b("Examples")}`,
      `    ${d("$")} minicode`,
      `    ${d("$")} minicode run "add a --verbose flag to src/cli.ts"`,
      `    ${d("$")} minicode run --yes "fix the failing tests"`,
      `    ${d("$")} minicode --continue`,
      "",
      `  ${b("Safety")}`,
      `    File edits and shell commands ask for approval by default. Use ${c("--yes")} to`,
      `    approve everything for non-interactive runs, and only in repositories you trust.`,
      "",
    ].join("\n"),
  );
}

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : (chunk as Buffer));
  }
  return Buffer.concat(chunks).toString("utf8");
}

function printResolvedConfig(config: ResolvedConfig, cwd: string): void {
  const rows: Array<[string, string]> = [
    ["model", config.model],
    ["base URL", config.baseUrl],
    ["API key", redactKey(config.apiKey)],
    ["max steps", String(config.maxSteps)],
    ["temperature", config.temperature === undefined ? "provider default" : String(config.temperature)],
    ["approvals", config.autoApprove ? "auto" : "ask"],
    ["cwd", cwd],
  ];
  for (const [key, value] of rows) {
    process.stdout.write(`  ${style.dim(key!.padEnd(12))}${value}\n`);
  }
  if (config.configFiles.length > 0) {
    process.stdout.write(`  ${style.dim("config".padEnd(12))}${config.configFiles.join(", ")}\n`);
  }
  if (!config.apiKey) {
    process.stdout.write(
      `\n  ${style.yellow("!")} No API key configured. Set ${style.cyan("OPENAI_API_KEY")} or add "api_key" to minicode.json.\n`,
    );
  }
  process.stdout.write("\n");
}

async function printSessions(cwd: string): Promise<void> {
  const sessions = await listSessions(cwd);
  if (sessions.length === 0) {
    process.stdout.write(style.dim("  No saved sessions for this directory.\n\n"));
    return;
  }

  process.stdout.write(`\n  ${style.bold("ID".padEnd(20))}${style.bold("Updated".padEnd(12))}${style.bold("Prompt")}\n`);
  for (const session of sessions.slice(0, 20)) {
    process.stdout.write(
      `  ${style.cyan(session.id.padEnd(20))}${style.dim(formatRelativeTime(session.updatedAt).padEnd(12))}${session.preview}\n`,
    );
  }
  process.stdout.write(
    `\n  ${style.dim("Resume with")} ${style.cyan("minicode -s <id>")} ${style.dim("or")} ${style.cyan("minicode --continue")}\n\n`,
  );
}

async function resolveSession(
  cwd: string,
  config: ResolvedConfig,
  flags: Flags,
): Promise<{ session: Session; resumed: boolean }> {
  if (flags.session) {
    const session = await findSession(cwd, flags.session);
    session.model = config.model;
    return { session, resumed: true };
  }

  if (flags.continue) {
    try {
      const session = await findSession(cwd, "latest");
      session.model = config.model;
      return { session, resumed: true };
    } catch (error) {
      if (!(error instanceof SessionError)) throw error;
      process.stderr.write(style.dim("  no previous session — starting a new one\n"));
    }
  }

  return { session: createSession(cwd, config.model), resumed: false };
}

async function main(): Promise<void> {
  const parsed = parseArgs(process.argv.slice(2));
  const { flags } = parsed;

  if (flags.version || parsed.command === "version") {
    process.stdout.write(`minicode ${VERSION}\n`);
    return;
  }
  if (flags.help || parsed.command === "help") {
    printHelp();
    return;
  }

  const cwd = process.cwd();
  const configFlags: ConfigFlags = {
    model: flags.model,
    baseUrl: flags.baseUrl,
    apiKey: flags.apiKey,
    maxSteps: flags.maxSteps,
    autoApprove: flags.autoApprove,
  };
  const config = await loadConfig(cwd, configFlags);

  if (parsed.command === "config") {
    printResolvedConfig(config, cwd);
    return;
  }
  if (parsed.command === "sessions") {
    await printSessions(cwd);
    return;
  }

  if (parsed.command === "run") {
    let prompt = parsed.positionals.join(" ").trim();
    if (!prompt && !process.stdin.isTTY) prompt = (await readStdin()).trim();
    if (!prompt) {
      throw new UsageError('No prompt given. Usage: minicode run "your task"');
    }

    requireApiKey(config);
    const { session } = await resolveSession(cwd, config, flags);
    process.exitCode = await runOnce({ config, cwd, prompt, session });
    return;
  }

  // Default: interactive session.
  if (parsed.positionals.length > 0) {
    throw new UsageError(
      `Unexpected argument: ${parsed.positionals[0]}\nDid you mean ${style.cyan(`minicode run ${JSON.stringify(parsed.positionals.join(" "))}`)}?`,
    );
  }

  if (!process.stdin.isTTY) {
    const prompt = (await readStdin()).trim();
    if (!prompt) {
      throw new UsageError(
        "No prompt given and stdin is not a terminal.\nUse minicode run \"your task\" or pipe a prompt in.",
      );
    }
    requireApiKey(config);
    const { session } = await resolveSession(cwd, config, flags);
    process.exitCode = await runOnce({ config, cwd, prompt, session });
    return;
  }

  requireApiKey(config);
  const { session, resumed } = await resolveSession(cwd, config, flags);
  await runInteractive({ config, cwd, session, resumed });
}

if (import.meta.main) {
  main().catch((error: unknown) => {
    if (error instanceof UsageError) {
      process.stderr.write(`\n  ${style.red("error:")} ${error.message}\n\n`);
      process.exitCode = 2;
      return;
    }
    if (error instanceof ConfigError || error instanceof SessionError) {
      process.stderr.write(`\n  ${style.red("error:")} ${error.message}\n\n`);
      process.exitCode = 1;
      return;
    }
    const message = error instanceof Error ? error.stack ?? error.message : String(error);
    process.stderr.write(`\n  ${style.red("error:")} ${message}\n\n`);
    process.exitCode = 1;
  });
}
