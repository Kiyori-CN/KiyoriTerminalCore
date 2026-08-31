package com.ai.assistance.operit.terminal.provider.type

/** Detects a marker even when a reader splits it across adjacent chunks. */
internal class HiddenExecReadyMarkerDetector(
    private val marker: String,
) {
    private var previousTail = ""

    init {
        require(marker.isNotEmpty()) { "marker must not be empty" }
    }

    fun append(chunk: String): Boolean {
        if (chunk.isEmpty()) {
            return false
        }

        val probe = previousTail + chunk
        if (probe.contains(marker)) {
            return true
        }

        previousTail = probe.takeLast(marker.length - 1)
        return false
    }
}

/**
 * Keeps hidden-exec output bounded while retaining protocol markers needed to settle a command.
 * Markers are supplied as prefixes and are captured line-by-line even when they cross read chunks.
 */
internal class HiddenExecOutputBuffer(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val headChars: Int = DEFAULT_HEAD_CHARS,
    private val truncationMarker: String = DEFAULT_TRUNCATION_MARKER,
    protectedMarkerPrefixes: List<String> = emptyList(),
) {
    private val value = StringBuilder()
    private val protectedPrefixes = protectedMarkerPrefixes.filter { it.isNotEmpty() }
    private val protectedFragments = linkedMapOf<String, String>()
    private var scanTail = ""
    private var head = ""
    private var tail = ""

    var truncated: Boolean = false
        private set

    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(headChars >= 0) { "headChars must not be negative" }
    }

    fun append(chunk: String) {
        if (chunk.isEmpty()) {
            return
        }

        val source = scanTail + chunk
        captureProtectedFragments(source)
        val scanLimit = protectedPrefixes.maxOfOrNull { it.length }?.plus(MARKER_SCAN_SUFFIX_CHARS) ?: 0
        scanTail = if (scanLimit == 0) "" else source.takeLast(scanLimit)

        if (!truncated) {
            value.append(chunk)
            if (value.length <= maxChars) {
                return
            }

            truncated = true
            val combined = value.toString()
            head = combined.take(headChars)
            tail = combined.takeLast(maxChars)
            value.setLength(0)
            return
        }

        tail = (tail + chunk).takeLast(maxChars)
    }

    fun indexOf(needle: String): Int = toString().indexOf(needle)

    override fun toString(): String {
        if (!truncated) {
            return value.toString()
        }

        val fragments = protectedPrefixes.mapNotNull { protectedFragments[it] }
        val rawPayload = (head + tail).removeProtectedFragments(fragments)
        val markerLength = fragments.sumOf { it.length }
        val payloadCapacity = (maxChars - truncationMarker.length - markerLength).coerceAtLeast(0)
        val payloadHeadLength = minOf(headChars, payloadCapacity)
        val payloadTailLength = payloadCapacity - payloadHeadLength
        val payloadHead = rawPayload.take(payloadHeadLength)
        val payloadTail = rawPayload.takeLast(payloadTailLength)

        return buildString(maxChars) {
            if (fragments.size >= 2) {
                append(fragments.first())
            }
            append(payloadHead)
            append(truncationMarker)
            if (fragments.size >= 3) {
                fragments.subList(1, fragments.lastIndex).forEach(::append)
            }
            append(payloadTail)
            if (fragments.size == 1) {
                append(fragments.first())
            } else {
                fragments.lastOrNull()?.let(::append)
            }
        }
    }

    private fun captureProtectedFragments(source: String) {
        protectedPrefixes.forEach { prefix ->
            var index = source.indexOf(prefix)
            while (index >= 0) {
                val lineEnd = source.indexOf('\n', index)
                if (lineEnd < 0) {
                    break
                }
                protectedFragments.putIfAbsent(prefix, source.substring(index, lineEnd + 1))
                index = source.indexOf(prefix, index + prefix.length)
            }
        }
    }

    private fun String.removeProtectedFragments(fragments: List<String>): String {
        var result = this
        fragments.forEach { fragment -> result = result.replace(fragment, "") }
        return result
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 256_000
        const val DEFAULT_HEAD_CHARS = 8_192
        const val DEFAULT_TRUNCATION_MARKER = "\n...[hidden output truncated]...\n"
        private const val MARKER_SCAN_SUFFIX_CHARS = 128
    }
}
