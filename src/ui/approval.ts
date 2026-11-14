import { createInterface } from "node:readline/promises";
import type { Approve, ApprovalRequest } from "../types.ts";
import type { Style } from "./ansi.ts";

export interface ApproverOptions {
  autoApprove: boolean;
  style: Style;
  input?: NodeJS.ReadStream;
  output?: NodeJS.WriteStream;
  /** Print the details of a request (diff, command, ...) before asking. */
  printDetails: (request: ApprovalRequest) => void;
}

export interface Approver {
  approve: Approve;
  alwaysAllow: Set<string>;
}

export function createApprover(options: ApproverOptions): Approver {
  const input = options.input ?? process.stdin;
  const output = options.output ?? process.stdout;
  const style = options.style;
  const alwaysAllow = new Set<string>();

  const approve: Approve = async (request) => {
    if (options.autoApprove) return true;
    if (alwaysAllow.has(request.tool)) return true;

    options.printDetails(request);

    if (!input.isTTY) {
      output.write(
        style.dim("    auto-denied because stdin is not a terminal (pass --yes to allow)\n"),
      );
      return false;
    }

    const rl = createInterface({ input, output, terminal: true });
    try {
      const answer = (
        await rl.question(
          `    ${style.bold("Allow?")} ${style.dim("y = once · a = always · n = no")} ${style.cyan("›")} `,
        )
      )
        .trim()
        .toLowerCase();

      if (answer === "a" || answer === "always") {
        alwaysAllow.add(request.tool);
        return true;
      }
      return answer === "y" || answer === "yes";
    } catch {
      return false;
    } finally {
      rl.close();
    }
  };

  return { approve, alwaysAllow };
}
