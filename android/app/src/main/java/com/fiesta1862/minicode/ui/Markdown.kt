package com.fiesta1862.minicode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val dividerRegex = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

private fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.append(lines[index])
                    if (index < lines.size - 1) code.append('\n')
                    index++
                }
                blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
            }

            headingRegex.matches(trimmed) -> {
                flushParagraph()
                val match = headingRegex.matchEntire(trimmed)!!
                blocks += MdBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                )
            }

            dividerRegex.matches(trimmed) -> {
                flushParagraph()
                blocks += MdBlock.Divider
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quote += lines[index].trim().removePrefix(">").trimStart()
                    index++
                }
                index--
                blocks += MdBlock.Quote(quote.joinToString("\n"))
            }

            bulletRegex.matches(line) -> {
                flushParagraph()
                val match = bulletRegex.matchEntire(line)!!
                blocks += MdBlock.ListItem(
                    marker = "•",
                    text = match.groupValues[2],
                    indent = match.groupValues[1].length,
                )
            }

            orderedRegex.matches(line) -> {
                flushParagraph()
                val match = orderedRegex.matchEntire(line)!!
                blocks += MdBlock.ListItem(
                    marker = match.groupValues[2] + ".",
                    text = match.groupValues[3],
                    indent = match.groupValues[1].length,
                )
            }

            trimmed.isEmpty() -> flushParagraph()

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MinicodeColors.Text,
) {
    val blocks = parseMarkdown(text)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.text, color = color)

                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    color = color,
                    fontSize = when (block.level) {
                        1 -> 23.sp
                        2 -> 20.sp
                        3 -> 18.sp
                        else -> 16.sp
                    },
                    lineHeight = when (block.level) {
                        1 -> 30.sp
                        2 -> 27.sp
                        else -> 24.sp
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )

                is MdBlock.Code -> CodeBlock(block.language, block.code)

                is MdBlock.ListItem -> Row(
                    modifier = Modifier.padding(start = (block.indent / 2 * 14).dp),
                ) {
                    Text(
                        text = block.marker,
                        color = MinicodeColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.width(if (block.marker == "•") 18.dp else 30.dp),
                    )
                    InlineText(block.text, color = color, modifier = Modifier.weight(1f))
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MinicodeColors.Accent.copy(alpha = 0.55f)),
                    )
                    InlineText(
                        text = block.text,
                        color = MinicodeColors.Muted,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                }

                MdBlock.Divider -> HorizontalDivider(
                    color = MinicodeColors.Border,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun InlineText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = inlineMarkdown(text),
        color = color,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        modifier = modifier,
    )
}

@Composable
private fun CodeBlock(language: String, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MinicodeColors.Code)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (language.isNotBlank()) {
            Text(
                text = language.lowercase(),
                color = MinicodeColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = code.ifEmpty { " " },
            color = Color(0xFFD7E3EE),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
    }
}

private fun inlineMarkdown(text: String, accent: Color = MinicodeColors.Accent): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        var plainStart = 0

        fun flushPlain(end: Int) {
            if (end > plainStart) append(text.substring(plainStart, end))
        }

        while (index < text.length) {
            val rest = text.substring(index)
            when {
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index + 1) {
                        flushPlain(index)
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0xFF1B2735),
                                color = Color(0xFF9DE2B8),
                                fontSize = 13.5.sp,
                            ),
                        ) {
                            append(" ${text.substring(index + 1, end)} ")
                        }
                        index = end + 1
                        plainStart = index
                    } else {
                        index++
                    }
                }

                rest.startsWith("**") || rest.startsWith("__") -> {
                    val marker = rest.substring(0, 2)
                    val end = text.indexOf(marker, index + 2)
                    if (end > index + 2) {
                        flushPlain(index)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                        plainStart = index
                    } else {
                        index++
                    }
                }

                rest.startsWith("~~") -> {
                    val end = text.indexOf("~~", index + 2)
                    if (end > index + 2) {
                        flushPlain(index)
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                        plainStart = index
                    } else {
                        index++
                    }
                }

                rest.startsWith("![") -> {
                    val labelEnd = text.indexOf(']', index + 2)
                    val urlEnd = if (labelEnd > 0) text.indexOf(')', labelEnd + 1) else -1
                    if (labelEnd > 0 && urlEnd > labelEnd && text.getOrNull(labelEnd + 1) == '(') {
                        flushPlain(index)
                        append(text.substring(index + 2, labelEnd))
                        index = urlEnd + 1
                        plainStart = index
                    } else {
                        index++
                    }
                }

                rest.startsWith("[") -> {
                    val labelEnd = text.indexOf(']', index + 1)
                    val urlEnd = if (labelEnd > 0) text.indexOf(')', labelEnd + 1) else -1
                    if (labelEnd > index && urlEnd > labelEnd && text.getOrNull(labelEnd + 1) == '(') {
                        flushPlain(index)
                        withStyle(
                            SpanStyle(
                                color = accent,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(text.substring(index + 1, labelEnd))
                        }
                        index = urlEnd + 1
                        plainStart = index
                    } else {
                        index++
                    }
                }

                rest.startsWith("*") && !rest.startsWith("**") -> {
                    val end = text.indexOf('*', index + 1)
                    if (end > index + 1 && !text[index + 1].isWhitespace()) {
                        flushPlain(index)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                        plainStart = index
                    } else {
                        index++
                    }
                }

                else -> index++
            }
        }
        flushPlain(text.length)
    }
