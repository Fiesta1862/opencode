import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { ConfigError, loadConfig, requireApiKey } from "../src/config.ts";
import { tempDir } from "./helpers.ts";

const ENV_KEYS = [
  "MINICODE_CONFIG_DIR",
  "MINICODE_DATA_DIR",
  "MINICODE_API_KEY",
  "OPENAI_API_KEY",
  "MINICODE_MODEL",
  "MINICODE_BASE_URL",
  "OPENAI_BASE_URL",
  "MINICODE_MAX_STEPS",
  "MINICODE_AUTO_APPROVE",
];

let savedEnv: Record<string, string | undefined> = {};
const dirs: string[] = [];

beforeEach(() => {
  savedEnv = {};
  for (const key of ENV_KEYS) {
    savedEnv[key] = process.env[key];
    delete process.env[key];
  }
});

afterEach(async () => {
  for (const key of ENV_KEYS) {
    if (savedEnv[key] === undefined) delete process.env[key];
    else process.env[key] = savedEnv[key];
  }
  while (dirs.length > 0) await rm(dirs.pop()!, { recursive: true, force: true });
});

async function setup(): Promise<{ cwd: string; configDir: string }> {
  const cwd = await tempDir();
  const configDir = await tempDir();
  dirs.push(cwd, configDir);
  process.env.MINICODE_CONFIG_DIR = configDir;
  return { cwd, configDir };
}

describe("config", () => {
  test("uses defaults", async () => {
    const { cwd } = await setup();

    const config = await loadConfig(cwd, { apiKey: "k" });
    expect(config.model).toBe("gpt-4o-mini");
    expect(config.baseUrl).toBe("https://api.openai.com/v1");
    expect(config.maxSteps).toBe(25);
    expect(config.autoApprove).toBe(false);
    expect(config.configFiles).toEqual([]);
  });

  test("project file overrides defaults, env overrides file, flags override env", async () => {
    const { cwd } = await setup();
    await writeFile(
      path.join(cwd, "minicode.json"),
      JSON.stringify({
        model: "file-model",
        base_url: "http://file.local/v1",
        max_steps: 7,
        auto_approve: true,
        temperature: 0.5,
      }),
    );

    const fromFile = await loadConfig(cwd, { apiKey: "k" });
    expect(fromFile.model).toBe("file-model");
    expect(fromFile.baseUrl).toBe("http://file.local/v1");
    expect(fromFile.maxSteps).toBe(7);
    expect(fromFile.autoApprove).toBe(true);
    expect(fromFile.temperature).toBe(0.5);

    process.env.MINICODE_MODEL = "env-model";
    process.env.MINICODE_BASE_URL = "http://env.local/v1/";
    process.env.MINICODE_MAX_STEPS = "9";
    process.env.MINICODE_AUTO_APPROVE = "0";

    const fromEnv = await loadConfig(cwd, { apiKey: "k" });
    expect(fromEnv.model).toBe("env-model");
    expect(fromEnv.baseUrl).toBe("http://env.local/v1");
    expect(fromEnv.maxSteps).toBe(9);
    expect(fromEnv.autoApprove).toBe(false);

    const fromFlags = await loadConfig(cwd, {
      apiKey: "k",
      model: "flag-model",
      baseUrl: "http://flag.local/v1/",
      maxSteps: 3,
      autoApprove: true,
    });
    expect(fromFlags.model).toBe("flag-model");
    expect(fromFlags.baseUrl).toBe("http://flag.local/v1");
    expect(fromFlags.maxSteps).toBe(3);
    expect(fromFlags.autoApprove).toBe(true);
  });

  test("merges global config with project config", async () => {
    const { cwd, configDir } = await setup();
    await mkdir(configDir, { recursive: true });
    await writeFile(
      path.join(configDir, "config.json"),
      JSON.stringify({ model: "global-model", temperature: 0.2 }),
    );
    await writeFile(path.join(cwd, "minicode.json"), JSON.stringify({ model: "project-model" }));

    const config = await loadConfig(cwd, { apiKey: "k" });
    expect(config.model).toBe("project-model");
    expect(config.temperature).toBe(0.2);
    expect(config.configFiles).toHaveLength(2);
  });

  test("API key precedence and redaction input", async () => {
    const { cwd } = await setup();
    await writeFile(path.join(cwd, "minicode.json"), JSON.stringify({ api_key: "file-key" }));

    expect((await loadConfig(cwd)).apiKey).toBe("file-key");

    process.env.OPENAI_API_KEY = "openai-key";
    expect((await loadConfig(cwd)).apiKey).toBe("openai-key");

    process.env.MINICODE_API_KEY = "minicode-key";
    expect((await loadConfig(cwd)).apiKey).toBe("minicode-key");

    expect((await loadConfig(cwd, { apiKey: "flag-key" })).apiKey).toBe("flag-key");
  });

  test("rejects invalid JSON and invalid URLs", async () => {
    const { cwd } = await setup();
    await writeFile(path.join(cwd, "minicode.json"), "{ nope");
    await expect(loadConfig(cwd, { apiKey: "k" })).rejects.toThrow(ConfigError);

    await writeFile(path.join(cwd, "minicode.json"), "{}");
    await expect(
      loadConfig(cwd, { apiKey: "k", baseUrl: "ftp://example.com" }),
    ).rejects.toThrow("http or https");

    await expect(loadConfig(cwd, { apiKey: "k", maxSteps: 0 })).rejects.toThrow("max_steps");
  });

  test("requireApiKey explains how to configure a key", async () => {
    const { cwd } = await setup();
    const config = await loadConfig(cwd);
    expect(() => requireApiKey(config)).toThrow("No API key found");

    const withKey = await loadConfig(cwd, { apiKey: "secret-key" });
    expect(requireApiKey(withKey)).toBe("secret-key");
  });
});
