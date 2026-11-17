package org.voiceassistant

/** What was said, for the log: the words in debug builds, only their length in release (logcat is readable over adb). */
internal fun said(t: CharSequence?): String = if (BuildConfig.DEBUG) "\"${t ?: "?"}\"" else "(${t?.length ?: 0} chars)"
