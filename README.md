# minicode

A minimal, fast AI coding agent for your terminal — built with TypeScript and Bun, distributed as a single binary from GitHub Releases.

> Hosted at [Fiesta1862/opencode](https://github.com/Fiesta1862/opencode). The application is called **minicode**; the repository name is historical and can be renamed later (GitHub keeps redirects).

```
  minicode
  model gpt-4o-mini · ~/projects/my-app
  /help for commands · Ctrl+C interrupts

› add a --verbose flag to the CLI and update the tests

⏺ I'll look at the current argument parsing first.
  ⚙ read src/cli.ts
  ✓ read src/cli.ts (142 lines)
  ⚙ edit src/cli.ts
    + const verbose = args.includes("--verbose");
    + if (verbose) console.log("verbose mode");
    Allow? y = once · a = always · n = no › y
  ✓ edited src/cli.ts +2 -0
⏺ Done. Added the flag and updated tests/cli.test.ts.
```

## Features

- **Terminal-native agent** — streaming responses, Markdown formatting, tool activity, and inline approval prompts.
- **OpenAI-compatible** — works with OpenAI, OpenRouter, Groq, Together, LM Studio, Ollama, vLLM, and anything else exposing `/v1/chat/completions`.
- **Real tools** — read, write, edit, glob, grep, list files, and run shell commands.
- **Safe by default** — file edits and commands require approval; paths are sandboxed to the current workspace, including symlink escapes.
- **Sessions** — conversations are saved automatically. Resume with `--continue` or `--session <id>`.
- **Single binary** — no Node, Bun, or `node_modules` required to run a release build.
- **Scriptable** — `minicode run "..."` for one-shot tasks, with a read-only-by-default policy unless `--yes` is passed.
- **No runtime dependencies** — the CLI and its TUI are implemented with the standard library.

## Install

From GitHub Releases (Linux x64/arm64, macOS x64/arm64, Windows x64):

```bash
curl -fsSL https://github.com/Fiesta1862/opencode/releases/latest/download/install.sh | bash
```

The installer downloads the right binary, verifies its SHA-256 checksum, and installs to `~/.local/bin`.

To install somewhere else (remember: variables go on the `bash` side of the pipe):

```bash
curl -fsSL https://github.com/Fiesta1862/opencode/releases/latest/download/install.sh | MINICODE_INSTALL_DIR="$HOME/bin" bash
```

Pin a version:

```bash
curl -fsSL https://github.com/Fiesta1862/opencode/releases/latest/download/install.sh | MINICODE_VERSION=0.1.0 bash
```

Or download the asset for your platform directly from the [releases page](https://github.com/Fiesta1862/opencode/releases).

## Quick start

```bash
export OPENAI_API_KEY=sk-...
minicode
```

For a local OpenAI-compatible server (e.g. Ollama):

```bash
export MINICODE_BASE_URL=http://localhost:11434/v1
export MINICODE_API_KEY=local
export MINICODE_MODEL=qwen2.5-coder:7b
minicode
```

> The base URL should include the API version prefix, usually `/v1`. For OpenAI the default is already `https://api.openai.com/v1`.

## Usage

```text
minicode                     Start an interactive session
minicode run [prompt]        Run one task and exit (reads stdin if no prompt)
minicode sessions            List saved sessions for this directory
minicode config              Show the resolved configuration
```

Useful options:

```text
-m, --model <name>           Model to use (default: gpt-4o-mini)
    --base-url <url>         OpenAI-compatible API root
    --api-key <key>          API key (prefer OPENAI_API_KEY)
    --max-steps <n>          Max model turns per message (default: 25)
-y, --yes                    Auto-approve file edits and commands
-c, --continue               Resume the most recent session here
-s, --session <id>           Resume a specific session
-h, --help                   Show help
-v, --version                Show the version
```

Examples:

```bash
minicode run "where is rate limiting implemented?"
minicode run --yes "fix the failing tests"
git diff | minicode run "review this change for bugs"
echo "explain src/agent.ts" | minicode
minicode --continue
```

### Interactive commands

| Command | Description |
| --- | --- |
| `/help` | Show commands |
| `/model [name]` | Show or change the model |
| `/clear` | Start a new session |
| `/config` | Show resolved configuration |
| `/exit` | Quit |

Press `Ctrl+C` once to interrupt a running response, twice to exit. Approval prompts accept `y` (once), `a` (always for this tool this session), or `n`.

## Tools

| Tool | Approval | Description |
| --- | --- | --- |
| `read_file` | no | Read a text file, with offset/limit |
| `write_file` | **yes** | Create or overwrite a file |
| `edit_file` | **yes** | Replace an exact string; shows a diff before approval |
| `list_files` | no | Indented directory tree |
| `glob` | no | Find files by pattern |
| `grep` | no | Search file contents by regex |
| `run_command` | **yes** | Run a shell command (120s default timeout, stdin closed) |

All paths are resolved inside the workspace. `node_modules`, `.git`, and binary files are skipped by the search tools. Long tool output is truncated before it is sent back to the model.

## Safety

- **Approvals:** edits and commands require confirmation unless you pass `--yes` or set `auto_approve` in config. In non-interactive `run` mode without `--yes`, actions are denied automatically.
- **Workspace sandbox:** paths that escape the working directory are rejected, including through symlinks.
- **No hidden network access:** the agent can still run commands that use the network if you approve them. Only run it in repositories you trust.
- **Not a security boundary:** approval screens reduce accidents, not a determined attacker. Review commands before approving.

## Configuration

Precedence, highest first:

1. CLI flags
2. Environment variables
3. `./minicode.json` (project)
4. `~/.config/minicode/config.json` (global)
5. Defaults

Example `minicode.json` (see `minicode.json.example`):

```json
{
  "model": "gpt-4o-mini",
  "base_url": "https://api.openai.com/v1",
  "max_steps": 25,
  "temperature": 0.2,
  "auto_approve": false,
  "system_prompt_extra": "Prefer functional style. Run bun test after changes."
}
```

Environment variables:

| Variable | Purpose |
| --- | --- |
| `OPENAI_API_KEY` / `MINICODE_API_KEY` | API key |
| `OPENAI_BASE_URL` / `MINICODE_BASE_URL` | API root |
| `MINICODE_MODEL` | Default model |
| `MINICODE_MAX_STEPS` | Default max steps |
| `MINICODE_AUTO_APPROVE` | `1` to auto-approve |
| `MINICODE_DATA_DIR` | Session storage (default `~/.local/share/minicode`) |
| `MINICODE_CONFIG_DIR` | Global config directory |
| `NO_COLOR` | Disable ANSI colors |

Sessions are stored as JSON under `~/.local/share/minicode/sessions/` and are scoped to the directory they were created in.

## Development

Requires [Bun](https://bun.sh) 1.2+.

```bash
bun install
bun run dev            # run from source
bun test               # unit + mock-server integration tests
bun run typecheck      # tsc --noEmit
bun run build          # binary for the current platform -> dist/
bun run build:all      # all release targets (cross-compiles)
```

The build injects the version from `package.json` and produces `dist/minicode-<os>-<arch>` plus `checksums.txt`.

### Publishing a release

On GitHub, with this repository pushed to `Fiesta1862/opencode`:

```bash
git tag v0.1.1
git push origin v0.1.1
```

`.github/workflows/release.yml` runs type checks and tests, cross-compiles every target, generates checksums, and attaches the binaries plus `install.sh` to the GitHub Release. The `ci.yml` workflow runs on pushes and pull requests.

If you rename the repository or fork it, update the default `REPO` in `install.sh` (or set `MINICODE_REPO=owner/repo` when installing).

## Project layout

```text
src/
  cli.ts              argument parsing, commands, help
  agent.ts            model/tool loop and system prompt
  llm.ts              OpenAI-compatible streaming client
  config.ts           flags/env/files resolution
  session.ts          JSON session persistence
  tools/              read, write, edit, glob, grep, list, shell
  ui/                 ANSI styling, streaming renderer, approvals
tests/                unit + mock-server integration tests
scripts/build.ts      cross-platform binary builds
install.sh            release installer
```

## Limitations

- No automatic context compaction yet; very long sessions eventually hit the model's context window.
- Approvals are per tool call (or per tool for `always`); there is no granular per-path policy yet.
- Markdown rendering is intentionally lightweight.
- Shell commands run with your user permissions.
- Windows shell execution uses PowerShell; Unix uses `bash`/`sh`.

## License

MIT
