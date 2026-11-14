import type { ApprovalRequest, ToolDefinition, ToolResult } from "../types.ts";

export interface ToolContext {
  cwd: string;
  signal?: AbortSignal;
  approve: (request: ApprovalRequest) => Promise<boolean>;
}

export interface Tool {
  definition: ToolDefinition;
  /** Whether running this tool requires explicit user approval. */
  needsApproval?: boolean;
  /**
   * Build a rich approval request (e.g. with a diff) before running.
   * Only called when `needsApproval` is true.
   */
  preview?(input: Record<string, unknown>, ctx: ToolContext): Promise<ApprovalRequest>;
  run(input: Record<string, unknown>, ctx: ToolContext): Promise<ToolResult>;
}

export class ToolInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ToolInputError";
  }
}

export function getString(
  input: Record<string, unknown>,
  key: string,
  options?: { optional?: false; allowEmpty?: boolean },
): string;
export function getString(
  input: Record<string, unknown>,
  key: string,
  options: { optional: true; allowEmpty?: boolean },
): string | undefined;
export function getString(
  input: Record<string, unknown>,
  key: string,
  options: { optional?: boolean; allowEmpty?: boolean } = {},
): string | undefined {
  const value = input[key];
  if (value === undefined || value === null) {
    if (options.optional) return undefined;
    throw new ToolInputError(`Missing required argument "${key}".`);
  }
  if (typeof value !== "string") {
    throw new ToolInputError(`Argument "${key}" must be a string.`);
  }
  if (!options.allowEmpty && value.trim() === "") {
    throw new ToolInputError(`Argument "${key}" must not be empty.`);
  }
  return value;
}

export function getInteger(
  input: Record<string, unknown>,
  key: string,
  options: { optional?: boolean; min?: number; max?: number } = {},
): number | undefined {
  const value = input[key];
  if (value === undefined || value === null) {
    if (options.optional) return undefined;
    throw new ToolInputError(`Missing required argument "${key}".`);
  }
  if (typeof value !== "number" || !Number.isInteger(value)) {
    throw new ToolInputError(`Argument "${key}" must be an integer.`);
  }
  if (options.min !== undefined && value < options.min) {
    throw new ToolInputError(`Argument "${key}" must be at least ${options.min}.`);
  }
  if (options.max !== undefined && value > options.max) {
    throw new ToolInputError(`Argument "${key}" must be at most ${options.max}.`);
  }
  return value;
}

export function getBoolean(
  input: Record<string, unknown>,
  key: string,
  options: { optional?: boolean } = {},
): boolean | undefined {
  const value = input[key];
  if (value === undefined || value === null) {
    if (options.optional) return undefined;
    throw new ToolInputError(`Missing required argument "${key}".`);
  }
  if (typeof value !== "boolean") {
    throw new ToolInputError(`Argument "${key}" must be a boolean.`);
  }
  return value;
}
