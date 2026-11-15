# OpenHost - a 24/7 agent server on your phone

OpenHost is an Android app that runs an AI coding agent and an HTTP server
directly on your phone. No PC, no Termux, and no separate CLI are required.

The app is self-contained: it talks to any OpenAI-compatible chat API, has
file tools sandboxed to a workspace, and serves the same agent to a phone
browser over LAN or Tailscale.

## Features

- **In-app chat** - talk to the agent from the Chat tab, with streaming replies
- **Web chat** - open the server URL in any browser on the same network
- **File tools** - read, write, edit, list, glob, and grep inside a workspace
- **Approvals** - writes and edits need confirmation unless auto-approve is on
- **Foreground service** - a persistent notification and wake lock keep the
  server alive when the app is in the background
- **LAN + Tailscale** - shows both addresses; Tailscale CGNAT addresses are
  detected automatically
- **Settings** - API base URL, API key, model, max steps, port, workspace folder,
  and access token

## Install

1. Download `openhost-release.apk` from the latest release and sideload it.
2. Open OpenHost and allow notifications (Android 13+).
3. Open **Settings**, enter an OpenAI-compatible **API base URL**, **API key**,
   and **model**, then tap **Save settings**.
4. Open the **Server** tab and tap **Start server**.
5. Use the **Chat** tab, or open the displayed localhost / LAN / Tailscale URL.
6. For 24/7 uptime, set OpenHost battery usage to **Unrestricted** in Android
   settings (and enable autostart on Xiaomi-style devices).

Examples of compatible APIs:

| Provider | Base URL | Model example |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| Ollama on LAN | `http://192.168.1.10:11434/v1` | `llama3.2` |

## Web UI and API

The server embeds the access token in the web page served at `/`, so opening
the URL in a browser is enough. Keep that URL and token private: anyone who can
reach the server can use the agent and its workspace.

| Endpoint | Method | Auth | Description |
|---|---|---|---|
| `/` | GET | No | Web chat UI |
| `/health` | GET | No | Health check with port and addresses |
| `/api/status` | GET | Bearer | Status, model, addresses |
| `/api/v1/chat` | POST | Bearer | Run the agent for one message |
| `/api/v1/new_session` | POST | Bearer | Clear a server-side session |

Example:

```bash
curl -s http://127.0.0.1:8080/api/v1/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"List the files in the workspace","sessionId":"demo"}'
```

`/api/v1/chat` is synchronous: it returns the final text plus a `tools` array
after the agent finishes. The browser UI uses the same endpoint.

## Build locally

```bash
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew :openhost:testDebugUnitTest
./gradlew :openhost:assembleDebug
```

The debug APK is written to
`android/openhost/build/outputs/apk/debug/openhost-debug.apk`.

## Release

Pushing an `openhost-v*` tag triggers
`.github/workflows/openhost-release.yml`.

For a signed release, configure these repository secrets:

| Secret | Description |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded JKS keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

If they are missing, the workflow still publishes a debug-signed APK so the
release pipeline never silently produces nothing. Signed and debug APKs have
different signatures, so users cannot update between them without reinstalling.

## Current limitations

- The agent talks to an external OpenAI-compatible API; it does not run a model
  on the phone.
- SSH connection management and a remote OpenCode bridge are not implemented.
- There is no shell tool on Android. File tools are limited to the workspace.
- The server uses plain HTTP. Use Tailscale (or a trusted LAN) rather than
  exposing the port to the public internet.
