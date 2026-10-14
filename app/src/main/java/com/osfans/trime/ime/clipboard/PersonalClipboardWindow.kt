/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.data.db.PersonalClipboardStore
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.gravityCenter
import splitties.views.setPaddingDp

/**
 * Standalone personal-clipboard popup: chips from `personal-clipboard.md`,
 * layout and font tuned by the file's own `#key:` directives, so both content
 * and appearance are edited in one Markdown file (no redeploy, no rebuild).
 *
 * A [BoardWindow.BarBoardWindow], so the panel title and its back control are
 * drawn in the quick bar (where the tool_bar sits) rather than inside the
 * panel; the title comes from the file's `#title:` directive. Chips are laid
 * out as a fixed [COLUMNS]-column grid.
 */
class PersonalClipboardWindow(di: DI) : BoardWindow.BarBoardWindow(di) {

    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    private var data = PersonalClipboardStore.Data(emptyList(), PersonalClipboardStore.Config())

    override val title: String
        get() = data.config.title

    private val adapter by lazy {
        ChipAdapter(scope, theme) { line -> service.commitText(line) }
    }

    private val spacingDp: Int
        get() = adapter.config.spacingDp

    override fun onCreateView(): View = contentView()

    private fun contentView(): View = RecyclerView(context).apply {
        layoutManager = FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
        addItemDecoration(SpacesItemDecorationCompat { spacingDp })
        this.adapter = this@PersonalClipboardWindow.adapter
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(theme.generalStyle.keyboardHeight),
        )
        // Chip width needs this view's own width, which is unknown during the
        // first (measure-time) bind; rebind once it is.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val current = width
            val bound = this@PersonalClipboardWindow.adapter.containerWidth
            if (current > 0 && current != bound) {
                this@PersonalClipboardWindow.adapter.containerWidth = current
                post { this@PersonalClipboardWindow.adapter.notifyDataSetChanged() }
            }
        }
    }

    override fun onAttached() {
        // Non-resident window: content is rebuilt on every attach.
        data = PersonalClipboardStore.load()
        adapter.submit(data)
    }

    override fun onDetached() {}

    override fun refreshColors() {}

    /**
     * Chip grid: text-sized buttons in a wrapping flow. Long lines condense
     * horizontally rather than clipping; font/spacing/padding come from the
     * file's own directives.
     */
    private class ChipAdapter(
        private val scope: ThemeScope,
        private val theme: Theme,
        private val onTap: (String) -> Unit,
    ) : RecyclerView.Adapter<ChipAdapter.Holder>() {

        class Holder(val root: GestureFrame, val text: AutoScaleTextView) : RecyclerView.ViewHolder(root)

        var lines: List<String> = emptyList()
        var config = PersonalClipboardStore.Config()
            private set

        /** Chip-list width in px; 0 until the list has been laid out once. */
        var containerWidth: Int = 0

        fun submit(data: PersonalClipboardStore.Data) {
            lines = data.lines
            config = data.config
            notifyDataSetChanged()
        }

        override fun getItemCount() = lines.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val text = AutoScaleTextView(ctx).apply {
                scaleMode = AutoScaleTextView.Mode.Horizontal
                textScaleX = 1.0f
                gravity = gravityCenter
                setTextColor(scope.colors.keyTextColor)
                typeface = FontManager.getTypeface("key_font")
                setPaddingDp(config.paddingDp, config.paddingDp / 2, config.paddingDp, config.paddingDp / 2)
            }
            val root = GestureFrame(ctx).apply {
                isClickable = true
                minimumHeight = dp(30)
                background = scope.decorDrawable(
                    "key_back_color",
                    "key_border_color",
                    dp(theme.generalStyle.keyBorder),
                    dp(theme.generalStyle.roundCorner),
                )
                addView(text)
            }
            return Holder(root, text)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            val total = containerWidth.takeIf { it > 0 }
                ?: (holder.itemView.parent as? android.view.ViewGroup)?.width?.takeIf { it > 0 }
                ?: holder.itemView.context.resources.displayMetrics.widthPixels
            val padding = holder.itemView.context.dp(config.paddingDp)
            val gap = holder.itemView.context.dp(config.spacingDp)
            // Floor, so exactly COLUMNS chips are guaranteed to fit one row.
            val cellWidth = ((total - gap * 2 * COLUMNS) / COLUMNS).coerceAtLeast(padding * 2 + 1)
            holder.itemView.layoutParams = FlexboxLayoutManager.LayoutParams(
                cellWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { maxWidth = cellWidth }
            holder.text.apply {
                textSize = theme.generalStyle.keyTextSize * config.fontScale
                maxWidth = cellWidth
                val naturalWidth = paint.measureText(line)
                val availableWidth = (cellWidth - padding * 2).toFloat()
                when (chipRenderMode(naturalWidth, availableWidth, config.minSqueeze)) {
                    // Wrapped chips also condense horizontally to the floor,
                    // so a long line wraps at the same readability limit.
                    RENDER_WRAP -> {
                        scaleMode = AutoScaleTextView.Mode.Wrap
                        textScaleX = config.minSqueeze
                    }

                    RENDER_SQUEEZE -> {
                        scaleMode = AutoScaleTextView.Mode.Horizontal
                        textScaleX = 1.0f
                    }

                    else -> {
                        scaleMode = AutoScaleTextView.Mode.Horizontal
                        textScaleX = 1.0f
                    }
                }
                this.text = line
            }
            holder.root.setOnClickListener { onTap(line) }
        }
    }

    /** Spacing between chips, driven by the file's '#spacing:' directive. */
    private inner class SpacesItemDecorationCompat(
        private val spaceDp: () -> Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val space = dp(spaceDp()) { /* context-relative dp at layout time */ }
            outRect.set(space, space, space, space)
        }
    }

    private fun dp(value: Int, lazy: () -> Unit = {}): Int = context.dp(value)

    companion object {
        /** Chip columns: every chip is given exactly 1/COLUMNS of the list width. */
        internal const val COLUMNS = 2

        internal const val RENDER_NORMAL = 0
        internal const val RENDER_SQUEEZE = 1
        internal const val RENDER_WRAP = 2

        /**
         * Chip render mode: normal when it fits, horizontally condensed until
         * [minSqueeze], wrapped onto multiple lines beyond it.
         */
        internal fun chipRenderMode(
            textWidth: Float,
            availableWidth: Float,
            minSqueeze: Float,
        ): Int {
            if (textWidth <= 0f || availableWidth <= 0f) return RENDER_NORMAL
            val ratio = availableWidth / textWidth
            return when {
                ratio >= 1f -> RENDER_NORMAL
                ratio >= minSqueeze -> RENDER_SQUEEZE
                else -> RENDER_WRAP
            }
        }
    }
}
