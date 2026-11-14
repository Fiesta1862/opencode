export type Role = "system" | "user" | "assistant" | "tool";

export interface ToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string;
  };
}

export interface ChatMessage {
  role: Role;
  content: string | null;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
}

export interface Usage {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
}

export interface ChatResult {
  assistant: ChatMessage;
  finishReason: string | null;
  usage?: Usage;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface DiffLine {
  type: "add" | "del" | "ctx";
  text: string;
}

export interface ToolResult {
  /** Text returned to the model. */
  output: string;
  /** Short one-line summary for the human. */
  summary?: string;
  /** Optional structured diff for the human. */
  diff?: DiffLine[];
  /** Whether the tool failed (for display purposes). */
  isError?: boolean;
}

export interface ApprovalRequest {
  tool: string;
  /** One-line description shown to the user. */
  summary: string;
  /** Multi-line details: command, diff, file contents, etc. */
  details?: string;
  diff?: DiffLine[];
}

export type Approve = (request: ApprovalRequest) => Promise<boolean>;
