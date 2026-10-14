/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.db.PersonalClipboardStore
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.clipboard.SlidePasteLayout.Zone
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import splitties.dimensions.dp
import kotlin.math.ceil

/**
 * Slide-paste grid drawn over the keyboard. As in Multiling, items are bare
 * strings on the candidate background, flowing side by side
 * ([SlidePasteLayout]) with thin dividers: short ones at the full text size,
 * long ones small and wrapped. The one under the finger is highlighted and
 * echoed, full length, in a preview bubble away from the finger.
 *
 * It opens while a finger drags from the personal-clipboard tool button,
 * which feeds the finger in through [track] and [release]. Lifting on an item
 * pastes it and closes the grid; lifting anywhere else leaves the grid open,
 * and it then takes taps and slides itself until an item is picked or ✕ is
 * hit. A strip on the right holds ✕ (close), ◀ and ▶ (pages; resting on one
 * keeps flipping).
 */
@SuppressLint("ViewConstructor")
class SlidePasteView(context: Context, private val scope: ThemeScope) : View(context) {

    /** Called with the item the finger was lifted on. */
    var onPick: ((String) -> Unit)? = null

    private var lines: List<String> = emptyList()
    private var config = PersonalClipboardStore.Grid()
    private var grid: SlidePasteLayout.Grid? = null
    private var page = 0
    private var highlighted = -1
    private var zone = Zone.NONE
    private var flippedInZone = false

    val isOpen: Boolean get() = visibility == VISIBLE

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val previewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val textLayouts = HashMap<Int, StaticLayout>()

    private val divider = dp(1).toFloat().coerceAtLeast(1f)
    private val minTextSize get() = dp(config.minFontDp).toFloat()
    private val corner get() = dp(scope.theme.generalStyle.roundCorner)
    private val minCell get() = dp(config.minWidthDp).toFloat()
    private val stripWidth = dp(STRIP_DP).toFloat()

    private val pageCount get() = grid?.pages?.size ?: 0

    private val flipPage = object : Runnable {
        override fun run() {
            if (!flip(zone)) return
            flippedInZone = true
            postDelayed(this, PAGE_REPEAT_MS)
        }
    }

    init {
        // INVISIBLE, not GONE: the grid keeps its size, so it can lay out
        // before it is first shown and the first move already hits a cell.
        visibility = INVISIBLE
    }

    /**
     * Shows [items] from their first page, sized by the file's `#grid-…:`
     * directives ([gridConfig]); nothing is highlighted yet.
     */
    fun show(items: List<String>, gridConfig: PersonalClipboardStore.Grid) {
        lines = items
        config = gridConfig
        grid = null
        textLayouts.clear()
        page = 0
        highlighted = -1
        setZone(Zone.NONE)
        visibility = VISIBLE
        invalidate()
    }

    fun close() {
        setZone(Zone.NONE)
        highlighted = -1
        visibility = INVISIBLE
    }

    /** Moves the finger to ([x], [y]) in this view's coordinates. */
    fun track(x: Float, y: Float) {
        val current = ensureGrid() ?: return
        val inside = x >= 0f && y >= 0f && x < width && y < height
        val inStrip = inside && x >= width - stripWidth
        setZone(if (inStrip) SlidePasteLayout.stripZone(y, height.toFloat(), pageCount > 1) else Zone.NONE)
        val item = if (inside && !inStrip) {
            current.pages.getOrNull(page)?.firstOrNull { it.contains(x, y) }?.item ?: -1
        } else {
            -1
        }
        if (item != highlighted) {
            highlighted = item
            if (item >= 0) InputFeedbackManager.keyPressVibrate(this)
            invalidate()
        }
    }

    /**
     * Lifts the finger: pastes and closes on an item, closes on ✕, turns the
     * page on ◀/▶ unless resting there already did; otherwise stays open.
     */
    fun release() {
        val picked = lines.getOrNull(highlighted)
        val lastZone = zone
        val flipped = flippedInZone
        setZone(Zone.NONE)
        highlighted = -1
        when {
            picked != null -> {
                close()
                onPick?.invoke(picked)
            }

            lastZone == Zone.CLOSE -> close()

            !flipped -> flip(lastZone)
        }
        invalidate()
    }

    private fun flip(direction: Zone): Boolean {
        val count = pageCount
        if (count < 2) return false
        page = when (direction) {
            Zone.NEXT -> (page + 1) % count
            Zone.PREV -> (page - 1 + count) % count
            else -> return false
        }
        highlighted = -1
        InputFeedbackManager.keyPressVibrate(this)
        invalidate()
        return true
    }

