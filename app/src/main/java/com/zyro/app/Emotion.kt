package com.zyro.app

/**
 * Feelings in the voice. The AI starts each spoken answer with a mood tag like [happy] or [worried].
 * Here the tag is removed from the text and turned into pitch and speed, so the same words sound glad,
 * gentle, excited or tired - the way a person's voice changes with how they feel.
 */
object Emotion {

    private val TAG = Regex("^\\s*\\[(\\w{3,10})]\\s*")

    class Style(val pitch: Float, val rate: Float)

    private val STYLES = mapOf(
        "calm" to Style(1.00f, 0.97f),
        "happy" to Style(1.07f, 1.05f),
        "excited" to Style(1.13f, 1.15f),
        "playful" to Style(1.09f, 1.06f),
        "sad" to Style(0.91f, 0.82f),
        "worried" to Style(1.03f, 1.00f),
        "serious" to Style(0.95f, 0.93f),
        "tired" to Style(0.90f, 0.80f),
        "gentle" to Style(0.98f, 0.88f),
        "proud" to Style(1.04f, 0.96f)
    )

    /** Returns (mood, text without the tag). No tag = calm. */
    fun parse(text: String): Pair<String, String> {
        val m = TAG.find(text) ?: return "calm" to text
        val mood = m.groupValues[1].lowercase()
        return (if (STYLES.containsKey(mood)) mood else "calm") to text.removeRange(m.range).trimStart()
    }

    fun strip(text: String): String = parse(text).second

    fun style(mood: String): Style = STYLES[mood] ?: STYLES.getValue("calm")
}
