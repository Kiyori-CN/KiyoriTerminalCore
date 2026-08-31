package com.ai.assistance.operit.terminal

/**
 * Protocol marker used by the interactive shell to report the status of one
 * command without changing the shell's persistent cwd or exported variables.
 */
internal object CommandExitMarker {
    const val PREFIX = "__OPERIT_COMMAND_EXIT__:"

    private val pattern = Regex("${Regex.escape(PREFIX)}[0-9a-fA-F-]+:(-?[0-9]+)")

    fun parse(line: String): Int? = pattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