    private fun setZone(entered: Zone) {
        if (entered == zone) return
        zone = entered
        flippedInZone = false
        removeCallbacks(flipPage)
        if (entered == Zone.PREV || entered == Zone.NEXT) postDelayed(flipPage, PAGE_DELAY_MS)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        grid = null
        textLayouts.clear()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Keyboard hidden: do not come back with a stale grid.
        if (visibility != VISIBLE) close()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(flipPage)
        super.onDetachedFromWindow()
    }

    /** Once left open, the grid takes taps and slides itself. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> track(event.x, event.y)

            MotionEvent.ACTION_UP -> release()

            MotionEvent.ACTION_CANCEL -> {
                setZone(Zone.NONE)
                highlighted = -1
                invalidate()
            }
        }
        return true
    }

    private fun ensureGrid(): SlidePasteLayout.Grid? {
        grid?.let { return it }
        if (width <= 0 || height <= 0) return null
        textPaint.typeface = FontManager.getTypeface("key_font")
        val maxRow = height / config.rows
        val minRow = dp(MIN_ROW_DP).toFloat().coerceAtMost(maxRow)
        // The control strip always takes the right edge.
        val result = SlidePasteLayout.layout(
            lines.size,
            width - stripWidth,
            height.toFloat(),
            maxRow,
            minRow,
            minCell,
            config.fontScale,
        ) { item, size -> measure(lines[item], size) }
        grid = result
        page = page.coerceIn(0, (result.pages.size - 1).coerceAtLeast(0))
        return result
    }

    private fun measure(text: String, size: Float): Float {
        textPaint.textSize = size
        return textPaint.measureText(text)
    }

    override fun onDraw(canvas: Canvas) {
        val colors = scope.colors
        // The candidate background may be translucent; lay it over the keyboard's.
        canvas.drawColor(colors.keyboardBackColor)
        canvas.drawColor(colors.backColor)
        val current = ensureGrid() ?: return
        drawStrip(canvas, current)
        if (lines.isEmpty()) {
            rect.set(0f, 0f, width - stripWidth, height.toFloat())
            val text = context.getString(R.string.slide_paste_empty)
            drawText(canvas, textLayout(-1, text, dp(EMPTY_TEXT_DP).toFloat(), false), colors.candidateTextColor)
            return
        }
        val cells = current.pages.getOrNull(page).orEmpty()
        var highlightedCell: SlidePasteLayout.Cell? = null
        cellPaint.color = colors.candidateSeparatorColor
        cellPaint.strokeWidth = divider
        for (cell in cells) {
            // Dividers only: strings sit on the bar's background, not on keys.
            if (cell.left > 0f) {
                val inset = (cell.bottom - cell.top) * DIVIDER_INSET
                canvas.drawLine(cell.left, cell.top + inset, cell.left, cell.bottom - inset, cellPaint)
            }
            if (cell.top > 0f) canvas.drawLine(cell.left, cell.top, cell.right, cell.top, cellPaint)
        }
        for (cell in cells) {
            val hit = cell.item == highlighted
            rect.set(cell.left, cell.top, cell.right, cell.bottom)
            if (hit) {
                highlightedCell = cell
                cellPaint.color = colors.hilitedCandidateBackColor
                canvas.drawRoundRect(rect, corner, corner, cellPaint)
            }
            val layout = textLayout(cell.item, lines[cell.item], current.textSize, cell.shrink)
            drawText(canvas, layout, if (hit) colors.hilitedCandidateTextColor else colors.candidateTextColor)
        }
        highlightedCell?.let { drawPreview(canvas, lines[it.item], current.textSize, it) }
    }

    /**
     * Text of [item] for a cell the size of [rect]: a short item at [size],
     * a [long] one small and wrapped ([SlidePasteLayout.fitText]). Cached per
     * item until the grid is laid out again.
     */
    private fun textLayout(item: Int, text: String, size: Float, long: Boolean): StaticLayout {
        if (item >= 0) textLayouts[item]?.let { return it }
        val base = if (long) SlidePasteLayout.longTextSize(size, config.longScale) else size
        val width = (rect.width() - base).coerceAtLeast(1f)
        val height = rect.height() - divider * 2
        val paint = TextPaint(textPaint).apply {
            textAlign = Paint.Align.LEFT
            textSize = base
        }
        val fit = SlidePasteLayout.fitText(paint.measureText(text), base, width, height, minTextSize, wrapFirst = long)
        paint.textSize = fit.size
        // Round up: a short item's cell is exactly its text wide.
        val layout = staticLayout(text, paint, ceil(width).toInt() + 1, fit.lines)
        if (item >= 0) textLayouts[item] = layout
        return layout
    }

