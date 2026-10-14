/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.SymbolHistory
import com.osfans.trime.data.db.QuickPasteStore
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.LiquidData
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import org.kodein.di.DI
import org.kodein.di.instance

/** A piece of liquid tap input: literal [Text] or a braced key [Key]. */
sealed interface LiquidSegment {
    data class Text(val text: String) : LiquidSegment

    data class Key(val token: String) : LiquidSegment
}

class LiquidWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    ResidentWindow {
    override val showTitle = false

    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()

    private lateinit var liquidLayout: LiquidLayout
    private val symbolHistory = SymbolHistory(180)
    var currentDataType: LiquidData.Type = LiquidData.Type.SINGLE
        private set
    private var currentStay = false
    private var currentDirect = false

    private val adapter by lazy {
        LiquidAdapter(
            scope,
            onItemClick = { _ ->
                when (currentDataType) {
                    LiquidData.Type.SYMBOL -> triggerSymbolInput(this.altText.ifEmpty { this.text })

                    LiquidData.Type.TABS -> {
                        val realPosition = LiquidData.getTagList()
                            .indexOfFirst { it.label == this.text }
                        setDataByIndex(realPosition)
                    }

                    else -> {
                        service.commitText(this.text)
                        if (currentDataType != LiquidData.Type.HISTORY && currentDataType != LiquidData.Type.QUICKPASTE) {
                            symbolHistory.insert(this.text)
                            symbolHistory.save()
                        }
                    }
                }
            },
            onItemLongClick = { item, _ ->
                if (currentDataType == LiquidData.Type.QUICKPASTE) {
                    showQuickPasteEditDialog(item)
                }
            },
        )
    }

    private val mainLayoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
    }

    companion object : ResidentWindow.Key {
        // NOTE: Android's ICU regex rejects a lone '}' (unlike desktop JDK),
        // so every literal brace here is escaped, mirroring TEXT_INPUT_PATTERN.
        private val LIQUID_TOKEN_PATTERN = """^((?:\{Escape\})?[^{}]+|\{[^{}]+\}).*$""".toRegex()

        /**
         * Splits liquid tap input into literal text and `{Key}` groups, mirroring
         * the keyboard text engine (`{}` escapes to a literal brace pair).
         */
        fun splitLiquidInput(input: String): List<LiquidSegment> {
            val escaped = input.replace("{}", "{braceleft}{braceright}")
            val segments = mutableListOf<LiquidSegment>()
            var i = 0
            while (i < escaped.length) {
                val value =
                    LIQUID_TOKEN_PATTERN.matchEntire(escaped.substring(i))?.groupValues?.get(1)
                        ?: escaped[i].toString()
                if (value.startsWith('{') && value.endsWith('}')) {
                    segments.add(LiquidSegment.Key(value.removeSurrounding("{", "}")))
                } else {
                    segments.add(LiquidSegment.Text(value))
                }
                i += value.length
            }
            return segments
        }
    }

    override val key: ResidentWindow.Key
        get() = LiquidWindow

    override fun onCreateView(): View = LiquidLayout(context, scope, commonKeyboardActionListener).apply {
        liquidLayout = this
        tabsUi.apply {
            setTags(LiquidData.getTagList())
            setOnTabClickListener { i ->
                setDataByIndex(i)
            }
        }
        recyclerView.apply {
            layoutManager = mainLayoutManager
            this.adapter = this@LiquidWindow.adapter
        }
    }

    override fun onCreateBarView() = liquidLayout.tabsUi.root

    override fun onAttached() {}

    override fun onDetached() {}

    override fun refreshColors() {
        if (::liquidLayout.isInitialized) liquidLayout.refreshColors()
    }

    fun setDataByIndex(i: Int) {
        val tag = LiquidData.getTagList()[i]
        currentDataType = tag.type
        currentStay = tag.stay
        currentDirect = tag.direct
        liquidLayout.tabsUi.activateTab(i)
        when (tag.type) {
            LiquidData.Type.HISTORY -> {
                symbolHistory.load()
                submitData(symbolHistory.toOrderedList().map { LiquidKeyboard.KeyItem(it) })
            }

            LiquidData.Type.QUICKPASTE -> {
                val items = QuickPasteStore.getAll()
                submitData(items.map { LiquidKeyboard.KeyItem(it.text, "ID:${it.id} ${if (it.pinned) "📌" else ""}") })
            }

            else -> {
                val data = LiquidData.getDataByIndex(i)
                submitData(data)
            }
        }
    }

    private fun submitData(data: List<LiquidKeyboard.KeyItem>) {
        adapter.submitList(data)
    }

    private fun triggerSymbolInput(symbol: String) {
        // Items without a label fall back to their display text.
        val input = symbol.ifEmpty { return }
        rime.launchOnReady {
            val (isAsciiMode, isAsciiPunch) = it.statusCached.run { isAsciiMode to isAsciiPunct }
            if (isAsciiMode) it.setRuntimeOption("ascii_mode", false)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punch", false)
            it.clearComposition()
            if (currentDirect || ('{' in input && '}' in input)) {
                // Direct-commit tabs, or mixed text and key events
                // (e.g. "[]{Left}"): commit the literal parts directly and
                // dispatch the braced groups as key actions. Other strings
                // keep the legacy simulate path below, which Rime-dependent
                // tabs (e.g. symbollist codes like '/fh') rely on.
                for (segment in splitLiquidInput(input)) {
                    when (segment) {
                        is LiquidSegment.Text -> service.commitText(segment.text)

                        is LiquidSegment.Key ->
                            commonKeyboardActionListener.listener.onAction(
                                KeyActionManager.getAction(segment.token),
                            )
                    }
                }
            } else {
                it.simulateKeySequence(input)
            }
            if (isAsciiMode) it.setRuntimeOption("ascii_mode", true)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punch", true)
            if (!currentStay) {
                ContextCompat.getMainExecutor(service).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
        }
    }

    private fun showQuickPasteEditDialog(item: LiquidKeyboard.KeyItem) {
        val idStr = item.altText.substringAfter("ID:").substringBefore(" ").toIntOrNull() ?: return
        val pasteItem = QuickPasteStore.get(idStr) ?: return

        val input = EditText(context).apply {
            setText(pasteItem.text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.quick_paste_edit_title)
            .setView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(input)
                },
            )
            .setPositiveButton(R.string.save) { _, _ ->
                val newText = input.text.toString()
                if (newText.isNotBlank()) {
                    QuickPasteStore.update(idStr, newText)
                    setDataByIndex(LiquidData.getTagList().indexOfFirst { it.type == LiquidData.Type.QUICKPASTE })
                }
            }
            .setNeutralButton(
                if (pasteItem.pinned) R.string.unpin else R.string.pin,
            ) { _, _ ->
                QuickPasteStore.togglePinned(idStr)
                setDataByIndex(LiquidData.getTagList().indexOfFirst { it.type == LiquidData.Type.QUICKPASTE })
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                QuickPasteStore.delete(idStr)
                setDataByIndex(LiquidData.getTagList().indexOfFirst { it.type == LiquidData.Type.QUICKPASTE })
            }
            .create()
            .also { service.showDialog(it) }
    }
}
