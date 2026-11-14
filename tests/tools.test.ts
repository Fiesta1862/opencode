import { afterEach, describe, expect, test } from "bun:test";
import { mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { getTool, type ToolContext } from "../src/tools/index.ts";
import { tempDir } from "./helpers.ts";

const dirs: string[] = [];

async function workspace(): Promise<string> {
  const dir = await tempDir();
  dirs.push(dir);
  return dir;
}

function context(cwd: string): ToolContext {
  return { cwd, approve: async () => true, signal: new AbortController().signal };
}

async function runTool(name: string, input: Record<string, unknown>, cwd: string) {
  const tool = getTool(name);
  if (!tool) throw new Error(`no such tool: ${name}`);
  return tool.run(input, context(cwd));
}

afterEach(async () => {
  while (dirs.length > 0) {
    await rm(dirs.pop()!, { recursive: true, force: true });
  }
});

describe("file tools", () => {
  test("write_file creates nested files and read_file returns contents", async () => {
    const cwd = await workspace();
    const result = await runTool("write_file", { path: "src/deep/a.txt", content: "one\ntwo\n" }, cwd);

    expect(result.summary).toContain("created src/deep/a.txt");
    expect(await readFile(path.join(cwd, "src/deep/a.txt"), "utf8")).toBe("one\ntwo\n");

    const read = await runTool("read_file", { path: "src/deep/a.txt" }, cwd);
    expect(read.output).toBe("one\ntwo\n");
  });

  test("read_file supports offsets and limits", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "lines.txt"), "a\nb\nc\nd\ne\n");

    const read = await runTool("read_file", { path: "lines.txt", offset: 2, limit: 2 }, cwd);
    expect(read.output).toBe("b\nc\n\n[Showing lines 2-3 of 5]");
  });

  test("edit_file replaces one occurrence and produces a diff", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "file.txt"), "hello world\nsecond line\n");

    const result = await runTool(
      "edit_file",
      { path: "file.txt", old_string: "world", new_string: "there" },
      cwd,
    );

    expect(await readFile(path.join(cwd, "file.txt"), "utf8")).toBe("hello there\nsecond line\n");
    expect(result.diff).toContainEqual({ type: "del", text: "hello world" });
    expect(result.diff).toContainEqual({ type: "add", text: "hello there" });
  });

  test("edit_file refuses ambiguous matches unless replace_all is set", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "dup.txt"), "foo\nfoo\n");

    await expect(
      runTool("edit_file", { path: "dup.txt", old_string: "foo", new_string: "bar" }, cwd),
    ).rejects.toThrow("occurs 2 times");

    await runTool(
      "edit_file",
      { path: "dup.txt", old_string: "foo", new_string: "bar", replace_all: true },
      cwd,
    );
    expect(await readFile(path.join(cwd, "dup.txt"), "utf8")).toBe("bar\nbar\n");
  });

  test("edit_file reports missing text", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "file.txt"), "abc\n");

    await expect(
      runTool("edit_file", { path: "file.txt", old_string: "zzz", new_string: "x" }, cwd),
    ).rejects.toThrow("was not found");
  });

  test("paths cannot escape the workspace", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "inside.txt"), "safe");

    await expect(runTool("read_file", { path: "../outside.txt" }, cwd)).rejects.toThrow(
      "escapes the workspace",
    );
    await expect(runTool("read_file", { path: "/etc/hosts" }, cwd)).rejects.toThrow(
      "escapes the workspace",
    );

    await expect(
      runTool("write_file", { path: "../escape.txt", content: "nope" }, cwd),
    ).rejects.toThrow("escapes the workspace");
  });

  test.skipIf(process.platform === "win32")("paths cannot escape via symlinks", async () => {
    const cwd = await workspace();
    const outside = await workspace();
    const secret = path.join(outside, "secret.txt");
    await writeFile(secret, "secret");
    await symlink(secret, path.join(cwd, "link.txt"));

    await expect(runTool("read_file", { path: "link.txt" }, cwd)).rejects.toThrow(
      "escapes the workspace",
    );
  });

  test.skipIf(process.platform === "win32")("paths cannot escape via dangling symlinks", async () => {
    const cwd = await workspace();
    const outside = await workspace();
    await symlink(path.join(outside, "missing", "escaped.txt"), path.join(cwd, "dangling.txt"));

    await expect(
      runTool("write_file", { path: "dangling.txt", content: "nope" }, cwd),
    ).rejects.toThrow("escapes the workspace");
  });

  test("read_file rejects binary files", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "bin"), Buffer.from([1, 0, 2, 3]));

    await expect(runTool("read_file", { path: "bin" }, cwd)).rejects.toThrow("binary");
  });
});

describe("search tools", () => {
  test("glob finds files and skips node_modules", async () => {
    const cwd = await workspace();
    await mkdir(path.join(cwd, "nested"), { recursive: true });
    await mkdir(path.join(cwd, "node_modules/pkg"), { recursive: true });
    await writeFile(path.join(cwd, "a.ts"), "");
    await writeFile(path.join(cwd, "b.js"), "");
    await writeFile(path.join(cwd, "nested/c.ts"), "");
    await writeFile(path.join(cwd, "node_modules/pkg/x.ts"), "");

    const result = await runTool("glob", { pattern: "**/*.ts" }, cwd);
    const lines = result.output.split("\n").sort();
    expect(lines).toEqual(["a.ts", "nested/c.ts"]);
  });

  test("grep finds matching lines and respects include", async () => {
    const cwd = await workspace();
    await writeFile(path.join(cwd, "a.ts"), "const a = 1;\n// TODO fix\n");
    await writeFile(path.join(cwd, "b.md"), "TODO in docs\n");

    const result = await runTool("grep", { pattern: "TODO", include: "*.ts" }, cwd);
    expect(result.output).toBe("a.ts:2: // TODO fix");

    await expect(runTool("grep", { pattern: "(", include: "*.ts" }, cwd)).rejects.toThrow(
      "Invalid regular expression",
    );
  });

  test("list_files renders a tree", async () => {
    const cwd = await workspace();
    await mkdir(path.join(cwd, "src"), { recursive: true });
    await writeFile(path.join(cwd, "README.md"), "");
    await writeFile(path.join(cwd, "src/index.ts"), "");

    const result = await runTool("list_files", { depth: 2 }, cwd);
    expect(result.output).toContain("src/");
    expect(result.output).toContain("index.ts");
    expect(result.output).toContain("README.md");
  });
});

describe("run_command", () => {
  test.skipIf(process.platform === "win32")("captures stdout and exit status", async () => {
    const cwd = await workspace();
    const result = await runTool("run_command", { command: "printf hello" }, cwd);

    expect(result.output).toContain("exit code: 0");
    expect(result.output).toContain("hello");
    expect(result.isError).toBe(false);
  });

  test.skipIf(process.platform === "win32")("reports non-zero exits", async () => {
    const cwd = await workspace();
    const result = await runTool("run_command", { command: "echo oops >&2; exit 3" }, cwd);

    expect(result.output).toContain("exit code: 3");
    expect(result.output).toContain("oops");
    expect(result.isError).toBe(true);
  });

  test.skipIf(process.platform === "win32")("honors the timeout", async () => {
    const cwd = await workspace();
    const result = await runTool("run_command", { command: "sleep 5", timeout_ms: 1000 }, cwd);

    expect(result.output).toContain("timed out");
    expect(result.isError).toBe(true);
  }, 10_000);
});