    /** Draws [layout] centered in [rect], clipped to it. */
    private fun drawText(canvas: Canvas, layout: StaticLayout, color: Int) {
        layout.paint.color = color
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.centerX() - layout.width / 2f, rect.centerY() - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** The right-hand strip: ✕, then ◀ and ▶ with the page number when paged. */
    private fun drawStrip(canvas: Canvas, current: SlidePasteLayout.Grid) {
        val colors = scope.colors
        val left = width - stripWidth
        val third = height / 3f
        val paged = current.pages.size > 1
        cellPaint.color = colors.candidateSeparatorColor
        cellPaint.strokeWidth = divider
        canvas.drawLine(left, 0f, left, height.toFloat(), cellPaint)
        val size = current.textSize.coerceAtMost(stripWidth * 0.6f)
        val zones = if (paged) listOf(Zone.CLOSE, Zone.PREV, Zone.NEXT) else listOf(Zone.CLOSE)
        zones.forEachIndexed { index, z ->
            rect.set(left, third * index, width.toFloat(), third * (index + 1))
            val active = z == zone
            if (active) {
                cellPaint.color = colors.hilitedCandidateBackColor
                canvas.drawRoundRect(rect, corner, corner, cellPaint)
            }
            textPaint.color = if (active) colors.hilitedCandidateTextColor else colors.candidateTextColor
            textPaint.textSize = size
            val glyph = when (z) {
                Zone.CLOSE -> "✕"
                Zone.PREV -> "◀"
                else -> "▶"
            }
            canvas.drawText(glyph, rect.centerX(), rect.centerY() + size / 3f, textPaint)
        }
        if (paged) {
            textPaint.color = colors.candidateTextColor
            textPaint.textSize = size * 0.6f
            canvas.drawText("${page + 1}/${current.pages.size}", left + stripWidth / 2f, height - size * 0.4f, textPaint)
        }
    }

    /**
     * Full text of the item under the finger, in a bubble at the top of the
     * grid, or at the bottom when the finger is in the top half, so it is
     * never hidden under the finger.
     */
    private fun drawPreview(canvas: Canvas, text: String, size: Float, cell: SlidePasteLayout.Cell) {
        val colors = scope.colors
        val margin = dp(6).toFloat()
        val bubbleWidth = (width - stripWidth - margin * 2).toInt().coerceAtLeast(1)
        previewPaint.typeface = textPaint.typeface
        previewPaint.textSize = size * PREVIEW_SCALE
        previewPaint.color = colors.popupTextColor
        val padding = previewPaint.textSize / 2f
        val textWidth = (bubbleWidth - padding * 2).toInt().coerceAtLeast(1)
        val layout = staticLayout(text, previewPaint, textWidth, PREVIEW_MAX_LINES)
        val bubbleHeight = layout.height + padding * 2
        val top = if (cell.centerY() < height / 2f) height - margin - bubbleHeight else margin
        rect.set(margin, top, margin + bubbleWidth, top + bubbleHeight)
        cellPaint.color = colors.popupBackColor
        canvas.drawRoundRect(rect, corner + padding / 2f, corner + padding / 2f, cellPaint)
        canvas.save()
        canvas.translate(margin + padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Centered text on up to [maxLines] lines, the last one ellipsized. */
    private fun staticLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        val w = width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            // No line limit before M: overflowing lines are clipped instead.
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }
    }

    private fun SlidePasteLayout.Cell.centerY() = (top + bottom) / 2f

    companion object {
        /** Rows never get shorter than this, unless `#grid-rows:` asks for more. */
        private const val MIN_ROW_DP = 34
        private const val STRIP_DP = 40
        private const val PAGE_DELAY_MS = 450L
        private const val PAGE_REPEAT_MS = 900L

        /** Vertical dividers stop short of the row edges by this share of its height. */
        private const val DIVIDER_INSET = 0.2f
        private const val PREVIEW_SCALE = 1.3f
        private const val PREVIEW_MAX_LINES = 4
        private const val EMPTY_TEXT_DP = 16
    }
}
