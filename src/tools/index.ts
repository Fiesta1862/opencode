import type { ToolDefinition } from "../types.ts";
import { runCommandTool } from "./command.ts";
import { editFileTool, readFileTool, writeFileTool } from "./fs.ts";
import { globTool } from "./glob.ts";
import { grepTool, listFilesTool } from "./search.ts";
import type { Tool } from "./types.ts";

export const allTools: Tool[] = [
  readFileTool,
  writeFileTool,
  editFileTool,
  listFilesTool,
  globTool,
  grepTool,
  runCommandTool,
];

export const toolDefinitions: ToolDefinition[] = allTools.map((tool) => tool.definition);

export function getTool(name: string): Tool | undefined {
  return allTools.find((tool) => tool.definition.name === name);
}

export { ToolInputError } from "./types.ts";
export type { Tool, ToolContext } from "./types.ts";
