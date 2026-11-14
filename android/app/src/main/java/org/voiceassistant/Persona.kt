package org.voiceassistant

/**
 * Who the assistant is, from config.json (scripts/setup.py): its name, the wake phrase, and how it
 * addresses you. Nothing personal is hard-coded anywhere else in the app.
 */
object Persona {
    val NAME: String = BuildConfig.ASSISTANT_NAME
    val WAKE_PHRASE: String = BuildConfig.WAKE_PHRASE
    /** "sir", "Sam", or empty for no form of address. */
    val ADDRESS: String = BuildConfig.ADDRESS

    /** A spoken line with "%A" where the form of address goes: "Paused%A." -> "Paused, sir." or "Paused." */
    fun say(line: String): String = line.replace("%A", if (ADDRESS.isBlank()) "" else ", $ADDRESS")

    /** The wake phrase or the bare name at the start of something said ("hey nova, open hulu" -> "open hulu"). */
    val leadingName: Regex = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:" + Regex.escape(NAME.lowercase()) + "|" + Regex.escape(WAKE_PHRASE.lowercase()) + ")[,.!]?\\s*",
        RegexOption.IGNORE_CASE,
    )
    /** The name anywhere in what was said. */
    val anyName: Regex = Regex("\\b" + Regex.escape(NAME.lowercase()) + "\\b", RegexOption.IGNORE_CASE)
}
