import { readFile } from "node:fs/promises";
import path from "node:path";
import { configDir } from "./util/paths.ts";

export class ConfigError extends Error {}

export interface ConfigFlags {
  model?: string;
  baseUrl?: string;
  apiKey?: string;
  maxSteps?: number;
  autoApprove?: boolean;
  systemPromptExtra?: string;
  temperature?: number;
}

export interface FileConfig {
  model?: string;
  base_url?: string;
  api_key?: string;
  max_steps?: number;
  temperature?: number;
  auto_approve?: boolean;
  system_prompt_extra?: string;
}

export interface ResolvedConfig {
  model: string;
  baseUrl: string;
  apiKey?: string;
  maxSteps: number;
  temperature?: number;
  autoApprove: boolean;
  systemPromptExtra?: string;
  /** Config files that were found and read, in precedence order. */
  configFiles: string[];
}

const DEFAULTS = {
  model: "gpt-4o-mini",
  baseUrl: "https://api.openai.com/v1",
  maxSteps: 25,
};

function envBool(value: string | undefined): boolean | undefined {
  if (value === undefined || value === "") return undefined;
  return /^(1|true|yes|on)$/i.test(value);
}

function envInt(value: string | undefined): number | undefined {
  if (value === undefined || value === "") return undefined;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}

async function readConfigFile(file: string): Promise<FileConfig> {
  let raw: string;
  try {
    raw = await readFile(file, "utf8");
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === "ENOENT") return {};
    throw new ConfigError(`Could not read ${file}: ${(err as Error).message}`);
  }

  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("expected a JSON object");
    }
    return parsed as FileConfig;
  } catch (err) {
    throw new ConfigError(`Invalid JSON in ${file}: ${(err as Error).message}`);
  }
}

function normalizeBaseUrl(url: string): string {
  const trimmed = url.trim().replace(/\/+$/, "");
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new ConfigError(`Invalid base URL: ${url}`);
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new ConfigError(`Base URL must use http or https: ${url}`);
  }
  return trimmed;
}

export async function loadConfig(cwd: string, flags: ConfigFlags = {}): Promise<ResolvedConfig> {
  const globalFile = path.join(configDir(), "config.json");
  const projectFile = path.join(cwd, "minicode.json");

  const global = await readConfigFile(globalFile);
  const project = await readConfigFile(projectFile);
  const file: FileConfig = { ...global, ...project };

  const configFiles: string[] = [];
  if (Object.keys(global).length > 0) configFiles.push(globalFile);
  if (Object.keys(project).length > 0) configFiles.push(projectFile);

  let apiKey =
    flags.apiKey ??
    process.env.MINICODE_API_KEY ??
    process.env.OPENAI_API_KEY ??
    file.api_key ??
    undefined;

  // Allow "ollama" style local providers without a key.
  if (apiKey && apiKey.trim() === "") apiKey = undefined;

  const baseUrl = normalizeBaseUrl(
    flags.baseUrl ??
      process.env.MINICODE_BASE_URL ??
      process.env.OPENAI_BASE_URL ??
      file.base_url ??
      DEFAULTS.baseUrl,
  );

  const maxSteps =
    flags.maxSteps ??
    envInt(process.env.MINICODE_MAX_STEPS) ??
    file.max_steps ??
    DEFAULTS.maxSteps;

  if (!Number.isInteger(maxSteps) || maxSteps < 1 || maxSteps > 200) {
    throw new ConfigError(`max_steps must be an integer between 1 and 200 (got ${maxSteps})`);
  }

  const temperature = flags.temperature ?? file.temperature;
  if (temperature !== undefined && (typeof temperature !== "number" || temperature < 0 || temperature > 2)) {
    throw new ConfigError("temperature must be a number between 0 and 2");
  }

  const autoApprove =
    flags.autoApprove ?? envBool(process.env.MINICODE_AUTO_APPROVE) ?? file.auto_approve ?? false;

  return {
    model:
      flags.model ??
      process.env.MINICODE_MODEL ??
      file.model ??
      DEFAULTS.model,
    baseUrl,
    apiKey,
    maxSteps,
    temperature,
    autoApprove,
    systemPromptExtra: flags.systemPromptExtra ?? file.system_prompt_extra,
    configFiles,
  };
}

export function redactKey(key: string | undefined): string {
  if (!key) return "(not set)";
  if (key.length <= 8) return "*".repeat(key.length);
  return `${key.slice(0, 4)}…${key.slice(-4)}`;
}

export function requireApiKey(config: ResolvedConfig): string {
  if (!config.apiKey) {
    throw new ConfigError(
      [
        "No API key found.",
        "",
        "Set one of:",
        "  export OPENAI_API_KEY=sk-...        # or",
        "  export MINICODE_API_KEY=sk-...",
        "",
        'Or add "api_key" to minicode.json / ~/.config/minicode/config.json.',
        "Local OpenAI-compatible servers (e.g. Ollama) usually accept any key — use MINICODE_API_KEY=local.",
      ].join("\n"),
    );
  }
  return config.apiKey;
}
