import { lstat, readlink, realpath } from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";

export class PathError extends Error {}

function isInside(parent: string, child: string): boolean {
  const p = process.platform === "win32" ? parent.toLowerCase() : parent;
  const c = process.platform === "win32" ? child.toLowerCase() : child;
  return c === p || c.startsWith(p.endsWith(path.sep) ? p : p + path.sep);
}

/**
 * Follow a symlink chain manually so dangling links are resolved too.
 * Returns the final lexical destination, or undefined for a non-link.
 */
async function linkDestination(link: string): Promise<string | undefined> {
  let current = link;
  let last: string | undefined;

  for (let depth = 0; depth < 40; depth++) {
    const target = await readlink(current).catch(() => undefined);
    if (target === undefined) return last;
    last = path.resolve(path.dirname(current), target);

    const info = await lstat(last).catch(() => undefined);
    if (!info) return last; // dangling link
    if (!info.isSymbolicLink()) return last;
    current = last;
  }

  throw new PathError("too many levels of symbolic links");
}

/**
 * Resolve a user/model supplied path and make sure it stays inside the
 * workspace. Every existing component is checked for symlinks, including
 * dangling ones, so writes cannot be redirected outside the workspace.
 */
export async function resolveWorkspacePath(cwd: string, input: string): Promise<string> {
  if (typeof input !== "string" || input.trim() === "") {
    throw new PathError("path must be a non-empty string");
  }
  if (input.includes("\0")) {
    throw new PathError("path contains a null byte");
  }

  const workspace = path.resolve(cwd);
  const target = path.resolve(workspace, input);

  if (!isInside(workspace, target)) {
    throw new PathError(`path escapes the workspace: ${input}`);
  }

  const realWorkspace = (await realpath(workspace).catch(() => workspace)) || workspace;
  const parts = path.relative(workspace, target).split(path.sep).filter(Boolean);
  let current = workspace;

  for (const part of parts) {
    current = path.join(current, part);
    const info = await lstat(current).catch(() => undefined);
    if (!info) break; // the rest of the path does not exist yet

    if (!info.isSymbolicLink()) continue;

    const destination = await linkDestination(current);
    if (!destination) break;

    const canonical = await realpath(destination).catch(() => destination);
    const contained =
      isInside(workspace, destination) ||
      isInside(realWorkspace, canonical) ||
      isInside(workspace, canonical);
    if (!contained) {
      throw new PathError(`path escapes the workspace via a symlink: ${input}`);
    }

    current = destination;
  }

  return target;
}

export function displayPath(cwd: string, target: string): string {
  const rel = path.relative(cwd, target);
  if (!rel || rel.startsWith("..")) return target;
  return rel;
}

export function dataDir(): string {
  if (process.env.MINICODE_DATA_DIR) return path.resolve(process.env.MINICODE_DATA_DIR);
  const xdg = process.env.XDG_DATA_HOME;
  if (xdg) return path.join(xdg, "minicode");
  return path.join(homedir(), ".local", "share", "minicode");
}

export function configDir(): string {
  if (process.env.MINICODE_CONFIG_DIR) return path.resolve(process.env.MINICODE_CONFIG_DIR);
  const xdg = process.env.XDG_CONFIG_HOME;
  if (xdg) return path.join(xdg, "minicode");
  return path.join(homedir(), ".config", "minicode");
}

export function sessionsDir(): string {
  return path.join(dataDir(), "sessions");
}
