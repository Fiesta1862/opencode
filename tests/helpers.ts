import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import type { ResolvedConfig } from "../src/config.ts";

export async function tempDir(prefix = "minicode-test-"): Promise<string> {
  return mkdtemp(path.join(tmpdir(), prefix));
}

export function makeConfig(baseUrl: string, overrides: Partial<ResolvedConfig> = {}): ResolvedConfig {
  return {
    model: "test-model",
    baseUrl,
    apiKey: "test-key",
    maxSteps: 6,
    autoApprove: false,
    configFiles: [],
    ...overrides,
  };
}

export interface MockToolCall {
  name: string;
  arguments?: unknown;
  id?: string;
}

export interface MockModelResponse {
  content?: string;
  toolCalls?: MockToolCall[];
}

export interface MockModel {
  url: string;
  requests: Array<Record<string, unknown>>;
  stop: () => void;
}

/**
 * A tiny OpenAI-compatible chat-completions server that streams canned
 * responses as SSE. Tool-call arguments are deliberately split across chunks
 * to exercise the streaming accumulator.
 */
export function startMockModel(responses: MockModelResponse[]): MockModel {
  const requests: Array<Record<string, unknown>> = [];
  let call = 0;

  const server = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    fetch: async (request) => {
      const body = (await request.json()) as Record<string, unknown>;
      requests.push(body);
      const response = responses[Math.min(call, responses.length - 1)]!;
      call++;

      const chunks: string[] = [];
      const push = (payload: unknown) => {
        chunks.push(`data: ${JSON.stringify(payload)}\n\n`);
      };

      if (response.content) {
        push({ choices: [{ index: 0, delta: { role: "assistant", content: response.content } }] });
      }

      for (const [index, toolCall] of (response.toolCalls ?? []).entries()) {
        const args =
          typeof toolCall.arguments === "string"
            ? toolCall.arguments
            : JSON.stringify(toolCall.arguments ?? {});
        const midpoint = Math.floor(args.length / 2);
        push({
          choices: [
            {
              index: 0,
              delta: {
                tool_calls: [
                  {
                    index,
                    id: toolCall.id ?? `call_${index}`,
                    type: "function",
                    function: { name: toolCall.name, arguments: args.slice(0, midpoint) },
                  },
                ],
              },
            },
          ],
        });
        push({
          choices: [
            {
              index: 0,
              delta: { tool_calls: [{ index, function: { arguments: args.slice(midpoint) } }] },
            },
          ],
        });
      }

      push({
        choices: [
          {
            index: 0,
            delta: {},
            finish_reason: response.toolCalls?.length ? "tool_calls" : "stop",
          },
        ],
      });
      chunks.push("data: [DONE]\n\n");

      return new Response(chunks.join(""), {
        headers: { "Content-Type": "text/event-stream" },
      });
    },
  });

  return {
    url: `http://127.0.0.1:${server.port}/v1`,
    requests,
    stop: () => server.stop(true),
  };
}
