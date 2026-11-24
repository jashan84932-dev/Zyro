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
    @Volatile private var learnPauseUntil = 0L

    /** Every few turns, quietly pull lasting facts about the user out of the recent chat. */
    fun learn(force: Boolean = false) {
        if ((!force && ++turns % 4 != 0) || !Prefs.hasGemini) return
        // do not spend the free Gemini quota on this when it is already tight (the agent and Live need it more)
        if (!force && System.currentTimeMillis() < learnPauseUntil) return
        val h = history().takeLast(8)
        if (h.isEmpty()) return
        // small talk like "haan", "theek hai", "ok" holds nothing worth remembering
        if (!force && h.filter { it.first == "user" }.sumOf { it.second.length } < 40) return
        val chat = h.joinToString("\n") { (r, t) -> (if (r == "assistant") "Zyro: " else "User: ") + t.take(300) }
        val known = facts().takeLast(60).joinToString("; ")
        val j = Brain.quickJson(
            "From this chat, list up to 3 NEW lasting facts about the USER that will help in future (name, family, work, goals, projects, routines, likes and dislikes, how they want Zyro to behave). " +
                "Skip anything temporary, and never store passwords, ID or card numbers, health or money details. Already known: $known\n\nChat:\n$chat\n\n" +
                "If the user corrected or changed something that is already known (moved city, changed job, new phone, changed mind), also list the old known fact in \"outdated\", copied exactly. " +
                "Reply strict JSON: {\"facts\":[\"short fact in the user's own words\"],\"outdated\":[]}",
            260, 25000, true
        ) ?: run {
            if (Brain.lastFail == 429) learnPauseUntil = System.currentTimeMillis() + 3 * 60_000L
            return
        }
        try {
            val obj = org.json.JSONObject(j.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            val old = obj.optJSONArray("outdated")
            if (old != null && old.length() > 0) {
                val cur = facts().toMutableList()
                for (i in 0 until minOf(3, old.length())) {
                    val o = old.optString(i).trim()
                    cur.removeAll { it.equals(o, ignoreCase = true) }
                }
                saveFacts(cur)
            }
            val arr = obj.optJSONArray("facts") ?: return
            for (i in 0 until minOf(3, arr.length())) addFact(arr.optString(i))
        } catch (e: Exception) {
            // ignore a bad answer
        }
    }

    /** Adds one finished exchange to the saved chat (used by Live so the next session continues the thread). */
    fun addTurn(user: String, assistant: String) {
        if (user.isBlank() && assistant.isBlank()) return
        val h = history()
        h.add("user" to user.ifBlank { "(…)" }.take(400))
        h.add("assistant" to assistant.ifBlank { "(…)" }.take(400))
        saveHistory(h)
    }

    private fun tokens(t: String): Set<String> =
        t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()

    fun addFact(fact: String) {
        val clean = fact.trim().take(300)
        if (clean.isEmpty()) return
        val list = facts().toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return
        // a near-copy ("Jashan ko chai pasand hai" vs "Jashan ko chai bahut pasand hai") is not stored twice;
        // if the new one is longer it replaces the older shorter wording
        val nt = tokens(clean)
        for (i in list.indices) {
            val ot = tokens(list[i])
            if (nt.size < 3 || ot.size < 3) continue
            val common = nt.count { it in ot }
            if (common.toFloat() / minOf(nt.size, ot.size) >= 0.8f) {
                if (clean.length > list[i].length) { list[i] = clean; saveFacts(list) }
                return
            }
        }
        list.add(clean)
        while (list.size > MAX_FACTS) list.removeAt(0)
        saveFacts(list)
    }

    /** What the user just said; Brain sets it so the prompt can pick the facts that matter for it. */
    @Volatile var lastQuery = ""

    private val stop = setOf(
        "hai", "hain", "ho", "hu", "hoon", "ka", "ki", "ke", "ko", "se", "me", "mein", "par", "pe", "aur", "ya",
        "to", "toh", "ye", "yeh", "wo", "woh", "kya", "kyun", "nahi", "nhi", "bhi", "the", "is", "are", "and",
        "for", "you", "your", "with", "that", "this", "user", "jashan"
    )

    /**
     * Picks at most [max] facts for the prompt: the newest few always, then the older ones that share
     * words with [query]. With 150 saved facts this keeps older but relevant facts (project, phone,
     * family) in reach and keeps the prompt short, so replies start sooner.
     */
    fun recall(query: String, max: Int = 30): List<String> {
        val all = facts()
        if (all.size <= max) return all
        val qt = tokens(query).filter { it !in stop }.toSet()
        val keep = HashSet<Int>()
        val newest = minOf(10, max)
        for (i in all.size - newest until all.size) keep.add(i)
        if (qt.isNotEmpty()) {
            val scored = ArrayList<Pair<Int, Int>>() // index to score
            for (i in 0 until all.size - newest) {
                val score = tokens(all[i]).count { it in qt }
                if (score > 0) scored.add(i to score)
            }
            scored.sortWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            for ((i, _) in scored) {
                if (keep.size >= max) break
                keep.add(i)
            }
        }
        // fill any remaining room with the next newest facts
        var j = all.size - newest - 1
        while (keep.size < max && j >= 0) { keep.add(j); j-- }
        return keep.sorted().map { all[it] }
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
