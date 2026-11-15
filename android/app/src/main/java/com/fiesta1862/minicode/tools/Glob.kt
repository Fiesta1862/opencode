package com.fiesta1862.minicode.tools

object Glob {
    /** Converts a workspace-style glob to a regex. `*` stays in one segment; `**` crosses segments. */
    fun toRegex(pattern: String): Regex {
        if (pattern.isBlank()) throw ToolException("Glob pattern is empty")
        val regex = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            when (val char = pattern[index]) {
                '*' -> {
                    val double = index + 1 < pattern.length && pattern[index + 1] == '*'
                    if (double) {
                        index++
                        if (index + 1 < pattern.length && pattern[index + 1] == '/') {
                            index++
                            regex.append("(?:.*/)?")
                        } else {
                            regex.append(".*")
                        }
                    } else {
                        regex.append("[^/]*")
                    }
                }

                '?' -> regex.append("[^/]")

                '[' -> {
                    val end = pattern.indexOf(']', index + 1)
                    if (end < 0) {
                        regex.append("\\[")
                    } else {
                        val inner = pattern.substring(index + 1, end)
                        val content = if (inner.startsWith("!")) "^" + inner.drop(1) else inner
                        regex.append('[').append(content).append(']')
                        index = end
                    }
                }

                else -> regex.append(Regex.escape(char.toString()))
            }
            index++
        }
        regex.append('$')
        return try {
            Regex(regex.toString())
        } catch (error: Exception) {
            throw ToolException("Invalid glob pattern: ${error.message}")
        }
    }
}
