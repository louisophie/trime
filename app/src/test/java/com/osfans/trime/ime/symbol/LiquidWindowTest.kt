/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the liquid tap input splitter: literal text stays text, `{Key}`
 * groups become key tokens, `{}` escapes to a literal brace pair.
 */
class LiquidWindowTest :
    BehaviorSpec({
        Given("plain text without braces") {
            Then("it stays a single text segment") {
                LiquidWindow.splitLiquidInput("[]") shouldBe
                    listOf(LiquidSegment.Text("[]"))
            }
        }

        Given("text with a trailing key group") {
            Then("it splits into text plus key") {
                LiquidWindow.splitLiquidInput("[]{Left}") shouldBe
                    listOf(
                        LiquidSegment.Text("[]"),
                        LiquidSegment.Key("Left"),
                    )
            }
        }

        Given("multiple key groups") {
            Then("each becomes a key segment") {
                LiquidWindow.splitLiquidInput("<!--  -->{Left}{Left}") shouldBe
                    listOf(
                        LiquidSegment.Text("<!--  -->"),
                        LiquidSegment.Key("Left"),
                        LiquidSegment.Key("Left"),
                    )
            }
        }

        Given("an empty brace pair") {
            Then("it escapes to a literal brace pair") {
                LiquidWindow.splitLiquidInput("a{}b") shouldBe
                    listOf(
                        LiquidSegment.Text("a"),
                        LiquidSegment.Key("braceleft"),
                        LiquidSegment.Key("braceright"),
                        LiquidSegment.Text("b"),
                    )
            }
        }
    })
