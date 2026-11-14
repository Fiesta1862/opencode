import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { rm } from "node:fs/promises";
import {
  createSession,
  findSession,
  formatRelativeTime,
  listSessions,
  saveSession,
  SessionError,
} from "../src/session.ts";
import { tempDir } from "./helpers.ts";

let dataDir: string;

beforeEach(async () => {
  dataDir = await tempDir();
  process.env.MINICODE_DATA_DIR = dataDir;
});

afterEach(async () => {
  delete process.env.MINICODE_DATA_DIR;
  await rm(dataDir, { recursive: true, force: true });
});

describe("sessions", () => {
  test("saves messages without the system prompt and loads them back", async () => {
    const cwd = "/tmp/project-a";
    const session = createSession(cwd, "test-model");
    session.messages.push(
      { role: "system", content: "system prompt" },
      { role: "user", content: "hello there, please fix the tests" },
      { role: "assistant", content: "working on it" },
    );

    await saveSession(session);

    const loaded = await findSession(cwd, "latest");
    expect(loaded.id).toBe(session.id);
    expect(loaded.messages).toHaveLength(2);
    expect(loaded.messages[0]).toEqual({ role: "user", content: "hello there, please fix the tests" });

    const prefix = session.id.slice(0, 6);
    expect((await findSession(cwd, prefix)).id).toBe(session.id);
  });

  test("lists only sessions for the requested directory, newest first", async () => {
    const cwd = "/tmp/project-b";
    const first = createSession(cwd, "m");
    first.messages.push({ role: "user", content: "first prompt" });
    await saveSession(first);

    await Bun.sleep(5);

    const second = createSession(cwd, "m");
    second.messages.push({ role: "user", content: "second prompt" });
    await saveSession(second);

    const other = createSession("/tmp/other-project", "m");
    other.messages.push({ role: "user", content: "other" });
    await saveSession(other);

    const sessions = await listSessions(cwd);
    expect(sessions).toHaveLength(2);
    expect(sessions[0]!.id).toBe(second.id);
    expect(sessions[1]!.id).toBe(first.id);
    expect(sessions[0]!.preview).toBe("second prompt");
  });

  test("reports missing and unknown sessions", async () => {
    const cwd = "/tmp/project-c";
    await expect(findSession(cwd, "latest")).rejects.toThrow(SessionError);

    const session = createSession(cwd, "m");
    await saveSession(session);

    await expect(findSession(cwd, "zzzzzz")).rejects.toThrow('No session matches "zzzzzz"');
    await expect(findSession("/tmp/elsewhere", "latest")).rejects.toThrow("No saved sessions");
  });

  test("formats relative times", () => {
    expect(formatRelativeTime(new Date().toISOString())).toBe("just now");
    expect(formatRelativeTime(new Date(Date.now() - 5 * 60_000).toISOString())).toBe("5m ago");
    expect(formatRelativeTime(new Date(Date.now() - 3 * 3_600_000).toISOString())).toBe("3h ago");
  });
});
