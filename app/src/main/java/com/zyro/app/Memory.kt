package com.zyro.app

import org.json.JSONArray
import org.json.JSONObject

/** Long-term facts the friend remembers about you, plus the recent chat. */
object Memory {
    private const val MAX_FACTS = 150
    private const val MAX_HISTORY = 40 // messages (20 back-and-forth turns)

    fun facts(): List<String> {
        val arr = try { JSONArray(Prefs.facts) } catch (e: Exception) { JSONArray() }
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun saveFacts(list: List<String>) {
        Prefs.facts = JSONArray(list).toString()
    }

    private var turns = 0

    /** Every few turns, quietly pull lasting facts about the user out of the recent chat. */
    fun learn() {
        if (++turns % 4 != 0 || !Prefs.hasGemini) return
        val h = history().takeLast(8)
        if (h.isEmpty()) return
        val chat = h.joinToString("\n") { (r, t) -> (if (r == "assistant") "Zyro: " else "User: ") + t.take(300) }
        val known = facts().takeLast(40).joinToString("; ")
        val j = Brain.quickJson(
            "From this chat, list up to 3 NEW lasting facts about the USER that will help in future (name, family, work, goals, projects, routines, likes and dislikes, how they want Zyro to behave). " +
                "Skip anything temporary, and never store passwords, ID or card numbers, health or money details. Already known: $known\n\nChat:\n$chat\n\n" +
                "Reply strict JSON: {\"facts\":[\"short fact in the user's own words\"]} or {\"facts\":[]}",
            200
        ) ?: return
        try {
            val arr = org.json.JSONObject(j.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).optJSONArray("facts") ?: return
            for (i in 0 until minOf(3, arr.length())) addFact(arr.optString(i))
        } catch (e: Exception) {
            // ignore a bad answer
        }
    }

    fun addFact(fact: String) {
        val clean = fact.trim().take(300)
        if (clean.isEmpty()) return
        val list = facts().toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return
        list.add(clean)
        while (list.size > MAX_FACTS) list.removeAt(0)
        saveFacts(list)
    }

    /** Removes every remembered fact containing [match]. Returns how many were removed. */
    fun forget(match: String): Int {
        val m = match.trim().lowercase()
        if (m.isEmpty()) return 0
        val before = facts()
        val after = before.filterNot { it.lowercase().contains(m) }
        saveFacts(after)
        return before.size - after.size
    }

    fun clearAll() {
        Prefs.facts = "[]"
        Prefs.history = "[]"
    }

    /** (role, text) pairs, role is "user" or "assistant". */
    fun history(): MutableList<Pair<String, String>> {
        val arr = try { JSONArray(Prefs.history) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optString("role") to o.optString("text"))
        }
        return out
    }

    fun saveHistory(list: List<Pair<String, String>>) {
        var l = list.takeLast(MAX_HISTORY)
        while (l.isNotEmpty() && l.first().first != "user") l = l.drop(1)
        val arr = JSONArray()
        for ((role, text) in l) arr.put(JSONObject().put("role", role).put("text", text))
        Prefs.history = arr.toString()
    }
}
