package com.ai.assistance.operit.terminal

/** Encode data for Bash before it crosses the interactive PTY / Readline boundary. */
internal fun quoteBashCommandPayload(value: String): String {
    require('\u0000' !in value) { "Terminal commands cannot contain NUL (U+0000)" }
    return buildString(value.length + 3) {
        append("$'")
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            when (code) {
                0x27 -> append("\\'")
                0x5c -> append("\\\\")
                0x09 -> append("\\t")
                0x0a -> append("\\n")
                0x0d -> append("\\r")
                // Control bytes must never become editor keys. Encoding UTF-8 bytes also makes
                // transport independent of Readline's locale; escape ! before history expansion.
                in 0x20..0x7e -> if (code == 0x21) append("\\041") else append(code.toChar())
                else -> append('\\').append(code.toString(8).padStart(3, '0'))
            }
        }
        append('\'')
    }
}

/** One physical input line, evaluated in the existing shell to retain cwd, exports and jobs. */
internal fun buildCommandWithExitMarkerProtocol(command: String, commandId: String): String {
    val payload = quoteBashCommandPayload(command.ifBlank { ":" })
    return buildString {
        // pwd -P checks the actual directory rather than Bash's cached $PWD. Only a failed
        // lookup triggers the user-requested HOME recovery; failed cd must prevent execution.
        append("if builtin pwd -P >/dev/null 2>&1; then :; else builtin cd -- \"\$HOME\"; fi && ")
        // Quoting alone is insufficient for a PTY: literal TAB triggers completion even inside
        // a multiline single-quoted eval. ANSI-C decoding happens after Readline accepts input.
        append("eval ").append(payload).append("; ")
        append("printf '\\033]1337;%s%s:%s\\007' '")
        append(CommandExitMarker.PREFIX)
        append("' ").append(quoteBashCommandPayload(commandId))
        append(" \"\$?\"\n")
    }
}
