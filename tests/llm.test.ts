import { afterEach, describe, expect, test } from "bun:test";
import { ApiError, parseSseEvent, sseEvents, streamChat } from "../src/llm.ts";
import { makeConfig, startMockModel } from "./helpers.ts";

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
}

async function collect(stream: ReadableStream<Uint8Array>): Promise<string[]> {
  const values: string[] = [];
  for await (const value of sseEvents(stream)) values.push(value);
  return values;
}

describe("sse parsing", () => {
  test("parses data lines and joins multiline payloads", () => {
    expect(parseSseEvent('event: message\ndata: {"a":1}\ndata: {"b":2}')).toBe('{"a":1}\n{"b":2}');
    expect(parseSseEvent(": keep-alive")).toBeUndefined();
    expect(parseSseEvent("event: message")).toBeUndefined();
    expect(parseSseEvent("data:no-space")).toBe("no-space");
  });

  test("handles events split across chunks, CRLF, and boundaries", async () => {
    const events = await collect(
      streamOf([
        'data: {"n":1}\r\n\r\ndata: {"n"',
        ':2}\n\n',
        "data: [DONE]\n\n",
      ]),
    );
    expect(events).toEqual(['{"n":1}', '{"n":2}', "[DONE]"]);
  });

  test("flushes a trailing event without a blank line", async () => {
    expect(await collect(streamOf(['data: {"n":1}']))).toEqual(['{"n":1}']);
  });
});

describe("streamChat", () => {
  const servers: Array<{ stop: () => void }> = [];

  afterEach(() => {
    while (servers.length > 0) servers.pop()!.stop();
  });

  test("streams text and assembles tool calls from split chunks", async () => {
    const mock = startMockModel([
      {
        content: "Hello",
        toolCalls: [{ name: "read_file", arguments: { path: "a.txt" } }],
      },
    ]);
    servers.push(mock);

    const deltas: string[] = [];
    const result = await streamChat({
      config: makeConfig(mock.url),
      messages: [{ role: "user", content: "hi" }],
      tools: [
        {
          name: "read_file",
          description: "read",
          parameters: { type: "object", properties: { path: { type: "string" } } },
        },
      ],
      onTextDelta: (text) => deltas.push(text),
    });

    expect(deltas.join("")).toBe("Hello");
    expect(result.assistant.content).toBe("Hello");
    expect(result.assistant.tool_calls).toHaveLength(1);
    expect(result.assistant.tool_calls![0]!.function.name).toBe("read_file");
    expect(JSON.parse(result.assistant.tool_calls![0]!.function.arguments)).toEqual({ path: "a.txt" });
    expect(result.finishReason).toBe("tool_calls");

    const sent = mock.requests[0]! as { tools?: Array<{ function: { name: string } }> };
    expect(sent.tools?.[0]?.function.name).toBe("read_file");
  });

  test("returns a plain assistant message when there are no tool calls", async () => {
    const mock = startMockModel([{ content: "Just text." }]);
    servers.push(mock);

    const result = await streamChat({
      config: makeConfig(mock.url),
      messages: [{ role: "user", content: "hi" }],
    });

    expect(result.assistant).toEqual({ role: "assistant", content: "Just text." });
    expect(result.finishReason).toBe("stop");
  });

  test("throws a friendly error on HTTP failures", async () => {
    const server = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch: () =>
        new Response(JSON.stringify({ error: { message: "bad key" } }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
    });
    servers.push({ stop: () => server.stop(true) });

    const error = await streamChat({
      config: makeConfig(`http://127.0.0.1:${server.port}/v1`),
      messages: [{ role: "user", content: "hi" }],
    }).catch((err: unknown) => err);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect((error as ApiError).message).toContain("bad key");
  });

  test("retries a 500 before succeeding", async () => {
    let calls = 0;
    const server = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch: () => {
        calls++;
        if (calls === 1) return new Response("boom", { status: 500 });
        return new Response(
          'data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n',
          { headers: { "Content-Type": "text/event-stream" } },
        );
      },
    });
    servers.push({ stop: () => server.stop(true) });

    const result = await streamChat({
      config: makeConfig(`http://127.0.0.1:${server.port}/v1`, { apiKey: undefined }),
      messages: [{ role: "user", content: "hi" }],
    });

    expect(result.assistant.content).toBe("ok");
    expect(calls).toBe(2);
  });
});
