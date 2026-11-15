package com.fiesta1862.minicode.tools

import com.fiesta1862.minicode.llm.ToolCall
import com.fiesta1862.minicode.llm.ToolSpec
import com.fiesta1862.minicode.workspace.Workspace
import com.fiesta1862.minicode.workspace.WorkspaceException
import com.fiesta1862.minicode.workspace.normalizeRelative
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

data class ToolResult(
    val summary: String,
    val output: String,
    val isError: Boolean = false,
)

class ToolException(message: String) : Exception(message)

class ToolRegistry(private val workspace: Workspace?) {

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "read_file",
            description = "Read a text file from the workspace. Returns numbered lines. Use offset and limit for large files.",
            parameters = schema(
                required = listOf("path"),
                properties = linkedMapOf(
                    "path" to stringProp("Relative path inside the workspace"),
                    "offset" to integerProp("First line to return, 1-based. Defaults to 1."),
                    "limit" to integerProp("Maximum number of lines. Defaults to 400, maximum 2000."),
                ),
            ),
        ),
        ToolSpec(
            name = "write_file",
            description = "Create or overwrite a text file in the workspace. Always show the user what you are writing.",
            parameters = schema(
                required = listOf("path", "content"),
                properties = linkedMapOf(
                    "path" to stringProp("Relative path inside the workspace"),
                    "content" to stringProp("Complete new file contents"),
                ),
            ),
            requiresApproval = true,
        ),
        ToolSpec(
            name = "edit_file",
            description = "Replace an exact string in a text file. By default old_string must occur exactly once; use replace_all for every occurrence.",
            parameters = schema(
                required = listOf("path", "old_string", "new_string"),
                properties = linkedMapOf(
                    "path" to stringProp("Relative path inside the workspace"),
                    "old_string" to stringProp("Exact text to replace"),
                    "new_string" to stringProp("Replacement text"),
                    "replace_all" to booleanProp("Replace every occurrence instead of requiring a unique match"),
                ),
            ),
            requiresApproval = true,
        ),
        ToolSpec(
            name = "list_files",
            description = "List files and folders in the workspace. Set recursive to true to walk subdirectories.",
            parameters = schema(
                required = emptyList(),
                properties = linkedMapOf(
                    "path" to stringProp("Relative directory. Defaults to the workspace root."),
                    "recursive" to booleanProp("Walk subdirectories"),
                ),
            ),
        ),
        ToolSpec(
            name = "glob",
            description = "Find files by glob pattern, for example **/*.kt or src/**/*.md.",
            parameters = schema(
                required = listOf("pattern"),
                properties = linkedMapOf(
                    "pattern" to stringProp("Glob pattern using * and **"),
                    "path" to stringProp("Relative directory to search. Defaults to the workspace root."),
                ),
            ),
        ),
        ToolSpec(
            name = "grep",
            description = "Search text files by regular expression. Returns path:line: matching text.",
            parameters = schema(
                required = listOf("pattern"),
                properties = linkedMapOf(
                    "pattern" to stringProp("Regular expression to search for"),
                    "path" to stringProp("Relative directory to search. Defaults to the workspace root."),
                    "include" to stringProp("Optional glob filter, for example **/*.kt"),
                ),
            ),
        ),
    )

    suspend fun execute(call: ToolCall): ToolResult {
        val ws = workspace
            ?: return error("No workspace is selected. Ask the user to choose a folder in Settings.")
        val args = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (exception: Exception) {
            return error("Invalid arguments JSON: ${exception.message}")
        }

        return try {
            when (call.name) {
                "read_file" -> readFile(ws, args)
                "write_file" -> writeFile(ws, args)
                "edit_file" -> editFile(ws, args)
                "list_files" -> listFiles(ws, args)
                "glob" -> glob(ws, args)
                "grep" -> grep(ws, args)
                else -> error("Unknown tool: ${call.name}")
            }
        } catch (exception: ToolException) {
            error(exception.message ?: "Tool failed")
        } catch (exception: WorkspaceException) {
            error(exception.message ?: "Workspace error")
        } catch (exception: Exception) {
            error("${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private suspend fun readFile(workspace: Workspace, args: JSONObject): ToolResult {
        val path = args.requiredString("path")
        val offset = args.optionalInt("offset", 1).coerceAtLeast(1)
        val limit = args.optionalInt("limit", 400).coerceIn(1, 2000)
        val text = workspace.readText(path, maxBytes = 1_000_000)
        val content = text.removeSuffix("\n")
        val lines = if (content.isEmpty()) emptyList() else content.split("\n")
        val total = lines.size

        if (total == 0) {
            return ToolResult("read $path (0 lines)", "(empty file)")
        }
        if (offset > total) {
            return error("Offset $offset is past the end of $path ($total lines)")
        }

        val start = min(offset - 1, total)
        val end = min(total, start + limit)
        val numbered = (start until end).joinToString("\n") { index ->
            "${index + 1}\t${lines[index]}"
        }
        return ToolResult("read $path ($total lines)", truncate(numbered, 30_000))
    }

    private suspend fun writeFile(workspace: Workspace, args: JSONObject): ToolResult {
        val path = args.requiredString("path")
        val content = args.requiredString("content")
        workspace.writeText(path, content)
        val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        return ToolResult(
            summary = "wrote $path ($lines lines)",
            output = "Wrote $lines lines to $path.",
        )
    }

    private suspend fun editFile(workspace: Workspace, args: JSONObject): ToolResult {
        val path = args.requiredString("path")
        val oldString = args.requiredString("old_string")
        val newString = args.requiredString("new_string")
        val replaceAll = args.optionalBoolean("replace_all", false)
        if (oldString.isEmpty()) throw ToolException("old_string cannot be empty")

        val original = workspace.readText(path, maxBytes = 1_000_000)
        val occurrences = countOccurrences(original, oldString)
        when {
            occurrences == 0 -> throw ToolException("old_string was not found in $path")
            occurrences > 1 && !replaceAll -> throw ToolException(
                "old_string occurs $occurrences times in $path; provide more context or set replace_all",
            )
        }

        val updated = if (replaceAll) original.replace(oldString, newString)
        else original.replaceFirst(oldString, newString)
        workspace.writeText(path, updated)

        val removed = oldString.count { it == '\n' } + 1
        val added = newString.count { it == '\n' } + 1
        return ToolResult(
            summary = "edited $path (+$added -$removed)",
            output = "Replaced ${if (replaceAll) occurrences else 1} occurrence(s) in $path.",
        )
    }

    private suspend fun listFiles(workspace: Workspace, args: JSONObject): ToolResult {
        val path = args.optionalString("path", "")
        val recursive = args.optionalBoolean("recursive", false)
        val entries = workspace.entries(path, recursive, max = 2000)
        if (entries.isEmpty()) return ToolResult("listed 0 entries", "(empty folder)")

        val lines = entries.take(500).map { entry ->
            if (entry.isDirectory) "${entry.path}/" else entry.path
        }
        val more = if (entries.size > 500) "\n… ${entries.size - 500} more entries" else ""
        return ToolResult(
            summary = "listed ${entries.size} entries",
            output = truncate(lines.joinToString("\n") + more, 30_000),
        )
    }

    private suspend fun glob(workspace: Workspace, args: JSONObject): ToolResult {
        val pattern = args.requiredString("pattern")
        val base = args.optionalString("path", "")
        val baseNormalized = normalizeRelative(base)
        val regex = Glob.toRegex(pattern)

        val matches = workspace.entries(baseNormalized, recursive = true, max = 5000)
            .filter { !it.isDirectory }
            .mapNotNull { entry ->
                val relative = if (baseNormalized.isEmpty()) entry.path
                else entry.path.removePrefix(baseNormalized).trimStart('/')
                if (regex.matches(relative)) entry.path else null
            }
            .sorted()
            .take(200)

        return ToolResult(
            summary = "found ${matches.size} files",
            output = if (matches.isEmpty()) "No files matched $pattern." else matches.joinToString("\n"),
        )
    }

    private suspend fun grep(workspace: Workspace, args: JSONObject): ToolResult {
        val pattern = args.requiredString("pattern")
        val base = args.optionalString("path", "")
        val include = args.optionalString("include", "")
        val baseNormalized = normalizeRelative(base)
        val regex = try {
            Regex(pattern)
        } catch (exception: Exception) {
            throw ToolException("Invalid regex: ${exception.message}")
        }
        val includeRegex = if (include.isBlank()) null else Glob.toRegex(include)

        val entries = workspace.entries(baseNormalized, recursive = true, max = 2000)
            .filter { !it.isDirectory }
            .filter { entry ->
                if (includeRegex == null) return@filter true
                val relative = if (baseNormalized.isEmpty()) entry.path
                else entry.path.removePrefix(baseNormalized).trimStart('/')
                includeRegex.matches(relative)
            }
            .take(500)

        val matches = mutableListOf<String>()
        var filesWithMatches = 0
        var truncated = false

        outer@ for (entry in entries) {
            val text = runCatching {
                workspace.readText(entry.path, maxBytes = 256_000)
            }.getOrNull() ?: continue

            var found = false
            for ((lineIndex, line) in text.lineSequence().withIndex()) {
                if (!regex.containsMatchIn(line)) continue
                found = true
                if (matches.size >= 100) {
                    truncated = true
                    break@outer
                }
                matches += "${entry.path}:${lineIndex + 1}: ${line.trim().take(300)}"
            }
            if (found) filesWithMatches++
        }

        val output = buildString {
            if (matches.isEmpty()) {
                append("No matches for $pattern.")
            } else {
                append(matches.joinToString("\n"))
                if (truncated) append("\n… results truncated at 100 matches")
            }
        }
        return ToolResult(
            summary = "found ${matches.size} matches in $filesWithMatches files",
            output = truncate(output, 30_000),
        )
    }

    private fun error(message: String): ToolResult =
        ToolResult(summary = "error", output = message, isError = true)

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf(needle, index)
            if (found < 0) break
            count++
            index = found + needle.length
        }
        return count
    }

    companion object {
        fun approvalPreview(call: ToolCall): String {
            val args = call.args()
            return when (call.name) {
                "write_file" -> {
                    val path = args.optString("path")
                    val content = args.optString("content")
                    "Path: $path\n\n${previewText(content)}"
                }

                "edit_file" -> {
                    val path = args.optString("path")
                    val oldText = args.optString("old_string")
                    val newText = args.optString("new_string")
                    buildString {
                        append("Path: ").append(path).append("\n\n")
                        append("- ").append(oldText.take(1800).replace("\n", "\n- "))
                        append("\n+ ").append(newText.take(1800).replace("\n", "\n+ "))
                    }
                }

                else -> call.arguments.take(3000)
            }.take(4000)
        }

        private fun previewText(content: String): String {
            val lines = content.lines()
            val shown = lines.take(40).joinToString("\n")
            return if (lines.size > 40) "$shown\n… ${lines.size - 40} more lines" else shown
        }

        private fun truncate(text: String, max: Int): String =
            if (text.length <= max) text else text.take(max) + "\n… output truncated"

        private fun schema(required: List<String>, properties: Map<String, JSONObject>): JSONObject =
            JSONObject().apply {
                put("type", "object")
                put(
                    "properties",
                    JSONObject().apply {
                        properties.forEach { (name, value) -> put(name, value) }
                    },
                )
                put("required", JSONArray(required))
            }

        private fun stringProp(description: String): JSONObject =
            JSONObject().put("type", "string").put("description", description)

        private fun integerProp(description: String): JSONObject =
            JSONObject().put("type", "integer").put("description", description)

        private fun booleanProp(description: String): JSONObject =
            JSONObject().put("type", "boolean").put("description", description)
    }
}

private fun JSONObject.requiredString(name: String): String {
    if (!has(name) || isNull(name)) throw ToolException("Missing required argument: $name")
    val value = opt(name)
    if (value !is String) throw ToolException("Argument $name must be a string")
    return value
}

private fun JSONObject.optionalString(name: String, default: String): String {
    if (!has(name) || isNull(name)) return default
    val value = opt(name)
    if (value !is String) throw ToolException("Argument $name must be a string")
    return value
}

private fun JSONObject.optionalInt(name: String, default: Int): Int {
    if (!has(name) || isNull(name)) return default
    val value = opt(name)
    if (value is Number) return value.toInt()
    throw ToolException("Argument $name must be an integer")
}

private fun JSONObject.optionalBoolean(name: String, default: Boolean): Boolean {
    if (!has(name) || isNull(name)) return default
    val value = opt(name)
    if (value is Boolean) return value
    throw ToolException("Argument $name must be a boolean")
}
