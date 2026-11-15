package com.fiesta1862.minicode.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobTest {

    @Test
    fun `double star crosses directories`() {
        val regex = Glob.toRegex("**/*.kt")
        assertTrue(regex.matches("Main.kt"))
        assertTrue(regex.matches("src/Main.kt"))
        assertTrue(regex.matches("src/a/b/Main.kt"))
        assertFalse(regex.matches("src/Main.java"))
    }

    @Test
    fun `single star stays in one segment`() {
        val regex = Glob.toRegex("src/*.kt")
        assertTrue(regex.matches("src/Main.kt"))
        assertFalse(regex.matches("src/a/Main.kt"))
    }

    @Test
    fun `question mark and classes`() {
        assertTrue(Glob.toRegex("file?.md").matches("file1.md"))
        assertFalse(Glob.toRegex("file?.md").matches("file10.md"))
        assertTrue(Glob.toRegex("file[0-9].md").matches("file7.md"))
        assertFalse(Glob.toRegex("file[!0-9].md").matches("file7.md"))
    }

    @Test
    fun `star matches dotfiles too`() {
        assertTrue(Glob.toRegex("*").matches(".gitignore"))
        assertTrue(Glob.toRegex("**/*.md").matches("docs/.hidden.md"))
    }
}
