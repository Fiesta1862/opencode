import { afterEach, describe, expect, test } from "bun:test";
import { readFile, rm, stat } from "node:fs/promises";
import path from "node:path";
import { runTurn } from "../src/agent.ts";
import type { ChatMessage, ApprovalRequest } from "../src/types.ts";
import { makeConfig, startMockModel, tempDir, type MockModel } from "./helpers.ts";

const dirs: string[] = [];
const servers: MockModel[] = [];

async function workspace(): Promise<string> {
  const dir = await tempDir();
  dirs.push(dir);
  return dir;
}

afterEach(async () => {
  while (servers.length > 0) servers.pop()!.stop();
  while (dirs.length > 0) await rm(dirs.pop()!, { recursive: true, force: true });
});

describe("agent loop", () => {
  test("executes a tool call and feeds the result back to the model", async () => {
    const cwd = await workspace();
    await Bun.write(path.join(cwd, "note.txt"), "hello agent\n");

    const mock = startMockModel([
      { toolCalls: [{ name: "read_file", arguments: { path: "note.txt" } }] },
      { content: "The note says hello agent." },
    ]);
    servers.push(mock);

    const messages: ChatMessage[] = [];
    const events: string[] = [];
    const deltas: string[] = [];

    await runTurn({
      config: makeConfig(mock.url),
      cwd,
      messages,
      userText: "read note.txt",
      approve: async () => true,
      hooks: {
        onTextDelta: (text) => deltas.push(text),
        onToolStart: (info) => events.push(`start:${info.call.function.name}`),
        onToolEnd: (info) => events.push(`end:${info.result?.isError ?? false}`),
      },
    });

    expect(events).toEqual(["start:read_file", "end:false"]);
    expect(deltas.join("")).toBe("The note says hello agent.");
    expect(messages.map((message) => message.role)).toEqual([
      "system",
      "user",
      "assistant",
      "tool",
      "assistant",
    ]);
    expect(messages[0]!.content).toContain(cwd);
    expect(messages[1]).toEqual({ role: "user", content: "read note.txt" });
    expect(messages[2]!.tool_calls?.[0]!.function.name).toBe("read_file");
    expect(messages[3]!.tool_call_id).toBe("call_0");
    expect(messages[3]!.content).toContain("hello agent");
    expect(messages[4]!.content).toBe("The note says hello agent.");

    expect(mock.requests).toHaveLength(2);
    const secondRequest = mock.requests[1] as {
      messages: Array<{ role: string; content: string | null }>;
    };
    expect(secondRequest.messages.at(-1)!.role).toBe("tool");
    expect(secondRequest.messages.at(-1)!.content).toContain("hello agent");
  });

  test("does not run a tool when approval is denied", async () => {
    const cwd = await workspace();
    const mock = startMockModel([
      { toolCalls: [{ name: "write_file", arguments: { path: "created.txt", content: "nope" } }] },
      { content: "Understood, I will not write the file." },
    ]);
    servers.push(mock);

    const messages: ChatMessage[] = [];
    const approvals: ApprovalRequest[] = [];

    await runTurn({
      config: makeConfig(mock.url),
      cwd,
      messages,
      userText: "create a file",
      approve: async (request) => {
        approvals.push(request);
        return false;
      },
    });

    expect(approvals).toHaveLength(1);
    expect(approvals[0]!.tool).toBe("write_file");
    expect(approvals[0]!.summary).toContain("created.txt");

    const toolMessage = messages.find((message) => message.role === "tool");
    expect(toolMessage?.content).toContain("Permission denied");
    await expect(stat(path.join(cwd, "created.txt"))).rejects.toThrow();
    expect(messages.at(-1)!.content).toBe("Understood, I will not write the file.");
  });

  test("asks for approval and executes when allowed", async () => {
    const cwd = await workspace();
    const mock = startMockModel([
      { toolCalls: [{ name: "write_file", arguments: { path: "ok.txt", content: "written\n" } }] },
      { content: "Done." },
    ]);
    servers.push(mock);

    const messages: ChatMessage[] = [];
    await runTurn({
      config: makeConfig(mock.url),
      cwd,
      messages,
      userText: "write ok.txt",
      approve: async () => true,
    });

    expect(await readFile(path.join(cwd, "ok.txt"), "utf8")).toBe("written\n");
    expect(messages.at(-1)!.content).toBe("Done.");
  });

  test("reports unknown tools and invalid JSON back to the model", async () => {
    const cwd = await workspace();
    const mock = startMockModel([
      {
        toolCalls: [
          { name: "not_a_tool", arguments: {}, id: "call_a" },
          { name: "read_file", arguments: "{not json", id: "call_b" },
        ],
      },
      { content: "Recovered." },
    ]);
    servers.push(mock);

    const messages: ChatMessage[] = [];
    const notices: string[] = [];

    await runTurn({
      config: makeConfig(mock.url),
      cwd,
      messages,
      userText: "do something",
      approve: async () => true,
      hooks: {
        onNotice: (level, message) => notices.push(`${level}:${message}`),
      },
    });

    const toolMessages = messages.filter((message) => message.role === "tool");
    expect(toolMessages).toHaveLength(2);
    expect(toolMessages[0]!.content).toContain("Unknown tool");
    expect(toolMessages[1]!.content).toContain("Invalid JSON");
    expect(messages.at(-1)!.content).toBe("Recovered.");
    expect(notices).toHaveLength(0);
  });

  test("stops at maxSteps and warns", async () => {
    const cwd = await workspace();
    await Bun.write(path.join(cwd, "loop.txt"), "data");

    const mock = startMockModel([
      { toolCalls: [{ name: "read_file", arguments: { path: "loop.txt" } }] },
    ]);
    servers.push(mock);

    const messages: ChatMessage[] = [];
    const notices: string[] = [];
    await runTurn({
      config: makeConfig(mock.url, { maxSteps: 2 }),
      cwd,
      messages,
      userText: "keep reading",
      approve: async () => true,
      hooks: { onNotice: (level, message) => notices.push(`${level}:${message}`) },
    });

    expect(notices.some((notice) => notice.includes("Stopped after 2 agent steps"))).toBe(true);
    expect(mock.requests).toHaveLength(2);
  });
});
