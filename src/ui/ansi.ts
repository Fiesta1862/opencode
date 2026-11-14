const ANSI = {
  reset: "0",
  bold: "1",
  dim: "2",
  italic: "3",
  underline: "4",
  red: "31",
  green: "32",
  yellow: "33",
  blue: "34",
  magenta: "35",
  cyan: "36",
  gray: "90",
  brightRed: "91",
  brightGreen: "92",
};

function noop(text: string): string {
  return text;
}

export interface Style {
  bold: (text: string) => string;
  dim: (text: string) => string;
  italic: (text: string) => string;
  underline: (text: string) => string;
  red: (text: string) => string;
  green: (text: string) => string;
  yellow: (text: string) => string;
  blue: (text: string) => string;
  magenta: (text: string) => string;
  cyan: (text: string) => string;
  gray: (text: string) => string;
  brightRed: (text: string) => string;
  brightGreen: (text: string) => string;
}

export function createStyle(enabled: boolean): Style {
  const make = (code: string) => {
    if (!enabled) return noop;
    return (text: string) => `\x1b[${code}m${text}\x1b[${ANSI.reset}m`;
  };
  return {
    bold: make(ANSI.bold),
    dim: make(ANSI.dim),
    italic: make(ANSI.italic),
    underline: make(ANSI.underline),
    red: make(ANSI.red),
    green: make(ANSI.green),
    yellow: make(ANSI.yellow),
    blue: make(ANSI.blue),
    magenta: make(ANSI.magenta),
    cyan: make(ANSI.cyan),
    gray: make(ANSI.gray),
    brightRed: make(ANSI.brightRed),
    brightGreen: make(ANSI.brightGreen),
  };
}

export function supportsColor(stream: { isTTY?: boolean }): boolean {
  if (process.env.NO_COLOR !== undefined && process.env.NO_COLOR !== "") return false;
  if (process.env.FORCE_COLOR !== undefined && process.env.FORCE_COLOR !== "0") return true;
  return stream.isTTY === true && process.env.TERM !== "dumb";
}

export const style = createStyle(supportsColor(process.stdout));
