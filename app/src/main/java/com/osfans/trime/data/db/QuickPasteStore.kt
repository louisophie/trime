/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import com.osfans.trime.data.base.DataManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class QuickPasteItem(
    val id: Int = 0,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
)

@Serializable
data class QuickPasteData(
    val items: List<QuickPasteItem> = emptyList(),
    val nextId: Int = 1,
)

object QuickPasteStore {
    private const val FILE_NAME = "quick_paste.json"
    private const val MAX_ITEMS = 1000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file by lazy { File(DataManager.userDataDir, FILE_NAME) }
    private var cachedData: QuickPasteData? = null

    private fun load(): QuickPasteData {
        cachedData?.let { return it }
        val data = if (file.exists()) {
            try {
                json.decodeFromString(file.readText())
            } catch (e: Exception) {
                QuickPasteData()
            }
        } else {
            QuickPasteData()
        }
        cachedData = data
        return data
    }

    private fun save(data: QuickPasteData) {
        cachedData = data
        file.writeText(json.encodeToString(data))
    }

    fun getAll(): List<QuickPasteItem> = load().items.sortedWith(
        compareByDescending<QuickPasteItem> { it.pinned }.thenByDescending { it.time },
    )

    fun add(text: String): QuickPasteItem {
        val data = load()
        val item = QuickPasteItem(id = data.nextId, text = text)
        val newItems = (data.items + item).take(MAX_ITEMS)
        save(data.copy(items = newItems, nextId = data.nextId + 1))
        return item
    }

    fun update(id: Int, newText: String) {
        val data = load()
        val newItems = data.items.map { if (it.id == id) it.copy(text = newText, time = System.currentTimeMillis()) else it }
        save(data.copy(items = newItems))
    }

    fun togglePinned(id: Int) {
        val data = load()
        val newItems = data.items.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        save(data.copy(items = newItems))
    }

    fun delete(id: Int) {
        val data = load()
        val newItems = data.items.filter { it.id != id }
        save(data.copy(items = newItems))
    }

    fun deleteAll() {
        save(QuickPasteData())
    }

    fun get(id: Int): QuickPasteItem? = load().items.find { it.id == id }
}
