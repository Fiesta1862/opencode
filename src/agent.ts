import os from "node:os";
import type { ResolvedConfig } from "./config.ts";
import { streamChat } from "./llm.ts";
import { getTool, toolDefinitions, ToolInputError, type ToolContext } from "./tools/index.ts";
import type { Approve, ApprovalRequest, ChatMessage, ToolCall, ToolResult } from "./types.ts";
import { oneLine, truncateMiddle } from "./util/text.ts";

export interface ToolStartInfo {
  call: ToolCall;
  input: Record<string, unknown>;
}

export interface ToolEndInfo {
  call: ToolCall;
  result?: ToolResult;
  denied?: boolean;
  error?: string;
}

export interface AgentHooks {
  onTextDelta?: (text: string) => void;
  onAssistantStart?: () => void;
  onAssistantEnd?: () => void;
  onToolStart?: (info: ToolStartInfo) => void;
  onToolEnd?: (info: ToolEndInfo) => void;
  onNotice?: (level: "info" | "warn" | "error", message: string) => void;
}

export interface RunTurnOptions {
  config: ResolvedConfig;
  cwd: string;
  messages: ChatMessage[];
  userText?: string;
  hooks?: AgentHooks;
  approve?: Approve;
  signal?: AbortSignal;
}

const TOOL_OUTPUT_LIMIT = 30_000;

export function buildSystemPrompt(cwd: string, config: ResolvedConfig): string {
  const date = new Date().toISOString().slice(0, 10);
  const lines = [
    "You are minicode, an AI coding agent running in a terminal.",
    "",
    "Environment:",
    `- Working directory: ${cwd}`,
    `- Platform: ${process.platform} (${process.arch})`,
    `- Date: ${date}`,
    `- Model: ${config.model}`,
    `- Approvals: ${config.autoApprove ? "actions are auto-approved" : "file edits and commands require user approval"}`,
    "",
    "You help with software engineering tasks: exploring and searching code, editing files, running commands, debugging, and explaining how things work.",
    "",
    "Rules:",
    "- Use tools to inspect the workspace. Never guess file contents, paths, or command output.",
    "- Read a file before editing it, and make minimal edits that match the surrounding style.",
    "- After making changes, verify them when practical (run tests, type-check, or the relevant command).",
    "- The user may deny file edits or commands. If denied, respect it, do not retry the same action, and explain or propose an alternative.",
    "- Avoid destructive commands (rm -rf, git reset --hard, force pushes) unless the user explicitly asks for them.",
    "- Keep responses concise and conversational. Use inline code for paths and identifiers. Do not dump large files or diffs back to the user.",
    "- If a tool fails, read the error and adapt instead of repeating the same call.",
    "- You cannot access files outside the workspace.",
    "- When the task is done, briefly summarize what changed and anything the user should verify.",
  ];
  if (config.systemPromptExtra?.trim()) {
    lines.push("", "Additional instructions:", config.systemPromptExtra.trim());
  }
  return lines.join("\n");
}

function summarizeArgs(name: string, input: Record<string, unknown>): string {
  if (typeof input.path === "string") return `${name} ${input.path}`;
  if (typeof input.command === "string") return oneLine(input.command, 100);
  if (typeof input.pattern === "string") return `${name} ${input.pattern}`;
  return name;
}

function abortError(): Error {
  const error = new Error("Aborted");
  error.name = "AbortError";
  return error;
}

export async function runTurn(options: RunTurnOptions): Promise<void> {
  const { config, cwd, messages, hooks = {}, approve, signal } = options;

  if (messages.length === 0 || messages[0]?.role !== "system") {
    messages.unshift({ role: "system", content: buildSystemPrompt(cwd, config) });
  }
  if (options.userText !== undefined) {
    messages.push({ role: "user", content: options.userText });
  }

  const deny: Approve = async () => false;
  const approveAction = approve ?? deny;

  const ctx: ToolContext = {
    cwd,
    signal,
    approve: approveAction,
  };

  for (let step = 0; step < config.maxSteps; step++) {
    if (signal?.aborted) throw abortError();

    hooks.onAssistantStart?.();
    let result;
    try {
      result = await streamChat({
        config,
        messages,
        tools: toolDefinitions,
        signal,
        onTextDelta: (text) => hooks.onTextDelta?.(text),
      });
    } finally {
      hooks.onAssistantEnd?.();
    }

    messages.push(result.assistant);
    const calls = result.assistant.tool_calls ?? [];

    if (calls.length === 0) {
      if (!result.assistant.content) {
        hooks.onNotice?.("warn", "The model returned an empty response.");
      } else if (result.finishReason === "length") {
        hooks.onNotice?.(
          "warn",
          "The response was cut off because it reached the model's output limit.",
        );
      }
      return;
    }

    for (const call of calls) {
      if (signal?.aborted) throw abortError();

      const name = call.function.name;
      const tool = getTool(name);
      const fail = (message: string, summary = "error") => {
        hooks.onToolEnd?.({
          call,
          result: { output: message, isError: true, summary },
        });
        messages.push({
          role: "tool",
          tool_call_id: call.id,
          name,
          content: truncateMiddle(message, TOOL_OUTPUT_LIMIT),
        });
      };

      let input: Record<string, unknown>;
      try {
        const parsed = JSON.parse(call.function.arguments || "{}") as unknown;
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
          throw new Error("expected a JSON object");
        }
        input = parsed as Record<string, unknown>;
      } catch (err) {
        fail(
          `Invalid JSON in arguments for tool "${name}": ${(err as Error).message}. Re-issue the tool call with valid JSON.`,
          "invalid arguments",
        );
        continue;
      }

      if (!tool) {
        fail(
          `Unknown tool "${name}". Available tools: ${toolDefinitions.map((t) => t.name).join(", ")}.`,
          "unknown tool",
        );
        continue;
      }

      hooks.onToolStart?.({ call, input });

      let approval: ApprovalRequest = { tool: name, summary: summarizeArgs(name, input) };
      if (tool.needsApproval) {
        if (tool.preview) {
          try {
            approval = await tool.preview(input, ctx);
          } catch (err) {
            const message = `Error: ${(err as Error).message}`;
            hooks.onToolEnd?.({
              call,
              result: { output: message, isError: true, summary: "invalid arguments" },
            });
            messages.push({
              role: "tool",
              tool_call_id: call.id,
              name,
              content: message,
            });
            continue;
          }
        }

        let approved = false;
        try {
          approved = await approveAction(approval);
        } catch {
          approved = false;
        }
        if (!approved) {
          const message =
            "Permission denied by the user. Do not retry this action; explain what you wanted to do or ask how to proceed.";
          hooks.onToolEnd?.({ call, denied: true, result: { output: message, isError: true, summary: "denied" } });
          messages.push({
            role: "tool",
            tool_call_id: call.id,
            name,
            content: message,
          });
          continue;
        }
      }

      let toolResult: ToolResult;
      try {
        toolResult = await tool.run(input, ctx);
      } catch (err) {
        const isInput = err instanceof ToolInputError;
        toolResult = {
          output: `Error: ${(err as Error).message}`,
          summary: isInput ? "invalid arguments" : "failed",
          isError: true,
        };
      }

      hooks.onToolEnd?.({ call, result: toolResult });
      messages.push({
        role: "tool",
        tool_call_id: call.id,
        name,
        content: truncateMiddle(toolResult.output, TOOL_OUTPUT_LIMIT),
      });
    }
  }

  hooks.onNotice?.(
    "warn",
    `Stopped after ${config.maxSteps} agent steps. The task may be incomplete — send another message to continue.`,
  );
}
