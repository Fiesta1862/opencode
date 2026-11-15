package dev.openhost.app.core

import android.content.Context
import dev.openhost.app.BuildConfig
import dev.openhost.app.agent.Agent
import dev.openhost.app.agent.AgentEvent
import dev.openhost.app.agent.ChatMessage
import dev.openhost.app.agent.OpenAiClient
import dev.openhost.app.agent.systemPrompt
import dev.openhost.app.data.AppSettings
import dev.openhost.app.data.SettingsStore
import dev.openhost.app.tools.ToolRegistry
import dev.openhost.app.tools.WorkspaceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class ServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val lanIp: String = "",
    val tailscaleIp: String = "",
    val message: String = "",
)

object ServerHolder {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val threadPool = Executors.newCachedThreadPool()
    private val client = OpenAiClient()
    private var serverJob: Job? = null
    private var addressJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var appContext: Context? = null
    private val sessions = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /** Binds the port immediately. Returns an error message, or null on success. */
    fun start(settings: AppSettings): String? {
        stop()
        val port = settings.serverPort
        val socket = try {
            runBlocking(Dispatchers.IO) {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            }
        } catch (e: Exception) {
            val message = "Port $port unavailable: ${e.message ?: e.javaClass.simpleName}"
            _state.update { ServerState(running = false, port = port, message = message) }
            return message
        }

        serverSocket = socket
        val addresses = getNetworkAddresses()
        _state.update {
            ServerState(
                running = true,
                port = port,
                lanIp = addresses.lanIp,
                tailscaleIp = addresses.tailscaleIp,
                message = "Listening",
            )
        }

        serverJob = scope.launch {
            while (isActive) {
                val accepted = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                threadPool.submit { handle(accepted) }
            }
        }

        addressJob = scope.launch {
            while (isActive) {
                val current = getNetworkAddresses()
                _state.update { state ->
                    if (state.running) {
                        state.copy(lanIp = current.lanIp, tailscaleIp = current.tailscaleIp)
                    } else {
                        state
                    }
                }
                delay(10_000)
            }
        }
        return null
    }

