/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import com.osfans.trime.data.base.DataManager
import java.io.File

/**
 * File-backed personal clipboard: one paste string per line in a user-editable
 * Markdown file under the Rime user dir (default `files/rime/personal-clipboard.md`).
 *
 * Blank lines and `#'-prefixed lines are ignored; comment lines carrying a
 * `#key: value` pair configure the list (panel title, font scale, chip
 * spacing, padding). Content re-loads whenever the file's mtime changes —
 * external edits take effect on next window open, without redeploying.
 */
object PersonalClipboardStore {
    const val DEFAULT_FILE_NAME = "personal-clipboard.md"

    /** Look/feel knobs of the chips list; all have defaults. */
    data class Config(
        val title: String = DEFAULT_FILE_NAME,
        val fontScale: Float = DEFAULT_FONT_SCALE,
        val spacingDp: Int = DEFAULT_SPACING_DP,
        val paddingDp: Int = DEFAULT_PADDING_DP,
        val minSqueeze: Float = DEFAULT_MIN_SQUEEZE,
        val grid: Grid = Grid(),
    )

    /**
     * Slide-paste grid knobs (`#grid-…:` directives): rows that fill the
     * keyboard at most, text size relative to the row, long items' text
     * relative to the rest, the smallest text, and the narrowest item.
     */
    data class Grid(
        val rows: Float = DEFAULT_GRID_ROWS,
        val fontScale: Float = DEFAULT_GRID_FONT_SCALE,
        val longScale: Float = DEFAULT_GRID_LONG_SCALE,
        val minFontDp: Int = DEFAULT_GRID_MIN_FONT_DP,
        val minWidthDp: Int = DEFAULT_GRID_MIN_WIDTH_DP,
    )

    /** Parsed file: paste lines in file order plus the resolved config. */
    data class Data(val lines: List<String>, val config: Config)

    private data class Cached(val path: String, val lastModified: Long, val data: Data)

    private var cache: Cached? = null

    private val DIRECTIVE = Regex("""^#\s*([a-z\-]+)\s*:\s*(\S+)""")

    /**
     * `#title:` carries free text (spaces allowed), so it gets its own regex
     * instead of reusing [DIRECTIVE], whose value stops at the first space.
     */
    private val TITLE_DIRECTIVE = Regex("""^#\s*title\s*:\s*(.+)$""")

    /** Pure parsing, separated for tests: `#'-directives configure, the rest are items. */
    fun parse(content: String): Data {
        var config = Config()
        val lines = mutableListOf<String>()
        content
            .replace("\r\n", "\n").replace("\r", "\n")
            .lines()
            .forEach { line ->
                val trimmed = line.trimStart()
                when {
                    !trimmed.startsWith("#") -> {
                        if (line.isEmpty()) return@forEach
                        lines.add(line)
                    }

                    else ->
                        TITLE_DIRECTIVE.find(line)?.let { m ->
                            config =
                                config.copy(
                                    title = m.groupValues[1].trim().ifEmpty { DEFAULT_FILE_NAME },
                                )
                        } ?: DIRECTIVE.find(line)?.let { m ->
                            val (key, rawValue) = m.destructured
                            config = when (key) {
                                "font-scale" -> rawValue.toFloatOrNull()?.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                                    ?.let { config.copy(fontScale = it) } ?: config

                                "spacing" -> rawValue.toIntOrNull()?.coerceIn(0, 40)
                                    ?.let { config.copy(spacingDp = it) } ?: config

                                "padding" -> rawValue.toIntOrNull()?.coerceIn(0, 40)
                                    ?.let { config.copy(paddingDp = it) } ?: config

                                "min-squeeze" -> rawValue.toFloatOrNull()?.coerceIn(MIN_SQUEEZE_FLOOR, MAX_SQUEEZE_FLOOR)
                                    ?.let { config.copy(minSqueeze = it) } ?: config

                                "grid-rows" -> rawValue.toFloatOrNull()?.coerceIn(1f, 12f)
                                    ?.let { config.copy(grid = config.grid.copy(rows = it)) } ?: config

                                "grid-font-scale" -> rawValue.toFloatOrNull()?.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                                    ?.let { config.copy(grid = config.grid.copy(fontScale = it)) } ?: config

                                "grid-long-scale" -> rawValue.toFloatOrNull()?.coerceIn(0.2f, 1f)
                                    ?.let { config.copy(grid = config.grid.copy(longScale = it)) } ?: config

                                "grid-min-font" -> rawValue.toIntOrNull()?.coerceIn(6, 40)
                                    ?.let { config.copy(grid = config.grid.copy(minFontDp = it)) } ?: config

                                "grid-min-width" -> rawValue.toIntOrNull()?.coerceIn(16, 200)
                                    ?.let { config.copy(grid = config.grid.copy(minWidthDp = it)) } ?: config

                                else -> config
                            }
                        }
                }
            }
        return Data(lines, config)
    }

    @Synchronized
    fun load(fileName: String = DEFAULT_FILE_NAME): Data {
        if (fileName.isEmpty()) return Data(emptyList(), Config())
        val file = File(DataManager.userDataDir, fileName)
        if (!file.isFile) {
            cache = null
            return Data(emptyList(), Config())
        }
        val lastModified = file.lastModified()
        cache?.takeIf { it.path == file.absolutePath && it.lastModified == lastModified }
            ?.let { return it.data }
        val data = file.readText().let(::parse)
        cache = Cached(file.absolutePath, lastModified, data)
        return data
    }

    const val DEFAULT_FONT_SCALE = 0.75f
    const val DEFAULT_SPACING_DP = 4
    const val DEFAULT_PADDING_DP = 8
    const val DEFAULT_MIN_SQUEEZE = 0.6f
    const val DEFAULT_GRID_ROWS = 5f
    const val DEFAULT_GRID_FONT_SCALE = 1f
    const val DEFAULT_GRID_LONG_SCALE = 0.55f
    const val DEFAULT_GRID_MIN_FONT_DP = 11
    const val DEFAULT_GRID_MIN_WIDTH_DP = 40
    private const val MIN_FONT_SCALE = 0.3f
    private const val MAX_FONT_SCALE = 2.0f
    private const val MIN_SQUEEZE_FLOOR = 0.1f
    private const val MAX_SQUEEZE_FLOOR = 1.0f
}
