import { createInterface, type Interface } from "node:readline/promises";
import { runTurn } from "../agent.ts";
import { redactKey, type ResolvedConfig } from "../config.ts";
import { createSession, saveSession, type Session } from "../session.ts";
import { VERSION } from "../version.ts";
import { createApprover } from "./approval.ts";
import { style } from "./ansi.ts";
import {
  AssistantRenderer,
  formatApproval,
  formatToolEnd,
  formatToolStart,
} from "./render.ts";

export interface InteractiveOptions {
  config: ResolvedConfig;
  cwd: string;
  session: Session;
  resumed?: boolean;
}

function formatError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message.replaceAll("\n", "\n    ");
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && (error.name === "AbortError" || error.name === "TimeoutError");
}

function printHelp(): void {
  const lines = [
    ["/help", "show this help"],
    ["/model [name]", "show or change the model"],
    ["/clear", "start a new session"],
    ["/config", "show the current configuration"],
    ["/exit", "quit"],
  ];
  process.stdout.write(`  ${style.bold("Commands")}\n`);
  for (const [command, description] of lines) {
    process.stdout.write(`    ${style.cyan(command!.padEnd(16))} ${style.dim(description!)}\n`);
  }
  process.stdout.write("\n");
}

function printConfig(config: ResolvedConfig, session: Session, cwd: string): void {
  const rows: Array<[string, string]> = [
    ["model", config.model],
    ["base URL", config.baseUrl],
    ["API key", redactKey(config.apiKey)],
    ["max steps", String(config.maxSteps)],
    ["approvals", config.autoApprove ? "auto" : "ask"],
    ["session", session.id],
    ["cwd", cwd],
  ];
  for (const [key, value] of rows) {
    process.stdout.write(`    ${style.dim(key!.padEnd(10))} ${value}\n`);
  }
  if (config.configFiles.length > 0) {
    process.stdout.write(`    ${style.dim("config".padEnd(10))} ${config.configFiles.join(", ")}\n`);
  }
  process.stdout.write("\n");
}

export async function runInteractive(options: InteractiveOptions): Promise<void> {
  const { cwd, config } = options;
  let session = options.session;
  let messages = session.messages;
  let resumed = options.resumed ?? false;

  process.stdout.write(
    `\n  ${style.bold(style.cyan("minicode"))} ${style.dim(VERSION)}\n` +
      `  ${style.dim("model")} ${config.model}  ${style.dim("·")}  ${style.dim(cwd)}\n`,
  );
  if (resumed && messages.length > 0) {
    process.stdout.write(
      `  ${style.dim("resumed")} ${style.dim(session.id)} ${style.dim(`(${messages.length} messages)`)}\n`,
    );
  }
  process.stdout.write(`  ${style.dim("/help for commands · Ctrl+C interrupts")}\n\n`);

  let activeAbort: AbortController | null = null;
  let exiting = false;
  let promptRl: Interface | null = null;

  const onSigint = () => {
    if (activeAbort) {
      if (activeAbort.signal.aborted) {
        process.exit(130);
      }
      activeAbort.abort();
    } else {
      exiting = true;
      promptRl?.close();
    }
  };
  process.on("SIGINT", onSigint);

  const approver = createApprover({
    autoApprove: config.autoApprove,
    style,
    printDetails: (request) => process.stdout.write(formatApproval(request, style)),
  });

  const renderer = new AssistantRenderer({ style });

  async function ask(): Promise<string | undefined> {
    const rl = createInterface({
      input: process.stdin,
      output: process.stdout,
      historySize: 200,
      terminal: process.stdin.isTTY,
    });
    promptRl = rl;
    rl.on("SIGINT", () => {
      exiting = true;
      rl.close();
    });
    try {
      return await rl.question(style.cyan("› "));
    } catch {
      return undefined;
    } finally {
      promptRl = null;
      rl.close();
    }
  }

  try {
    while (!exiting) {
      const line = await ask();
      if (line === undefined) break;

      const input = line.trim();
      if (input === "") continue;

      if (input.startsWith("/")) {
        const [command, ...rest] = input.slice(1).split(/\s+/);
        switch (command) {
          case "exit":
          case "quit":
          case "q":
            exiting = true;
            break;
          case "help":
            printHelp();
            break;
          case "clear": {
            session = createSession(cwd, config.model);
            messages = session.messages;
            resumed = false;
            process.stdout.write(`  ${style.dim("new session")} ${session.id}\n\n`);
            break;
          }
          case "model": {
            const next = rest.join(" ").trim();
            if (next) {
              config.model = next;
              session.model = next;
              process.stdout.write(`  ${style.dim("model set to")} ${style.bold(next)}\n\n`);
            } else {
              process.stdout.write(`  ${config.model}\n\n`);
            }
            break;
          }
          case "config":
            printConfig(config, session, cwd);
            break;
          default:
            process.stdout.write(
              `  ${style.yellow("!")} unknown command ${style.bold("/" + command)} ${style.dim("— try /help")}\n\n`,
            );
        }
        continue;
      }

      const controller = new AbortController();
      activeAbort = controller;
      process.stdout.write("\n");

      try {
        await runTurn({
          config,
          cwd,
          messages,
          userText: input,
          signal: controller.signal,
          approve: approver.approve,
          hooks: {
            onAssistantStart: () => renderer.begin(),
            onTextDelta: (text) => renderer.delta(text),
            onAssistantEnd: () => {
              renderer.end();
            },
            onToolStart: ({ call, input: toolInput }) => {
              process.stdout.write(
                `  ${style.cyan("⚙")} ${style.dim(formatToolStart(call.function.name, toolInput))}\n`,
              );
            },
            onToolEnd: (info) => {
              const output = formatToolEnd(info, style);
              if (output) process.stdout.write(output + "\n");
            },
            onNotice: (level, message) => {
              const icon =
                level === "error" ? style.red("✗") : level === "warn" ? style.yellow("!") : style.cyan("·");
              process.stdout.write(`  ${icon} ${message}\n`);
            },
          },
        });
      } catch (error) {
        if (isAbortError(error) || controller.signal.aborted) {
          process.stdout.write(`\n  ${style.dim("interrupted")}\n`);
        } else {
          process.stdout.write(`\n  ${style.red("✗")} ${formatError(error)}\n`);
        }
      } finally {
        activeAbort = null;
        try {
          await saveSession(session);
        } catch (error) {
          process.stdout.write(
            `  ${style.yellow("!")} could not save session: ${(error as Error).message}\n`,
          );
        }
      }

      process.stdout.write("\n");
    }
  } finally {
    process.off("SIGINT", onSigint);
  }

  if (session.messages.some((message) => message.role === "user")) {
    process.stdout.write(
      style.dim(`  session ${session.id} · resume with `) +
        style.cyan("minicode --continue") +
        "\n\n",
    );
  }
}
