import { runTurn } from "../agent.ts";
import type { ResolvedConfig } from "../config.ts";
import { saveSession, type Session } from "../session.ts";
import { createApprover } from "./approval.ts";
import { createStyle, supportsColor } from "./ansi.ts";
import { formatApproval, formatToolEnd, formatToolStart, Spinner } from "./render.ts";

export interface RunOnceOptions {
  config: ResolvedConfig;
  cwd: string;
  prompt: string;
  session: Session;
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && (error.name === "AbortError" || error.name === "TimeoutError");
}

export async function runOnce(options: RunOnceOptions): Promise<number> {
  const { config, cwd, prompt, session } = options;
  const stderrStyle = createStyle(supportsColor(process.stderr));
  const spinner = new Spinner("thinking", process.stderr, stderrStyle);

  const approver = createApprover({
    autoApprove: config.autoApprove,
    style: stderrStyle,
    output: process.stderr,
    printDetails: (request) => process.stderr.write(formatApproval(request, stderrStyle)),
  });

  const controller = new AbortController();
  const onSigint = () => {
    if (controller.signal.aborted) process.exit(130);
    process.stderr.write(stderrStyle.dim("\n  interrupting…\n"));
    controller.abort();
  };
  process.on("SIGINT", onSigint);

  let lastChar = "\n";
  const writeStdout = (text: string) => {
    if (text.length === 0) return;
    process.stdout.write(text);
    lastChar = text[text.length - 1]!;
  };

  let exitCode = 0;
  try {
    await runTurn({
      config,
      cwd,
      messages: session.messages,
      userText: prompt,
      signal: controller.signal,
      approve: approver.approve,
      hooks: {
        onAssistantStart: () => spinner.start(),
        onTextDelta: (text) => {
          spinner.stop();
          writeStdout(text);
        },
        onAssistantEnd: () => spinner.stop(),
        onToolStart: ({ call, input }) => {
          spinner.stop();
          process.stderr.write(
            `${stderrStyle.cyan("⚙")} ${stderrStyle.dim(formatToolStart(call.function.name, input))}\n`,
          );
        },
        onToolEnd: (info) => {
          const output = formatToolEnd(info, stderrStyle);
          if (output) process.stderr.write(output + "\n");
        },
        onNotice: (level, message) => {
          const icon =
            level === "error"
              ? stderrStyle.red("✗")
              : level === "warn"
                ? stderrStyle.yellow("!")
                : stderrStyle.cyan("·");
          process.stderr.write(`${icon} ${message}\n`);
        },
      },
    });
  } catch (error) {
    spinner.stop();
    if (isAbortError(error) || controller.signal.aborted) {
      process.stderr.write(stderrStyle.dim("\n  interrupted\n"));
      exitCode = 130;
    } else {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`\n${stderrStyle.red("✗")} ${message.replaceAll("\n", "\n  ")}\n`);
      exitCode = 1;
    }
  } finally {
    process.off("SIGINT", onSigint);
    spinner.stop();
    if (lastChar !== "\n") process.stdout.write("\n");
    try {
      await saveSession(session);
    } catch (error) {
      process.stderr.write(
        `${stderrStyle.yellow("!")} could not save session: ${(error as Error).message}\n`,
      );
    }
  }

  return exitCode;
}
