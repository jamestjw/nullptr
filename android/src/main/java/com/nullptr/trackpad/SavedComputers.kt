package com.nullptr.trackpad

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class SavedComputers(private val prefs: SharedPreferences) {
    private data class Snapshot(val computers: Map<String, PairedComputer>, val selected: String?)

    private var snapshot = load()
    val all: Collection<PairedComputer> get() = snapshot.computers.values
    val active: PairedComputer? get() = snapshot.computers[snapshot.selected]

    fun pair(computer: PairedComputer) {
        val key = computer.address.toString()
        val saved = computer.copy(name = snapshot.computers[key]?.name)
        save(Snapshot(snapshot.computers + (key to saved), key))
    }

    fun select(address: TailnetAddress) {
        val key = address.toString()
        if (key in snapshot.computers && key != snapshot.selected) save(snapshot.copy(selected = key))
    }

    fun rename(address: TailnetAddress, name: String) {
        val key = address.toString()
        val computer = snapshot.computers[key] ?: return
        val renamed = computer.copy(name = name.trim().ifEmpty { null })
        save(snapshot.copy(computers = snapshot.computers + (key to renamed)))
    }

    fun forget(address: TailnetAddress) {
        val key = address.toString()
        val remaining = snapshot.computers - key
        val selected = if (key == snapshot.selected) remaining.keys.firstOrNull() else snapshot.selected
        save(Snapshot(remaining, selected))
    }

    private fun load(): Snapshot {
        val stored = prefs.getString("savedComputers", null)
        if (stored != null) return decode(stored)

        val address = TailnetAddress.parse(prefs.getString("address", "").orEmpty())
        val token = prefs.getString("token", "").orEmpty()
        if (address == null || token.isBlank()) return Snapshot(emptyMap(), null)
        val key = address.toString()
        return Snapshot(mapOf(key to PairedComputer(address, token)), key).also(::persist)
    }

    private fun decode(stored: String): Snapshot {
        val json = try {
            JSONObject(stored)
        } catch (_: JSONException) {
            return Snapshot(emptyMap(), null)
        }
        val computers = linkedMapOf<String, PairedComputer>()
        val entries = json.optJSONArray("computers") ?: JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val address = TailnetAddress.parse(entry.optString("address")) ?: continue
            val token = entry.opt("token") as? String ?: continue
            if (token.isBlank()) continue
            val name = (entry.opt("name") as? String)?.trim()?.ifEmpty { null }
            computers[address.toString()] = PairedComputer(address, token, name)
        }
        val selected = TailnetAddress.parse(json.optString("selected"))?.toString()
        return Snapshot(computers, selected?.takeIf(computers::containsKey) ?: computers.keys.firstOrNull())
    }

    private fun save(next: Snapshot) {
        persist(next)
        snapshot = next
    }

    private fun persist(next: Snapshot) {
        val entries = JSONArray()
        next.computers.values.forEach { computer ->
            entries.put(JSONObject()
                .put("address", computer.address.toString())
                .put("token", computer.token)
                .put("name", computer.name))
        }
        val json = JSONObject().put("computers", entries).put("selected", next.selected)
        prefs.edit().putString("savedComputers", json.toString()).remove("address").remove("token").apply()
    }
}
