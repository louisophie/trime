/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the personal-clipboard chip render mode: fitting lines stay natural,
 * moderately long lines squeeze horizontally down to the floor, anything
 * longer wraps onto multiple lines instead of becoming unreadably thin.
 */
class PersonalClipboardWindowTest :
    BehaviorSpec({
        Given("chip render selection") {
            Then("fitting text renders normally") {
                PersonalClipboardWindow.chipRenderMode(100f, 500f, 0.6f) shouldBe
                    PersonalClipboardWindow.RENDER_NORMAL
            }

            Then("mild overflow squeezes down to the floor") {
                PersonalClipboardWindow.chipRenderMode(500f, 400f, 0.6f) shouldBe
                    PersonalClipboardWindow.RENDER_SQUEEZE
                PersonalClipboardWindow.chipRenderMode(500f, 300f, 0.6f) shouldBe
                    PersonalClipboardWindow.RENDER_SQUEEZE
            }

            Then("beyond the floor it wraps instead of over-squeezing") {
                PersonalClipboardWindow.chipRenderMode(500f, 200f, 0.6f) shouldBe
                    PersonalClipboardWindow.RENDER_WRAP
            }

            Then("degenerate inputs stay safe") {
                PersonalClipboardWindow.chipRenderMode(0f, 500f, 0.6f) shouldBe
                    PersonalClipboardWindow.RENDER_NORMAL
                PersonalClipboardWindow.chipRenderMode(500f, 0f, 0.6f) shouldBe
                    PersonalClipboardWindow.RENDER_NORMAL
            }
        }
    })
