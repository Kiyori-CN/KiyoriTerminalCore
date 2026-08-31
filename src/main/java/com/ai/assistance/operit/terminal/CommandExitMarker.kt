package com.ai.assistance.operit.terminal

/**
 * Protocol marker used by the interactive shell to report the status of one
 * command without changing the shell's persistent cwd or exported variables.
 */
internal object CommandExitMarker {
    /** The current marker is carried in an ANSI OSC payload, so it is not rendered as text. */
    const val PREFIX = "__KIYORI_COMMAND_EXIT__:"
    private const val LEGACY_PREFIX = "__OPERIT_COMMAND_EXIT__:"
    private const val OSC_COMMAND = "1337"
    private const val OSC_START = "\u001B]$OSC_COMMAND;${PREFIX}"

    private const val COMMAND_ID_PATTERN = "[0-9a-fA-F-]+"
    private val commandMarkerPatterns = listOf(
        Regex(
            "\u001B\\]$OSC_COMMAND;" + Regex.escape(PREFIX) +
                "($COMMAND_ID_PATTERN):(-?[0-9]+)(?:\u0007|\u001B\\\\)"
        ),
        Regex(
            "(?<!\u001B\\]$OSC_COMMAND;)" + Regex.escape(PREFIX) +
                "($COMMAND_ID_PATTERN):(-?[0-9]+)"
        ),
        Regex(
            Regex.escape(LEGACY_PREFIX) + "($COMMAND_ID_PATTERN):(-?[0-9]+)"
        ),
    )

    fun parse(line: String): Int? = parse(line, expectedCommandId = null)

    fun parse(line: String, expectedCommandId: String?): Int? = commandMarkerPatterns.asSequence()
        .mapNotNull { pattern ->
            pattern.find(line)?.let { match ->
                val commandId = match.groupValues.getOrNull(1)
                val exitCode = match.groupValues.getOrNull(2)?.toIntOrNull()
                if (exitCode != null && (expectedCommandId == null || commandId == expectedCommandId)) {
                    exitCode
                } else {
                    null
                }
            }
        }
        .firstOrNull()

    fun isProtocolEcho(line: String): Boolean =
        line.contains("__operit_command_exit_code=\$?") ||
            line.contains("printf") &&
                (line.contains(PREFIX) || line.contains(LEGACY_PREFIX))

    fun oscPrefix(): String = "\u001B]$OSC_COMMAND;"

    /**
     * Removes Kiyori OSC status envelopes before they reach the canvas renderer.
     *
     * The ANSI scanner intentionally operates on one read chunk at a time and therefore cannot
     * carry an unfinished OSC sequence into the next chunk. Keeping that carry here prevents the
     * sequence suffix from being rendered as ordinary terminal text when a PTY read splits it.
     */
    class DisplayFilter {
        private var pending = ""

        fun filter(chunk: String): String {
            if (chunk.isEmpty() && pending.isEmpty()) {
                return ""
            }

            val input = pending + chunk
            pending = ""
            val output = StringBuilder(input.length)
            var cursor = 0

            while (cursor < input.length) {
                val start = input.indexOf(OSC_START, cursor)
                if (start < 0) {
                    val partialStart = findTrailingStart(input, cursor)
                    if (partialStart >= 0) {
                        output.append(input, cursor, partialStart)
                        pending = input.substring(partialStart)
                    } else {
                        output.append(input, cursor, input.length)
                    }
                    break
                }

                output.append(input, cursor, start)
                val payloadStart = start + OSC_START.length
                val belIndex = input.indexOf('\u0007', payloadStart)
                val stIndex = input.indexOf("\u001B\\", payloadStart)
                val endIndex = when {
                    belIndex < 0 -> stIndex
                    stIndex < 0 -> belIndex
                    else -> minOf(belIndex, stIndex)
                }
                if (endIndex < 0) {
                    pending = input.substring(start)
                    break
                }

                cursor = endIndex + if (endIndex == belIndex) 1 else 2
            }

            return output.toString()
        }

        private fun findTrailingStart(input: String, fromIndex: Int): Int {
            val maxLength = minOf(OSC_START.length - 1, input.length - fromIndex)
            for (length in maxLength downTo 1) {
                val start = input.length - length
                if (start < fromIndex) {
                    continue
                }
                if (input.regionMatches(start, OSC_START, 0, length)) {
                    return start
                }
            }
            return -1
        }
    }
}