    fun stop() {
        addressJob?.cancel()
        addressJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        sessions.clear()
        _state.update { previous ->
            if (previous.running) ServerState()
            else previous.copy(running = false, port = 0, lanIp = "", tailscaleIp = "")
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else {
                ""
            }

            val settings = currentSettings()
            val result = runBlocking { route(path, method, headers, body, settings) }
            writeResponse(socket.getOutputStream(), result.first, result.second, result.third)
        } catch (_: Exception) {
            // A broken connection should never take the server down.
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun currentSettings(): AppSettings {
        val context = appContext ?: return AppSettings()
        return runCatching { SettingsStore(context).load() }.getOrElse { AppSettings() }
    }

    private suspend fun route(
        path: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        settings: AppSettings,
    ): Triple<Int, String, String> {
        if (method == "OPTIONS") return Triple(204, "text/plain", "")

        val authed = checkAuth(headers, settings.serverToken)
        return when (path) {
            "/", "/index.html" -> Triple(200, "text/html; charset=utf-8", webUiPage(settings.serverToken))
            "/favicon.ico" -> Triple(204, "text/plain", "")
            "/health" -> {
                val addresses = getNetworkAddresses()
                val json = JSONObject().apply {
                    put("status", "ok")
                    put("version", BuildConfig.VERSION_NAME)
                    put("port", _state.value.port)
                    put("lan_ip", addresses.lanIp)
                    put("tailscale_ip", addresses.tailscaleIp)
                }
                Triple(200, "application/json", json.toString())
            }
            "/api/status" -> {
                if (!authed) return unauthorized()
                val addresses = getNetworkAddresses()
                val json = JSONObject().apply {
                    put("status", "ok")
                    put("port", _state.value.port)
                    put("lan_ip", addresses.lanIp)
                    put("tailscale_ip", addresses.tailscaleIp)
                    put("model", settings.model)
                    put("autoApprove", settings.autoApprove)
                }
                Triple(200, "application/json", json.toString())
            }
            "/api/v1/chat" -> {
                if (!authed) return unauthorized()
                if (method != "POST") return Triple(405, "application/json", "{\"error\":\"method not allowed\"}")
                val request = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                val message = request.optString("message", "").trim()
                if (message.isEmpty()) {
                    return Triple(400, "application/json", "{\"error\":\"message required\"}")
                }
                val sessionId = request.optString("sessionId", "default").ifBlank { "default" }
                val allowWrites = request.optBoolean("autoApprove", false) || settings.autoApprove

                val workspace = appContext?.let { WorkspaceFactory.create(it, settings) }
                val runSettings = settings.copy(autoApprove = allowWrites)
                val history = sessions.computeIfAbsent(sessionId) { mutableListOf() }
                val text = StringBuilder()
                val toolLog = JSONArray()

                val agent = Agent(
                    client = client,
                    tools = { ToolRegistry(workspace) },
                    settings = { runSettings },
                    systemPrompt = {
                        systemPrompt(workspace?.displayName ?: "app storage", workspace != null)
                    },
                    approve = { _, _ -> allowWrites },
                )

                try {
                    agent.run(history, message).collect { event ->
                        when (event) {
                            is AgentEvent.TextDelta -> text.append(event.text)
                            is AgentEvent.AssistantTurn -> Unit
                            is AgentEvent.ToolStarted -> Unit
                            is AgentEvent.ToolFinished -> toolLog.put(
                                JSONObject().apply {
                                    put("name", event.call.name)
                                    put("summary", event.result.summary)
                                    put("isError", event.result.isError)
                                },
                            )
                            is AgentEvent.ToolDenied -> toolLog.put(
                                JSONObject().apply {
                                    put("name", event.call.name)
                                    put("summary", "denied (enable \"allow file changes\")")
                                    put("isError", false)
                                },
                            )
                            is AgentEvent.StepLimit -> Unit
                            is AgentEvent.Done -> Unit
                        }
                    }
                } catch (e: Exception) {
                    return Triple(
                        500,
                        "application/json",
                        JSONObject().put("error", (e.message ?: "agent failed").take(500)).toString(),
                    )
                }

                if (history.size > 80) history.clear()

                val response = JSONObject().apply {
                    put("response", text.toString())
                    put("tools", toolLog)
                    put("sessionId", sessionId)
                }
                Triple(200, "application/json", response.toString())
            }
            "/api/v1/new_session" -> {
                if (!authed) return unauthorized()
                val request = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                sessions.remove(request.optString("sessionId", "default").ifBlank { "default" })
                Triple(200, "application/json", "{\"ok\":true}")
            }
            else -> Triple(404, "application/json", "{\"error\":\"not found\"}")
        }
    }

    private fun unauthorized(): Triple<Int, String, String> =
        Triple(401, "application/json", "{\"error\":\"unauthorized\"}")

    private fun checkAuth(headers: Map<String, String>, token: String): Boolean {
        if (token.isEmpty()) return false
        val auth = headers["authorization"] ?: return false
        return auth.removePrefix("Bearer ").trim() == token
    }

    private fun readHttpLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (byte == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(byte)
            if (buffer.size() > 32 * 1024) return null
        }
    }

    private fun writeResponse(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }
}

private fun webUiPage(token: String): String = """
<!DOCTYPE html>
<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>OpenHost</title>
<style>
  *{box-sizing:border-box}
  body{font-family:system-ui,sans-serif;margin:0;background:#f6f4fb;color:#1d1b1d}
  .wrap{max-width:760px;margin:0 auto;height:100vh;display:flex;flex-direction:column}
  header{padding:14px 16px;background:#6750a4;color:#fff;display:flex;justify-content:space-between;align-items:center}
  header h1{font-size:18px;margin:0}
  header button{background:rgba(255,255,255,.2);border:0;color:#fff;padding:6px 12px;border-radius:8px;font-size:13px}
  #chat{flex:1;overflow-y:auto;padding:16px}
  .msg{margin:8px 0;padding:10px 12px;border-radius:12px;max-width:85%;white-space:pre-wrap;line-height:1.45;font-size:14px}
  .user{background:#6750a4;color:#fff;margin-left:auto}
  .agent{background:#fff;border:1px solid #e6e0f0}
  .tool{background:#fdf4d7;border:1px solid #eedc9a;font-size:12px;color:#6b5b1d;max-width:95%;font-family:monospace}
  .notice{background:#ffe9e9;border:1px solid #f3b6b6;font-size:12px;color:#8a1f1f}
  footer{padding:10px;background:#fff;border-top:1px solid #e6e0f0}
  .row{display:flex;gap:8px}
  input[type=text]{flex:1;padding:10px 12px;border:1px solid #ccc;border-radius:10px;font-size:14px}
  button.send{background:#6750a4;color:#fff;border:0;border-radius:10px;padding:0 18px;font-size:14px}
  button:disabled{opacity:.5}
  .opts{display:flex;align-items:center;gap:6px;margin-top:8px;font-size:12px;color:#555}
  #status{font-size:11px;color:#777;padding:4px 16px;background:#f6f4fb}
</style></head>
<body>
<div class="wrap">
  <header>
    <h1>OpenHost Agent</h1>
    <button onclick="newChat()">New chat</button>
  </header>
  <div id="chat"></div>
  <div id="status">ready</div>
  <footer>
    <div class="row">
      <input id="m" type="text" placeholder="Ask the agent..." onkeydown="if(event.key==='Enter')send()">
      <button class="send" id="sendBtn" onclick="send()">Send</button>
    </div>
    <label class="opts"><input id="writes" type="checkbox"> allow file changes</label>
  </footer>
</div>
<script>
const TOKEN = '$token';
let sessionId = 's-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
const chat = document.getElementById('chat');
const statusEl = document.getElementById('status');
function add(cls, text) {
  const d = document.createElement('div');
  d.className = 'msg ' + cls;
  d.textContent = text;
  chat.appendChild(d);
  chat.scrollTop = chat.scrollHeight;
  return d;
}
function setStatus(t){ statusEl.textContent = t; }
async function send() {
  const input = document.getElementById('m');
  const text = input.value.trim();
  if (!text) return;
  input.value = '';
  add('user', text);
  const btn = document.getElementById('sendBtn');
  btn.disabled = true;
  setStatus('thinking...');
  const pending = add('agent', '...');
  try {
    const res = await fetch('/api/v1/chat', {
      method: 'POST',
      headers: {'Content-Type':'application/json','Authorization':'Bearer '+TOKEN},
      body: JSON.stringify({
        message: text,
        sessionId,
        autoApprove: document.getElementById('writes').checked
      })
    });
    const data = await res.json();
    pending.remove();
    if (!res.ok) { add('notice', data.error || ('HTTP ' + res.status)); setStatus('error'); return; }
    if (data.tools && data.tools.length) {
      for (const t of data.tools) add('tool', 'tool ' + t.name + ' - ' + t.summary + (t.isError ? ' (error)' : ''));
    }
    add('agent', data.response || '(no response)');
    setStatus('ready');
  } catch (e) {
    pending.remove();
    add('notice', 'Error: ' + e);
    setStatus('error');
  } finally {
    btn.disabled = false;
  }
}
async function newChat() {
  try {
    await fetch('/api/v1/new_session', {
      method: 'POST',
      headers: {'Content-Type':'application/json','Authorization':'Bearer '+TOKEN},
      body: JSON.stringify({sessionId})
    });
  } catch (e) {}
  sessionId = 's-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
  chat.innerHTML = '';
  setStatus('new session');
}
</script>
</body></html>
""".trimIndent()
