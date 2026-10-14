/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins schema prev/next wrap-around used by the space-bar swipe commands.
 */
class SchemaSwitchTest :
    BehaviorSpec({
        val ids = listOf("luna_pinyin", "t1d", "cangjie5")

        Given("a forward step") {
            Then("it moves to the next schema") {
                nextSchemaId("luna_pinyin", ids, 1) shouldBe "t1d"
            }

            Then("it wraps from the last to the first") {
                nextSchemaId("cangjie5", ids, 1) shouldBe "luna_pinyin"
            }
        }

        Given("a backward step") {
            Then("it moves to the previous schema") {
                nextSchemaId("t1d", ids, -1) shouldBe "luna_pinyin"
            }

            Then("it wraps from the first to the last") {
                nextSchemaId("luna_pinyin", ids, -1) shouldBe "cangjie5"
            }
        }

        Given("edge cases") {
            Then("an empty list yields null") {
                nextSchemaId("t1d", emptyList(), 1) shouldBe null
            }

            Then("an unknown current starts at an end") {
                nextSchemaId("missing", ids, 1) shouldBe "luna_pinyin"
                nextSchemaId("missing", ids, -1) shouldBe "cangjie5"
            }

            Then("a single schema stays put") {
                nextSchemaId("t1d", listOf("t1d"), 1) shouldBe "t1d"
                nextSchemaId("t1d", listOf("t1d"), -1) shouldBe "t1d"
            }
        }
    })
