import { randomBytes } from "node:crypto";
import { mkdir, readdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import type { ChatMessage } from "./types.ts";
import { sessionsDir } from "./util/paths.ts";
import { oneLine } from "./util/text.ts";

export interface Session {
  version: 1;
  id: string;
  cwd: string;
  model: string;
  createdAt: string;
  updatedAt: string;
  messages: ChatMessage[];
}

export interface SessionSummary {
  id: string;
  cwd: string;
  model: string;
  updatedAt: string;
  messageCount: number;
  preview: string;
}

export class SessionError extends Error {}

export function createSession(cwd: string, model: string): Session {
  const now = new Date().toISOString();
  const id = `${Date.now().toString(36)}-${randomBytes(3).toString("hex")}`;
  return {
    version: 1,
    id,
    cwd,
    model,
    createdAt: now,
    updatedAt: now,
    messages: [],
  };
}

function sessionFile(id: string): string {
  return path.join(sessionsDir(), `${id}.json`);
}

function isValidSession(value: unknown): value is Session {
  if (!value || typeof value !== "object") return false;
  const session = value as Partial<Session>;
  return (
    typeof session.id === "string" &&
    typeof session.cwd === "string" &&
    Array.isArray(session.messages)
  );
}

export async function saveSession(session: Session): Promise<string> {
  session.updatedAt = new Date().toISOString();
  const persisted: Session = {
    ...session,
    messages: session.messages.filter((message) => message.role !== "system"),
  };

  const dir = sessionsDir();
  await mkdir(dir, { recursive: true });
  const file = sessionFile(session.id);
  const tmp = `${file}.${process.pid}.tmp`;
  await writeFile(tmp, JSON.stringify(persisted, null, 2), "utf8");
  await rename(tmp, file);
  return file;
}

async function readSessionFile(file: string): Promise<Session | undefined> {
  try {
    const parsed = JSON.parse(await readFile(file, "utf8"));
    return isValidSession(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

export async function listSessions(cwd?: string): Promise<SessionSummary[]> {
  let files: string[];
  try {
    files = (await readdir(sessionsDir())).filter((name) => name.endsWith(".json"));
  } catch {
    return [];
  }

  const sessions: SessionSummary[] = [];
  for (const name of files) {
    const session = await readSessionFile(path.join(sessionsDir(), name));
    if (!session) continue;
    if (cwd && session.cwd !== cwd) continue;
    const firstUser = session.messages.find((message) => message.role === "user");
    sessions.push({
      id: session.id,
      cwd: session.cwd,
      model: session.model,
      updatedAt: session.updatedAt,
      messageCount: session.messages.length,
      preview: firstUser?.content ? oneLine(firstUser.content, 72) : "(empty session)",
    });
  }

  sessions.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  return sessions;
}

export async function findSession(cwd: string, ref: string): Promise<Session> {
  const all = await listSessions(cwd);
  if (all.length === 0) {
    throw new SessionError(`No saved sessions for ${cwd}.`);
  }

  if (ref === "latest") {
    const session = await readSessionFile(sessionFile(all[0]!.id));
    if (!session) throw new SessionError("Could not read the latest session.");
    return session;
  }

  const matches = all.filter((session) => session.id === ref || session.id.startsWith(ref));
  if (matches.length === 0) {
    throw new SessionError(`No session matches "${ref}" in this directory.`);
  }
  if (matches.length > 1) {
    throw new SessionError(
      `Session id "${ref}" is ambiguous: ${matches.map((session) => session.id).join(", ")}`,
    );
  }
  const session = await readSessionFile(sessionFile(matches[0]!.id));
  if (!session) throw new SessionError(`Could not read session ${matches[0]!.id}.`);
  return session;
}

export function formatRelativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (!Number.isFinite(then)) return iso;
  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h ago`;
  const days = Math.floor(seconds / 86_400);
  return days === 1 ? "yesterday" : `${days}d ago`;
}
