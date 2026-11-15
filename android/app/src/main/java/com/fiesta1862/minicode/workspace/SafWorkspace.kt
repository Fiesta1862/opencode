package com.fiesta1862.minicode.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

class SafWorkspace(
    private val context: Context,
    treeUri: Uri,
) : Workspace {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw WorkspaceException("Could not open the selected folder")

    override val displayName: String = root.name?.takeIf { it.isNotBlank() } ?: "workspace"

    private fun segments(path: String): List<String> =
        normalizeRelative(path).split('/').filter { it.isNotEmpty() }

    private fun resolve(path: String): DocumentFile? {
        var current = root
        for (segment in segments(path)) {
            val next = current.findFile(segment) ?: return null
            if (!next.exists()) return null
            current = next
        }
        return current
    }

    private fun resolveDirectory(path: String, create: Boolean): DocumentFile {
        var current = root
        for (segment in segments(path)) {
            val existing = current.findFile(segment)
            if (existing != null) {
                if (!existing.isDirectory) throw WorkspaceException("Not a directory: $path")
                current = existing
            } else {
                if (!create) throw WorkspaceException("Not a directory: $path")
                current = current.createDirectory(segment)
                    ?: throw WorkspaceException("Could not create folder: $segment")
            }
        }
        return current
    }

    override suspend fun entries(path: String, recursive: Boolean, max: Int): List<WorkspaceEntry> =
        withContext(Dispatchers.IO) {
            val base = resolve(path) ?: throw WorkspaceException("Not a directory: $path")
            if (!base.isDirectory) throw WorkspaceException("Not a directory: $path")
            val basePath = normalizeRelative(path)
            val result = mutableListOf<WorkspaceEntry>()

            fun walk(directory: DocumentFile, prefix: String, depth: Int) {
                if (result.size >= max || depth > 30) return
                val children = directory.listFiles().sortedBy { it.name?.lowercase() ?: "" }
                for (child in children) {
                    if (result.size >= max) break
                    val name = child.name?.takeIf { it.isNotBlank() } ?: continue
                    val childPath = if (prefix.isEmpty()) name else "$prefix/$name"
                    result += WorkspaceEntry(childPath, child.isDirectory)
                    if (recursive && child.isDirectory) walk(child, childPath, depth + 1)
                }
            }

            walk(base, basePath, 0)
            result
        }

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: throw WorkspaceException("Not a file: $path")
        if (file.isDirectory) throw WorkspaceException("Not a file: $path")
        val resolver = context.contentResolver
        val stream = resolver.openInputStream(file.uri)
            ?: throw WorkspaceException("Could not open $path")
        stream.use {
            val (bytes, truncated) = it.readLimited(maxBytes)
            decodeText(bytes, truncated, file.name ?: path)
        }
    }

    override suspend fun writeText(path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val relative = normalizeRelative(path)
        if (relative.isEmpty()) throw WorkspaceException("Choose a file path, not the workspace root")
        val parts = relative.split('/')
        val name = parts.last()
        val parent = resolveDirectory(parts.dropLast(1).joinToString("/"), create = true)

        var file = parent.findFile(name)
        if (file != null && file.isDirectory) throw WorkspaceException("Path is a directory: $path")
        if (file == null) {
            val created = parent.createFile(mimeTypeFor(name), name)
                ?: parent.createFile("application/octet-stream", name)
                ?: throw WorkspaceException("Could not create $path")

            // Some document providers append an extension that matches the MIME type
            // (for example hello.md becomes hello.md.txt). Restore the exact name.
            if (created.name != name) {
                val renamed = runCatching { created.renameTo(name) }.getOrDefault(false)
                if (!renamed) {
                    runCatching { created.delete() }
                    throw WorkspaceException(
                        "The selected folder renamed \"$name\" to \"${created.name}\". " +
                            "Try a different folder or file name.",
                    )
                }
                if (created.name != name) {
                    runCatching { created.delete() }
                    throw WorkspaceException("Could not create the exact file name: $name")
                }
            }
            file = created
        }

        val resolver = context.contentResolver
        val output: OutputStream = runCatching {
            resolver.openOutputStream(file.uri, "wt")
        }.getOrNull() ?: resolver.openOutputStream(file.uri, "w")
            ?: throw WorkspaceException("Could not write $path")
        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(path)?.exists() == true
    }

    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(path)?.isDirectory == true
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "md", "markdown" -> "text/markdown"
            "json", "jsonc" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "csv" -> "text/csv"
            "js", "mjs" -> "application/javascript"
            "txt", "kt", "kts", "java", "jsx", "ts", "tsx",
            "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "bash", "zsh",
            "scss", "yml", "yaml", "toml", "ini", "cfg", "conf", "sql",
            "gradle", "properties", "gitignore", "env", "tsv", "log",
            -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
