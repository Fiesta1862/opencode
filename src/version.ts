/**
 * Injected at build time by `scripts/build.ts` via `--define`.
 * Falls back to a dev marker when running from source.
 */
export const VERSION: string = process.env.MINICODE_VERSION ?? "0.1.0-dev";
