package com.fiesta1862.minicode.tools

import com.fiesta1862.minicode.llm.ToolCall
import com.fiesta1862.minicode.workspace.LocalWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        root = temp.newFolder("workspace")
        registry = ToolRegistry(LocalWorkspace(root))
    }

    private fun call(name: String, arguments: String) = ToolCall("call_1", name, arguments)

    @Test
    fun `write and read a file`() = runBlocking {
        val write = registry.execute(
            call("write_file", """{"path":"notes/hello.md","content":"line one\nline two"}"""),
        )
        assertFalse(write.output, write.isError)
        assertTrue(File(root, "notes/hello.md").isFile)

        val read = registry.execute(call("read_file", """{"path":"notes/hello.md"}"""))
        assertFalse(read.output, read.isError)
        assertTrue(read.output.contains("1\tline one"))
        assertTrue(read.output.contains("2\tline two"))
    }

    @Test
    fun `edit requires a unique match unless replace_all`() = runBlocking {
        registry.execute(call("write_file", """{"path":"a.txt","content":"cat cat dog"}"""))

        val ambiguous = registry.execute(
            call("edit_file", """{"path":"a.txt","old_string":"cat","new_string":"fox"}"""),
        )
        assertTrue(ambiguous.isError)
        assertTrue(ambiguous.output.contains("occurs 2 times"))

        val replaceAll = registry.execute(
            call(
                "edit_file",
                """{"path":"a.txt","old_string":"cat","new_string":"fox","replace_all":true}""",
            ),
        )
        assertFalse(replaceAll.output, replaceAll.isError)
        assertEquals("fox fox dog", File(root, "a.txt").readText())

        val missing = registry.execute(
            call("edit_file", """{"path":"a.txt","old_string":"elephant","new_string":"x"}"""),
        )
        assertTrue(missing.isError)
    }

    @Test
    fun `paths outside the workspace are rejected`() = runBlocking {
        val read = registry.execute(call("read_file", """{"path":"../../etc/passwd"}"""))
        assertTrue(read.isError)
        assertTrue(read.output.contains("escapes the workspace"))

        val absolute = registry.execute(call("write_file", """{"path":"/tmp/x","content":"x"}"""))
        assertTrue(absolute.isError)

        assertFalse(File(temp.root, "x").exists())
    }

    @Test
    fun `list glob and grep`() = runBlocking {
        registry.execute(call("write_file", """{"path":"README.md","content":"# Project"}"""))
        registry.execute(call("write_file", """{"path":"src/Main.kt","content":"fun main() = needle()"}"""))
        registry.execute(call("write_file", """{"path":"src/util/Helpers.kt","content":"// needle helper"}"""))

        val listing = registry.execute(call("list_files", """{"recursive":true}"""))
        assertFalse(listing.output, listing.isError)
        assertTrue(listing.output.contains("src/Main.kt"))
        assertTrue(listing.output.contains("src/util/"))

        val glob = registry.execute(call("glob", """{"pattern":"**/*.kt"}"""))
        assertFalse(glob.output, glob.isError)
        assertTrue(glob.output.contains("src/Main.kt"))
        assertTrue(glob.output.contains("src/util/Helpers.kt"))
        assertFalse(glob.output.contains("README.md"))

        val nestedGlob = registry.execute(call("glob", """{"pattern":"*.kt","path":"src"}"""))
        assertTrue(nestedGlob.output.contains("src/Main.kt"))
        assertFalse(nestedGlob.output.contains("Helpers.kt"))

        val grep = registry.execute(call("grep", """{"pattern":"needle","include":"**/*.kt"}"""))
        assertFalse(grep.output, grep.isError)
        assertTrue(grep.output.contains("src/Main.kt:1"))
        assertTrue(grep.output.contains("src/util/Helpers.kt:1"))

        val noMatch = registry.execute(call("grep", """{"pattern":"zebra"}"""))
        assertFalse(noMatch.isError)
        assertTrue(noMatch.output.contains("No matches"))
    }

    @Test
    fun `missing arguments produce tool errors`() = runBlocking {
        val result = registry.execute(call("write_file", """{"path":"a.txt"}"""))
        assertTrue(result.isError)
        assertTrue(result.output.contains("Missing required argument: content"))
    }

    @Test
    fun `approval preview shows path and content`() {
        val preview = ToolRegistry.approvalPreview(
            call("write_file", """{"path":"hello.md","content":"# Hello"}"""),
        )
        assertTrue(preview.contains("hello.md"))
        assertTrue(preview.contains("# Hello"))
    }
}
