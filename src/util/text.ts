import type { DiffLine } from "../types.ts";

/** Collapse whitespace into single spaces and cap the length. */
export function oneLine(text: string, max = 100): string {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > max ? clean.slice(0, max - 1) + "…" : clean;
}

/** Keep the start and end of long output; models rarely need the middle. */
export function truncateMiddle(text: string, max: number): string {
  if (text.length <= max) return text;
  const head = Math.floor(max * 0.6);
  const tail = Math.floor(max * 0.35);
  const omitted = text.length - head - tail;
  return (
    text.slice(0, head) +
    `\n… [${omitted.toLocaleString()} characters omitted] …\n` +
    text.slice(text.length - tail)
  );
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function splitLines(text: string): string[] {
  const normalized = text.replace(/\r\n/g, "\n");
  const lines = normalized.split("\n");
  if (lines.length > 1 && lines[lines.length - 1] === "") lines.pop();
  return lines;
}

/**
 * Build a compact, contiguous diff (context around the changed region).
 * This is intended for display of single edits, not a general purpose diff.
 */
export function makeDiff(before: string, after: string, context = 3): DiffLine[] {
  const a = splitLines(before);
  const b = splitLines(after);

  let prefix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;

  let suffix = 0;
  while (
    suffix < a.length - prefix &&
    suffix < b.length - prefix &&
    a[a.length - 1 - suffix] === b[b.length - 1 - suffix]
  ) {
    suffix++;
  }

  const lines: DiffLine[] = [];
  const ctxStart = Math.max(0, prefix - context);
  for (let i = ctxStart; i < prefix; i++) lines.push({ type: "ctx", text: a[i]! });

  for (let i = prefix; i < a.length - suffix; i++) lines.push({ type: "del", text: a[i]! });
  for (let i = prefix; i < b.length - suffix; i++) lines.push({ type: "add", text: b[i]! });

  const ctxEnd = Math.min(a.length, a.length - suffix + context);
  for (let i = a.length - suffix; i < ctxEnd; i++) lines.push({ type: "ctx", text: a[i]! });

  return lines;
}
