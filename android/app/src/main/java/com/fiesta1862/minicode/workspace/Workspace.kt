package com.fiesta1862.minicode.workspace

import android.content.Context
import android.net.Uri
import com.fiesta1862.minicode.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

data class WorkspaceEntry(
    val path: String,
    val isDirectory: Boolean,
)

interface Workspace {
    val displayName: String

    suspend fun entries(path: String = "", recursive: Boolean = false, max: Int = 2000): List<WorkspaceEntry>

    suspend fun readText(path: String, maxBytes: Int = 1_000_000): String

    suspend fun writeText(path: String, content: String)

    suspend fun exists(path: String): Boolean

    suspend fun isDirectory(path: String): Boolean
}

class WorkspaceException(message: String) : IOException(message)

fun normalizeRelative(path: String): String {
    val trimmed = path.trim()
    if (trimmed.startsWith("/")) {
        throw WorkspaceException("Paths must be relative to the workspace: $path")
    }
    val cleaned = trimmed.replace('\\', '/')
    if (cleaned.contains('\u0000')) throw WorkspaceException("Invalid path")
    if (cleaned.isBlank()) return ""
    val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) {
        throw WorkspaceException("Path escapes the workspace: $path")
    }
    return parts.joinToString("/")
}

internal fun InputStream.readLimited(maxBytes: Int): Pair<ByteArray, Boolean> {
    val buffer = ByteArray(maxBytes)
    var total = 0
    while (total < maxBytes) {
        val read = read(buffer, total, maxBytes - total)
        if (read <= 0) break
        total += read
    }
    val bytes = if (total == buffer.size) buffer else buffer.copyOf(total)
    val extra = read()
    return bytes to (extra >= 0)
}

internal fun decodeText(bytes: ByteArray, truncated: Boolean, path: String): String {
    if (bytes.take(8000).any { it == 0.toByte() }) {
        throw WorkspaceException("$path looks like a binary file")
    }
    val text = String(bytes, Charsets.UTF_8)
    return if (truncated) "$text\n\n[File truncated by minicode]" else text
}

class LocalWorkspace(
    root: File,
    override val displayName: String = "app storage",
) : Workspace {
    private val root: File = root.canonicalFile

    init {
        this.root.mkdirs()
    }

    private fun resolve(path: String): File {
        val relative = normalizeRelative(path)
        val candidate = if (relative.isEmpty()) root else File(root, relative)
        val canonical = candidate.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw WorkspaceException("Path escapes the workspace: $path")
        }
        return canonical
    }

    private fun relative(file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    override suspend fun entries(path: String, recursive: Boolean, max: Int): List<WorkspaceEntry> =
        withContext(Dispatchers.IO) {
            val base = resolve(path)
            if (!base.isDirectory) throw WorkspaceException("Not a directory: $path")
            val result = mutableListOf<WorkspaceEntry>()
            if (recursive) {
                val queue = ArrayDeque<File>()
                queue.add(base)
                while (queue.isNotEmpty() && result.size < max) {
                    val dir = queue.removeFirst()
                    val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
                    for (child in children) {
                        if (result.size >= max) break
                        if (Files.isSymbolicLink(child.toPath())) continue
                        result += WorkspaceEntry(relative(child), child.isDirectory)
                        if (child.isDirectory) queue.add(child)
                    }
                }
            } else {
                val children = base.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
                for (child in children) {
                    if (result.size >= max) break
                    if (Files.isSymbolicLink(child.toPath())) continue
                    result += WorkspaceEntry(relative(child), child.isDirectory)
                }
            }
            result
        }

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.isFile) throw WorkspaceException("Not a file: $path")
        val (bytes, truncated) = file.inputStream().use { it.readLimited(maxBytes) }
        decodeText(bytes, truncated, file.name)
    }

    override suspend fun writeText(path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (file.isDirectory) throw WorkspaceException("Path is a directory: $path")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(path).exists()
    }

    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(path).isDirectory
    }
}

object WorkspaceFactory {
    fun create(context: Context, settings: AppSettings): Workspace {
        val uri = settings.workspaceUri
        if (uri != null) {
            return SafWorkspace(context, Uri.parse(uri))
        }
        return LocalWorkspace(File(context.filesDir, "workspace"), displayName = "app storage")
    }
}
