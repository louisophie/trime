/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.util.yaml.Node
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins liquid_keyboard tab decoding: tab order follows the `keyboards:`
 * list, and the `stay:` flag (whether tapping an item keeps the liquid
 * window open) defaults to false.
 */
class LiquidKeyboardTest :
    BehaviorSpec({
        fun tabNode(
            type: String,
            name: String,
            stay: String? = null,
            direct: String? = null,
        ): Node.Mapping {
            val pairs = mutableMapOf<Node, Node>(
                Node.Scalar("type") to Node.Scalar(type),
                Node.Scalar("name") to Node.Scalar(name),
                Node.Scalar("keys") to Node.Sequence(Node.Scalar("a")),
            )
            if (stay != null) pairs[Node.Scalar("stay")] = Node.Scalar(stay)
            if (direct != null) pairs[Node.Scalar("direct")] = Node.Scalar(direct)
            return Node.Mapping(pairs)
        }

        Given("a liquid_keyboard section with a staying-direct tab and a default tab") {
            val node =
                Node.Mapping(
                    Node.Scalar("keyboards") to
                        Node.Sequence(Node.Scalar("personal"), Node.Scalar("emoji")),
                    Node.Scalar("personal") to tabNode("SYMBOL", "個人", stay = "true", direct = "true"),
                    Node.Scalar("emoji") to tabNode("SINGLE", "emoji"),
                )
            val decoded = LiquidKeyboard.decode(node)

            Then("order follows the keyboards list") {
                decoded.keyboards.map { it.id } shouldBe listOf("personal", "emoji")
            }

            Then("stay and direct are decoded when present and false by default") {
                decoded.keyboards[0].stay shouldBe true
                decoded.keyboards[0].direct shouldBe true
                decoded.keyboards[1].stay shouldBe false
                decoded.keyboards[1].direct shouldBe false
            }
        }
    })
