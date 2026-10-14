/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import com.osfans.trime.data.db.PersonalClipboardStore
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/**
 * Slide paste, after Multiling O Keyboard's suggestion bar: hold a tool
 * button bound to `personal_clipboard`, or drag out of it, and the lines of
 * `personal-clipboard.md` spread over the keyboard as a [SlidePasteView]
 * grid; lifting the finger on a line commits it, lifting anywhere else
 * leaves the grid open for taps until ✕ or another tap on the button. A
 * plain tap with the grid closed still opens the [PersonalClipboardWindow].
 */
class SlidePasteDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {

    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()

    val view by lazy {
        SlidePasteView(context, scope).apply { onPick = { service.commitText(it) } }
    }

    private val location = IntArray(2)

    /** True when the preset key named [action] opens the personal clipboard. */
    fun isTrigger(action: String): Boolean = action.isNotEmpty() && KeyActionManager.getAction(action).command == TRIGGER_COMMAND

    /**
     * Gives [button] the slide gesture. [onTap] runs for a plain tap only,
     * never at the end of a slide.
     */
    fun attach(button: GestureFrame, onTap: () -> Unit) {
        var sliding = false
        var wasOpen = false
        var lastX = 0f
        var lastY = 0f
        fun open() {
            if (sliding) return
            sliding = true
            // A grid left open keeps its page when slid into again.
            if (!view.isOpen) {
                val data = PersonalClipboardStore.load()
                view.show(data.lines, data.config.grid)
            }
            InputFeedbackManager.keyPressVibrate(button, true)
        }
        val openOnHold = Runnable { open() }
        button.onPress = {
            sliding = false
            wasOpen = view.isOpen
            if (!wasOpen) button.postDelayed(openOnHold, holdTimeout.toLong())
        }
        button.onMove = { x, y, _ ->
            lastX = x
            lastY = y
            if (x < 0f || y < 0f || x >= button.width || y >= button.height) open()
            if (sliding) track(button, x, y)
        }
        button.onRelease = { _, _ ->
            button.removeCallbacks(openOnHold)
            if (sliding) {
                track(button, lastX, lastY)
                view.release()
            }
        }
        button.onCancel = {
            button.removeCallbacks(openOnHold)
            if (sliding) view.close()
            sliding = false
        }
        button.setOnClickListener {
            when {
                sliding -> sliding = false
                wasOpen -> view.close()
                else -> onTap()
            }
        }
    }

    /** Re-expresses a point in [button] coordinates in the grid's own. */
    private fun track(button: GestureFrame, x: Float, y: Float) {
        button.getLocationOnScreen(location)
        val screenX = location[0] + x
        val screenY = location[1] + y
        view.getLocationOnScreen(location)
        view.track(screenX - location[0], screenY - location[1])
    }

    override fun onStartInput(info: EditorInfo) = view.close()

    /** Another board (liquid keyboard, clipboard...) replaces the grid. */
    override fun onWindowAttached(window: BoardWindow) = view.close()

    /** Same hold as a key's long press. */
    private val holdTimeout by AppPrefs.defaultInstance().keyboard.longPressTimeout

    companion object {
        const val TRIGGER_COMMAND = "personal_clipboard"
    }
}
