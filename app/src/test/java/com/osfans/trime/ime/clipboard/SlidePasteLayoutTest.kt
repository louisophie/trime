/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pins the Multiling-style slide-paste packing: short items share rows, long
 * ones are capped at half a row and shrunk, rows are justified edge to edge,
 * and row height drops until everything fits on one page before paging.
 */
class SlidePasteLayoutTest :
    BehaviorSpec({
        // Each character is half the text size wide.
        fun measureOf(lengths: List<Int>): (Int, Float) -> Float = { item, size -> lengths[item] * size / 2f }

        Given("row packing") {
            Then("short items share one row, each at least the minimum cell") {
                val rows = SlidePasteLayout.packRows(6, 1000f, 10f, 40f, measure = measureOf(List(6) { 1 }))
                rows shouldHaveSize 1
                rows[0] shouldHaveSize 6
                rows[0].sumOf { it.second.toDouble() } shouldBe (1000.0 plusOrMinus 0.01)
            }

            Then("an item wider than 2/3 of the row is capped at half and shrunk") {
                // 300 chars * 5 + 10 padding = 1510 > 2/3 * 1000
                val rows = SlidePasteLayout.packRows(1, 1000f, 10f, 40f, measure = measureOf(listOf(300)))
                val (_, _, shrink) = rows[0][0]
                shrink shouldBe true
                // Alone on its row, it is then justified to the full width.
                rows[0][0].second.toDouble() shouldBe (1000.0 plusOrMinus 0.01)
            }

            Then("two capped items share a row") {
                val rows = SlidePasteLayout.packRows(2, 1000f, 10f, 40f, measure = measureOf(listOf(300, 300)))
                rows shouldHaveSize 1
                rows[0].map { it.second.toDouble() } shouldBe listOf(500.0, 500.0)
            }

            Then("an item that does not fit wraps, and every row is justified") {
                // 58 chars * 5 + 10 = 300 each: three per row.
                val rows = SlidePasteLayout.packRows(4, 1000f, 10f, 40f, measure = measureOf(List(4) { 58 }))
                rows.map { it.size } shouldBe listOf(3, 1)
                rows.forEach { row -> row.sumOf { it.second.toDouble() } shouldBe (1000.0 plusOrMinus 0.01) }
                rows.flatten().map { it.first } shouldBe listOf(0, 1, 2, 3)
            }

            Then("nothing to pack yields no rows") {
                SlidePasteLayout.packRows(0, 1000f, 10f, 40f, measure = measureOf(emptyList())) shouldHaveSize 0
            }
        }

        Given("text fitting") {
            Then("text that fits keeps its size on one line") {
                SlidePasteLayout.fitText(80f, 20f, 100f, 60f, 12f) shouldBe SlidePasteLayout.TextFit(20f, 1)
            }

            Then("mildly long text shrinks onto one line") {
                val fit = SlidePasteLayout.fitText(125f, 20f, 100f, 60f, 12f)
                fit.lines shouldBe 1
                fit.size.toDouble() shouldBe (16.0 plusOrMinus 0.01)
            }

            Then("text that would shrink below the readable floor wraps instead") {
                // One line would need size 5: below 12, so wrap.
                val fit = SlidePasteLayout.fitText(400f, 20f, 100f, 60f, 12f)
                (fit.lines > 1) shouldBe true
                (fit.size >= 12f) shouldBe true
                // Its lines fit the cell height.
                (fit.lines * fit.size * SlidePasteLayout.LINE_HEIGHT <= 60f) shouldBe true
            }

            Then("at the floor it takes the lines that fit, the rest is cut") {
                val fit = SlidePasteLayout.fitText(5000f, 20f, 100f, 60f, 12f)
                fit.size shouldBe 12f
                fit.lines shouldBe (60f / (12f * SlidePasteLayout.LINE_HEIGHT)).toInt()
            }

            Then("a floor above the base size never enlarges the text") {
                SlidePasteLayout.fitText(400f, 10f, 100f, 60f, 12f).size shouldBe 10f
            }
        }

        Given("long items, Multiling style") {
            Then("they start small and wrap rather than shrink onto one line") {
                val base = SlidePasteLayout.longTextSize(30f)
                base.toDouble() shouldBe (16.5 plusOrMinus 0.01)
                // 1.5 widths of text: one shrunk line would still be readable,
                // but a long item wraps onto two lines at its base size.
                val fit = SlidePasteLayout.fitText(150f, base, 100f, 60f, 11f, wrapFirst = true)
                fit shouldBe SlidePasteLayout.TextFit(base, 2)
                SlidePasteLayout.fitText(150f, base, 100f, 60f, 11f).lines shouldBe 1
            }

            Then("they shrink further only when their lines do not fit") {
                val base = SlidePasteLayout.longTextSize(30f)
                val fit = SlidePasteLayout.fitText(600f, base, 100f, 60f, 11f, wrapFirst = true)
                (fit.size < base) shouldBe true
                (fit.size >= 11f) shouldBe true
                (fit.lines * fit.size * SlidePasteLayout.LINE_HEIGHT <= 60f) shouldBe true
            }
        }

        Given("the control strip") {
            Then("it reads close, previous, next from the top") {
                SlidePasteLayout.stripZone(10f, 300f, true) shouldBe SlidePasteLayout.Zone.CLOSE
                SlidePasteLayout.stripZone(150f, 300f, true) shouldBe SlidePasteLayout.Zone.PREV
                SlidePasteLayout.stripZone(290f, 300f, true) shouldBe SlidePasteLayout.Zone.NEXT
            }

            Then("without pages only close is live") {
                SlidePasteLayout.stripZone(10f, 300f, false) shouldBe SlidePasteLayout.Zone.CLOSE
                SlidePasteLayout.stripZone(150f, 300f, false) shouldBe SlidePasteLayout.Zone.NONE
                SlidePasteLayout.stripZone(290f, 300f, false) shouldBe SlidePasteLayout.Zone.NONE
            }

            Then("points outside it do nothing") {
                SlidePasteLayout.stripZone(-1f, 300f, true) shouldBe SlidePasteLayout.Zone.NONE
                SlidePasteLayout.stripZone(300f, 300f, true) shouldBe SlidePasteLayout.Zone.NONE
            }
        }

        Given("grid layout") {
            Then("row height drops until all rows fit on one page") {
                val lengths = List(12) { 40 }
                val grid = SlidePasteLayout.layout(12, 1000f, 500f, 100f, 30f, 40f, measure = measureOf(lengths))
                grid.pages shouldHaveSize 1
                (grid.pages[0].maxOf { it.bottom } <= 500f) shouldBe true
                // Tallest fit: two per row needs 2 * (20 + 1) * text <= 1000,
                // i.e. rows up to 1000 / 42 * 7 / 3 = 55.56 tall, 6 rows.
                grid.rowHeight.toDouble() shouldBe (55.55 plusOrMinus 0.1)
                grid.textSize shouldBe SlidePasteLayout.textSizeFor(grid.rowHeight)
            }

            Then("the tallest rows are kept when they already fit") {
                val grid = SlidePasteLayout.layout(3, 1000f, 500f, 100f, 30f, 40f, measure = measureOf(List(3) { 2 }))
                grid.rowHeight shouldBe 100f
                grid.pages shouldHaveSize 1
            }

            Then("overflow at the minimum row height pages whole rows, each item once") {
                val count = 60
                val grid = SlidePasteLayout.layout(count, 300f, 200f, 100f, 40f, 40f, measure = measureOf(List(count) { 200 }))
                grid.rowHeight shouldBe 40f
                (grid.pages.size > 1) shouldBe true
                grid.pages.forEach { page -> (page.maxOf { it.bottom } <= 200f) shouldBe true }
                grid.pages.flatten().map { it.item } shouldBe (0 until count).toList()
            }

            Then("cells tile their row with no gaps, so every point hits one cell") {
                val grid = SlidePasteLayout.layout(5, 1000f, 500f, 100f, 30f, 40f, measure = measureOf(listOf(3, 30, 8, 1, 60)))
                val cells = grid.pages[0]
                cells.groupBy { it.top }.values.forEach { row ->
                    row.first().left shouldBe 0f
                    row.zipWithNext().forEach { (a, b) -> b.left shouldBe a.right }
                    row.last().right.toDouble() shouldBe (1000.0 plusOrMinus 0.01)
                }
                cells.count { it.contains(500f, 10f) } shouldBe 1
            }

            Then("an empty list has no pages") {
                SlidePasteLayout.layout(0, 1000f, 500f, 100f, 30f, 40f, measure = measureOf(emptyList())).pages shouldHaveSize 0
            }
        }
    })
