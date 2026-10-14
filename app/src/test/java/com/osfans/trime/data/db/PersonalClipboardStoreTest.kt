/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins personal-clipboard.md parsing: blanks and `#' comments are dropped,
 * `#key: value` directives configure the list, order is preserved, leading
 * spaces and \r are handled, and unknown or invalid directives fall back to
 * defaults.
 */
class PersonalClipboardStoreTest :
    BehaviorSpec({
        Given("a markdown style content") {
            val content =
                "# 個人清單\n\nhello@example.com\n  - indented item\ncd /storage/emulated/0/Documents\n# another header\n\n最後一行"
            Then("comments and blanks are removed, order preserved") {
                PersonalClipboardStore.parse(content).lines shouldBe listOf(
                    "hello@example.com",
                    "  - indented item",
                    "cd /storage/emulated/0/Documents",
                    "最後一行",
                )
            }

            Then("no directives keep the defaults") {
                PersonalClipboardStore.parse(content).config shouldBe PersonalClipboardStore.Config()
            }
        }

        Given("CRLF line endings") {
            Then("they are absorbed") {
                PersonalClipboardStore.parse("a\r\nb\r\nc\r").lines shouldBe listOf("a", "b", "c")
            }
        }

        Given("empty or comment-only content") {
            Then("no items are produced") {
                PersonalClipboardStore.parse("").lines shouldBe emptyList()
                PersonalClipboardStore.parse("\n\n# only headers\n## x").lines shouldBe emptyList()
            }
        }

        Given("style directives") {
            val content = "#font-scale: 0.5\n#spacing: 6\n#padding: 12\nitem"
            Then("they configure the list") {
                PersonalClipboardStore.parse(content).config shouldBe
                    PersonalClipboardStore.Config(fontScale = 0.5f, spacingDp = 6, paddingDp = 12)
            }

            Then("out-of-range values are clamped and unknown ones ignored") {
                PersonalClipboardStore.parse("#font-scale: 99\n#unknown: 1\nitem").config.fontScale shouldBe 2.0f
            }
        }

        Given("a min-squeeze directive") {
            Then("it configures the squeeze floor") {
                PersonalClipboardStore.parse("#min-squeeze: 0.45\nitem").config.minSqueeze shouldBe 0.45f
            }

            Then("absent means the default floor") {
                PersonalClipboardStore.parse("item").config.minSqueeze shouldBe
                    PersonalClipboardStore.DEFAULT_MIN_SQUEEZE
            }

            Then("out-of-range is clamped") {
                PersonalClipboardStore.parse("#min-squeeze: 2\nitem").config.minSqueeze shouldBe 1.0f
                PersonalClipboardStore.parse("#min-squeeze: 0\nitem").config.minSqueeze shouldBe 0.1f
            }
        }

        Given("a title directive") {
            Then("it names the panel") {
                PersonalClipboardStore.parse("#title: 看板\nitem").config.title shouldBe "看板"
            }

            Then("spaces inside the value are kept") {
                PersonalClipboardStore.parse("#title: my notes\nitem").config.title shouldBe "my notes"
            }

            Then("a blank value falls back to the file name") {
                PersonalClipboardStore.parse("#title:   \nitem").config.title shouldBe
                    PersonalClipboardStore.DEFAULT_FILE_NAME
            }

            Then("absent means the default file name") {
                PersonalClipboardStore.parse("item").config.title shouldBe PersonalClipboardStore.DEFAULT_FILE_NAME
            }
        }

        Given("slide-paste grid directives") {
            Then("they configure the grid, not the chips") {
                val config = PersonalClipboardStore.parse(
                    "#grid-rows: 6\n#grid-font-scale: 0.8\n#grid-long-scale: 0.5\n#grid-min-font: 13\n#grid-min-width: 48\nitem",
                ).config
                config.grid shouldBe PersonalClipboardStore.Grid(6f, 0.8f, 0.5f, 13, 48)
                config.fontScale shouldBe PersonalClipboardStore.DEFAULT_FONT_SCALE
            }

            Then("absent means the Multiling defaults") {
                PersonalClipboardStore.parse("item").config.grid shouldBe PersonalClipboardStore.Grid()
            }

            Then("out-of-range values are clamped, garbage ignored") {
                val grid = PersonalClipboardStore.parse(
                    "#grid-rows: 99\n#grid-long-scale: 0\n#grid-min-font: x\n#grid-min-width: 5\nitem",
                ).config.grid
                grid.rows shouldBe 12f
                grid.longScale shouldBe 0.2f
                grid.minFontDp shouldBe PersonalClipboardStore.DEFAULT_GRID_MIN_FONT_DP
                grid.minWidthDp shouldBe 16
            }
        }
    })
