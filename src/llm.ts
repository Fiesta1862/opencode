import type { ResolvedConfig } from "./config.ts";
import type { ChatMessage, ChatResult, ToolCall, ToolDefinition, Usage } from "./types.ts";
import { VERSION } from "./version.ts";

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly body?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const MAX_RETRIES = 2;

/** Parse a single SSE event block into its data payload (multi-line data joined). */
export function parseSseEvent(block: string): string | undefined {
  const dataLines: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(":")) continue;
    if (!line.startsWith("data:")) continue;
    let value = line.slice(5);
    if (value.startsWith(" ")) value = value.slice(1);
    dataLines.push(value);
  }
  if (dataLines.length === 0) return undefined;
  return dataLines.join("\n");
}

function nextBoundary(buffer: string): { index: number; length: number } | null {
  const lf = buffer.indexOf("\n\n");
  const crlf = buffer.indexOf("\r\n\r\n");
  if (lf === -1 && crlf === -1) return null;
  if (crlf === -1 || (lf !== -1 && lf < crlf)) return { index: lf, length: 2 };
  return { index: crlf, length: 4 };
}

/** Yield the `data:` payload of each SSE event from a byte stream. */
export async function* sseEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      for (;;) {
        const boundary = nextBoundary(buffer);
        if (!boundary) break;
        const block = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary.length);
        const data = parseSseEvent(block);
        if (data !== undefined) yield data;
      }
    }

    buffer += decoder.decode();
    const data = parseSseEvent(buffer);
    if (data !== undefined) yield data;
  } finally {
    reader.releaseLock();
  }
}

function endpoint(baseUrl: string): string {
  return `${baseUrl.replace(/\/+$/, "")}/chat/completions`;
}

function providerMessage(body: string): string {
  try {
    const parsed = JSON.parse(body) as { error?: { message?: string } | string; message?: string };
    if (typeof parsed.error === "string") return parsed.error;
    if (parsed.error?.message) return parsed.error.message;
    if (parsed.message) return parsed.message;
  } catch {
    // fall through
  }
  return body.replace(/\s+/g, " ").trim().slice(0, 500);
}

function friendlyHttpError(status: number, body: string, url: string): ApiError {
  const detail = providerMessage(body);
  let hint = "";
  if (status === 401 || status === 403) {
    hint = " Authentication failed — check OPENAI_API_KEY / MINICODE_API_KEY.";
  } else if (status === 404) {
    hint = ` Endpoint not found at ${url} — check the base URL (it usually ends in /v1).`;
  } else if (status === 429) {
    hint = " Rate limited or out of quota — try again shortly.";
  }
  return new ApiError(
    `API request failed (${status}).${hint}${detail ? `\n${detail}` : ""}`,
    status,
    body,
  );
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      return;
    }
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      },
      { once: true },
    );
  });
}

export interface StreamChatOptions {
  config: ResolvedConfig;
  messages: ChatMessage[];
  tools?: ToolDefinition[];
  signal?: AbortSignal;
  onTextDelta?: (text: string) => void;
}

interface DeltaToolCall {
  index?: number;
  id?: string;
  type?: string;
  function?: { name?: string; arguments?: string };
}

export async function streamChat(options: StreamChatOptions): Promise<ChatResult> {
  const { config, messages, tools, signal, onTextDelta } = options;
  const url = endpoint(config.baseUrl);

  const body: Record<string, unknown> = {
    model: config.model,
    messages,
    stream: true,
  };
  if (config.temperature !== undefined) body.temperature = config.temperature;
  if (tools && tools.length > 0) {
    body.tools = tools.map((tool) => ({
      type: "function",
      function: {
        name: tool.name,
        description: tool.description,
        parameters: tool.parameters,
      },
    }));
    body.tool_choice = "auto";
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "text/event-stream",
    "User-Agent": `minicode/${VERSION}`,
  };
  if (config.apiKey) headers.Authorization = `Bearer ${config.apiKey}`;

  let response: Response | undefined;
  let attempt = 0;

  while (response === undefined) {
    try {
      response = await fetch(url, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
        signal,
      });
    } catch (err) {
      if (signal?.aborted) throw err;
      if (attempt < MAX_RETRIES) {
        await delay(500 * 2 ** attempt, signal);
        attempt++;
        continue;
      }
      throw new ApiError(
        `Could not reach ${url}: ${(err as Error).message}\nIs the server running and the base URL correct?`,
      );
    }

    if (response.ok) break;

    const text = await response.text().catch(() => "");
    const retryable = response.status === 429 || response.status >= 500;
    if (retryable && attempt < MAX_RETRIES) {
      attempt++;
      await delay(750 * 2 ** attempt, signal);
      response = undefined;
      continue;
    }
    throw friendlyHttpError(response.status, text, url);
  }

  if (!response.body) {
    throw new ApiError("The API returned an empty response body.");
  }

  let text = "";
  let finishReason: string | null = null;
  let usage: Usage | undefined;
  const toolCalls = new Map<number, { id: string; name: string; args: string }>();

  for await (const data of sseEvents(response.body)) {
    if (data === "[DONE]") break;
    if (!data.trim()) continue;

    let chunk: {
      error?: { message?: string } | string;
      choices?: Array<{
        delta?: { content?: string | null; tool_calls?: DeltaToolCall[] };
        finish_reason?: string | null;
      }>;
      usage?: Usage;
    };
    try {
      chunk = JSON.parse(data);
    } catch {
      // Some providers send keep-alive or non-JSON lines; ignore them.
      continue;
    }

    if (chunk.error) {
      const message = typeof chunk.error === "string" ? chunk.error : chunk.error.message;
      throw new ApiError(message ?? "The API returned a streaming error.", undefined, data);
    }

    const choice = chunk.choices?.[0];
    if (chunk.usage) usage = chunk.usage;
    if (!choice) continue;

    const delta = choice.delta;
    if (delta?.content) {
      text += delta.content;
      onTextDelta?.(delta.content);
    }

    if (Array.isArray(delta?.tool_calls)) {
      for (const part of delta.tool_calls) {
        const index = typeof part.index === "number" ? part.index : 0;
        let entry = toolCalls.get(index);
        if (!entry) {
          entry = { id: "", name: "", args: "" };
          toolCalls.set(index, entry);
        }
        if (part.id) entry.id = part.id;
        if (part.function?.name) entry.name = part.function.name;
        if (part.function?.arguments) entry.args += part.function.arguments;
      }
    }

    if (choice.finish_reason) finishReason = choice.finish_reason;
  }

  const calls: ToolCall[] = [];
  let n = 0;
  for (const [index, entry] of [...toolCalls.entries()].sort((a, b) => a[0] - b[0])) {
    if (!entry.name) continue;
    calls.push({
      id: entry.id || `call_${Date.now().toString(36)}_${index}_${n++}`,
      type: "function",
      function: { name: entry.name, arguments: entry.args || "{}" },
    });
  }

  const assistant: ChatMessage = {
    role: "assistant",
    content: text.length > 0 ? text : null,
  };
  if (calls.length > 0) assistant.tool_calls = calls;

  return { assistant, finishReason, usage };
}
