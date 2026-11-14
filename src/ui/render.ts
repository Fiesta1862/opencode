import type { ToolEndInfo } from "../agent.ts";
import type { ApprovalRequest } from "../types.ts";
import { oneLine } from "../util/text.ts";
import type { Style } from "./ansi.ts";

const FRAMES = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

export class Spinner {
  private timer: ReturnType<typeof setInterval> | undefined;
  private frame = 0;
  private startedAt = 0;
  private active = false;

  constructor(
    private label: string,
    private readonly out: NodeJS.WriteStream = process.stdout,
    private readonly style: Style,
    private readonly enabled = out.isTTY === true,
  ) {}

  start(label = this.label): void {
    this.label = label;
    if (!this.enabled || this.active) return;
    this.active = true;
    this.startedAt = Date.now();
    this.render();
    this.timer = setInterval(() => this.render(), 90);
  }

  stop(): void {
    if (!this.enabled || !this.active) return;
    this.active = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.out.write("\r\x1b[2K");
  }

  private render(): void {
    const frame = FRAMES[this.frame++ % FRAMES.length]!;
    const seconds = ((Date.now() - this.startedAt) / 1000).toFixed(1);
    this.out.write(`\r\x1b[2K  ${this.style.dim(`${frame} ${this.label}… ${seconds}s`)}`);
  }
}

function formatInline(text: string, style: Style): string {
  return text
    .split(/(`[^`]*`)/g)
    .map((part) => {
      if (part.length > 1 && part.startsWith("`") && part.endsWith("`")) {
        return style.yellow(part);
      }
      return part
        .replace(/\*\*([^*]+)\*\*/g, (_match, inner: string) => style.bold(inner))
        .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, (_match, prefix: string, inner: string) => {
          return prefix + style.italic(inner);
        });
    })
    .join("");
}

export interface AssistantRendererOptions {
  style: Style;
  out?: NodeJS.WriteStream;
  label?: string;
}

/**
 * Line-buffered renderer for streamed assistant text with light markdown
 * formatting. Writes a bullet before the first line of each message.
 */
export class AssistantRenderer {
  private readonly style: Style;
  private readonly out: NodeJS.WriteStream;
  private readonly spinner: Spinner;
  private buffer = "";
  private inCode = false;
  private firstLine = true;
  private wroteAny = false;

  constructor(options: AssistantRendererOptions) {
    this.style = options.style;
    this.out = options.out ?? process.stdout;
    this.spinner = new Spinner(options.label ?? "thinking", this.out, this.style);
  }

  begin(): void {
    this.buffer = "";
    this.inCode = false;
    this.firstLine = true;
    this.wroteAny = false;
    this.spinner.start();
  }

  delta(text: string): void {
    this.spinner.stop();
    this.buffer += text;
    let newline: number;
    while ((newline = this.buffer.indexOf("\n")) !== -1) {
      const line = this.buffer.slice(0, newline).replace(/\r$/, "");
      this.buffer = this.buffer.slice(newline + 1);
      this.writeLine(line);
    }
  }

  /** Flush pending text and stop the spinner. Returns true if text was written. */
  end(): boolean {
    this.spinner.stop();
    if (this.buffer.length > 0) {
      this.writeLine(this.buffer.replace(/\r$/, ""));
      this.buffer = "";
    }
    return this.wroteAny;
  }

  private writeLine(line: string): void {
    const prefix = this.firstLine ? this.style.magenta("⏺ ") : "  ";
    this.firstLine = false;
    this.wroteAny = true;
    this.out.write(prefix + this.renderLine(line) + "\n");
  }

  private renderLine(line: string): string {
    if (line.trimStart().startsWith("```")) {
      this.inCode = !this.inCode;
      return this.style.gray(line);
    }
    if (this.inCode) return this.style.cyan(line);

    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) return this.style.bold(heading[2]!);

    const bullet = /^(\s*)[-*]\s+(.*)$/.exec(line);
    if (bullet) return `${bullet[1]}• ${formatInline(bullet[2]!, this.style)}`;

    return formatInline(line, this.style);
  }
}

export function formatToolStart(name: string, input: Record<string, unknown>): string {
  const pathValue = typeof input.path === "string" ? input.path : undefined;
  switch (name) {
    case "run_command":
      return `$ ${oneLine(String(input.command ?? ""), 100)}`;
    case "read_file":
      return `read ${pathValue ?? "file"}`;
    case "write_file":
      return `write ${pathValue ?? "file"}`;
    case "edit_file":
      return `edit ${pathValue ?? "file"}`;
    case "list_files":
      return `list ${pathValue ?? "."}`;
    case "glob":
      return `glob ${oneLine(String(input.pattern ?? ""), 80)}`;
    case "grep":
      return `grep /${oneLine(String(input.pattern ?? ""), 60)}/${input.include ? ` in ${input.include}` : ""}`;
    default:
      return name;
  }
}

export function formatToolEnd(info: ToolEndInfo, style: Style): string | undefined {
  if (info.denied) return `  ${style.yellow("⊘ denied")}`;
  if (!info.result) return undefined;
  const icon = info.result.isError ? style.red("✗") : style.green("✓");
  const summary = info.result.summary ? ` ${style.dim(info.result.summary)}` : "";
  return `  ${icon}${summary}`;
}

export function formatApproval(request: ApprovalRequest, style: Style, maxLines = 30): string {
  const lines: string[] = [];

  if (request.diff && request.diff.length > 0) {
    for (const line of request.diff.slice(0, maxLines)) {
      const marker = line.type === "add" ? "+" : line.type === "del" ? "-" : " ";
      const text = `    ${marker} ${line.text}`;
      if (line.type === "add") lines.push(style.green(text));
      else if (line.type === "del") lines.push(style.red(text));
      else lines.push(style.dim(text));
    }
    if (request.diff.length > maxLines) {
      lines.push(style.dim(`    … ${request.diff.length - maxLines} more diff lines`));
    }
  } else if (request.details) {
    const detail = request.details.split("\n");
    for (const line of detail.slice(0, maxLines)) lines.push(`    ${style.dim(line)}`);
    if (detail.length > maxLines) {
      lines.push(style.dim(`    … ${detail.length - maxLines} more lines`));
    }
  }

  return lines.length > 0 ? lines.join("\n") + "\n" : "";
}
