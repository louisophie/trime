/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

/**
 * Pure geometry of the slide-paste grid, ported from Multiling O Keyboard's
 * suggestion bar: items flow side by side and wrap like words, so a row holds
 * as many short items as fit; an item wider than 2/3 of the row is capped at
 * half a row and its text shrunk to fit.
 *
 * Kept free of Android types so the packing rules are unit-testable; text
 * widths come in through a measure function.
 */
internal object SlidePasteLayout {

    /** One laid-out item; [shrink] means its text must be scaled down to fit. */
    data class Cell(
        val item: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val shrink: Boolean,
    ) {
        val width: Float get() = right - left

        fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    /** Every page of the grid at one row height and text size. */
    data class Grid(
        val pages: List<List<Cell>>,
        val rowHeight: Float,
        val textSize: Float,
    )

    /** Text size for a row, as in Multiling 3/7 of the row height, times [scale]. */
    fun textSizeFor(rowHeight: Float, scale: Float = 1f): Float = rowHeight * 3f / 7f * scale

    /**
     * Packs items into justified rows of [totalWidth]: each item takes its text
     * width plus [textSize] of padding, at least [minCell]; one wider than 2/3
     * of the row is capped at half a row ([Cell.shrink]). A row wraps when the
     * next item does not fit, and its leftover space is shared out in
     * proportion to the item widths so the row is filled edge to edge.
     *
     * Returns rows of (item, width, shrink) with tops left to the caller.
     */
    fun packRows(
        count: Int,
        totalWidth: Float,
        textSize: Float,
        minCell: Float,
        measure: (item: Int, textSize: Float) -> Float,
    ): List<List<Triple<Int, Float, Boolean>>> {
        if (count <= 0 || totalWidth <= 0f) return emptyList()
        val rows = mutableListOf<List<Triple<Int, Float, Boolean>>>()
        var row = mutableListOf<Triple<Int, Float, Boolean>>()
        var used = 0f

        fun closeRow() {
            if (row.isEmpty()) return
            val extra = totalWidth - used
            rows.add(
                if (extra > 0f && used > 0f) {
                    row.map { (item, width, shrink) -> Triple(item, width + extra * width / used, shrink) }
                } else {
                    row
                },
            )
            row = mutableListOf()
            used = 0f
        }

        for (item in 0 until count) {
            var width = (measure(item, textSize) + textSize).coerceAtLeast(minCell)
            val shrink = width * 3f > totalWidth * 2f
            if (shrink) width = totalWidth / 2f
            width = width.coerceAtMost(totalWidth)
            if (used + width > totalWidth) closeRow()
            row.add(Triple(item, width, shrink))
            used += width
        }
        closeRow()
        return rows
    }

    /**
     * Lays [count] items out in a [width] x [height] area. Picks the tallest
     * row height, from [maxRowHeight] down to [minRowHeight], at which every
     * row fits on one page; when even the smallest overflows, rows are split
     * into pages of whole rows.
     */
    fun layout(
        count: Int,
        width: Float,
        height: Float,
        maxRowHeight: Float,
        minRowHeight: Float,
        minCell: Float,
        textScale: Float = 1f,
        measure: (item: Int, textSize: Float) -> Float,
    ): Grid {
        val floor = minRowHeight.coerceAtLeast(1f)
        val ceiling = maxRowHeight.coerceAtLeast(floor)
        fun pack(rowHeight: Float) = packRows(count, width, textSizeFor(rowHeight, textScale), minCell, measure)
        fun fits(rowHeight: Float, rows: List<*>) = rows.size * rowHeight <= height

        var rowHeight = ceiling
        var rows = pack(rowHeight)
        if (!fits(rowHeight, rows)) {
            // Taller rows mean bigger text, so never fewer rows: the total
            // height only grows with the row height, and the tallest fitting
            // one can be bisected. Below the floor, page instead.
            var low = floor
            var high = ceiling
            rowHeight = floor
            rows = pack(floor)
            if (fits(floor, rows)) {
                repeat(BISECT_STEPS) {
                    val mid = (low + high) / 2f
                    val midRows = pack(mid)
                    if (fits(mid, midRows)) {
                        low = mid
                        rowHeight = mid
                        rows = midRows
                    } else {
                        high = mid
                    }
                }
            }
        }
        val rowsPerPage = (height / rowHeight).toInt().coerceAtLeast(1)
        val pages = rows.chunked(rowsPerPage).map { pageRows ->
            pageRows.flatMapIndexed { rowIndex, row ->
                val top = rowIndex * rowHeight
                var left = 0f
                row.map { (item, cellWidth, shrink) ->
                    Cell(item, left, top, left + cellWidth, top + rowHeight, shrink).also { left += cellWidth }
                }
            }
        }
        return Grid(pages, rowHeight, textSizeFor(rowHeight, textScale))
    }

    /** How one item's text is set in its cell: at [size], on up to [lines] lines. */
    data class TextFit(val size: Float, val lines: Int)

    /**
     * Sets an item's text: text that fits stays as is. Otherwise, unless
     * [wrapFirst], it shrinks to fit on one line, but never below [minSize]
     * for readability; past that it wraps, at the largest size whose lines
     * all fit in [height]. At [minSize] it gets as many lines as fit there
     * and the rest is cut off (ellipsized by the caller).
     *
     * [wrapFirst] is for long items, which Multiling sets small
     * ([longTextSize]) and wraps rather than shrinking onto one line.
     * [naturalWidth] is the one-line width at [size]; widths scale with size.
     */
    fun fitText(
        naturalWidth: Float,
        size: Float,
        width: Float,
        height: Float,
        minSize: Float,
        wrapFirst: Boolean = false,
    ): TextFit {
        if (naturalWidth <= width || naturalWidth <= 0f || width <= 0f) return TextFit(size, 1)
        val floor = minSize.coerceAtMost(size)
        val oneLine = size * width / naturalWidth
        if (!wrapFirst && oneLine >= floor) return TextFit(oneLine, 1)

        fun maxLines(at: Float) = (height / (at * LINE_HEIGHT)).toInt().coerceAtLeast(1)
        var at = size
        while (at > floor) {
            // Word breaks waste some of each line, hence the slack.
            val needed = kotlin.math.ceil(naturalWidth * at / size * WRAP_SLACK / width).toInt()
            if (needed <= maxLines(at)) return TextFit(at, needed)
            at *= SHRINK_STEP
        }
        return TextFit(floor, maxLines(floor))
    }

    /** Text size of a long ([Cell.shrink]) item: Multiling sets them at 0.55 of the rest. */
    fun longTextSize(textSize: Float, scale: Float = LONG_TEXT_SCALE): Float = textSize * scale

    /** What a point in the right-hand control strip does. */
    enum class Zone { NONE, CLOSE, PREV, NEXT }

    /**
     * The control strip, top to bottom: close, previous page, next page,
     * in equal thirds of [height]; without pages only close is live.
     */
    fun stripZone(y: Float, height: Float, paged: Boolean): Zone {
        if (height <= 0f || y < 0f || y >= height) return Zone.NONE
        return when ((y * 3f / height).toInt()) {
            0 -> Zone.CLOSE
            1 -> if (paged) Zone.PREV else Zone.NONE
            else -> if (paged) Zone.NEXT else Zone.NONE
        }
    }

    /** Line height of wrapped text, in text sizes. */
    const val LINE_HEIGHT = 1.2f
    private const val WRAP_SLACK = 1.1f
    const val LONG_TEXT_SCALE = 0.55f
    private const val SHRINK_STEP = 0.95f

    /** Enough halvings to pin a row height to well under a pixel. */
    private const val BISECT_STEPS = 12
}
