package com.winlator.star.store.blsteam

/**
 * Minimal protobuf wire reader for the handful of CM push messages the Kotlin side decodes off the
 * engine's message firehose ([BlSteamStateObserver.onClientMessage]): account info, friends list,
 * persona state, nickname list, add-friend response. Only the fields we consume are surfaced; every
 * other field is skipped by wire type. Never throws — a truncated/garbage body just ends the scan.
 *
 * Wire types: 0 varint, 1 fixed64, 2 length-delimited, 5 fixed32 (groups are unsupported → stop).
 */
class BlProto(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    /** Current field number after [next]; 0 when the scan ended. */
    var field: Int = 0
        private set

    /** Current wire type after [next]. */
    var wireType: Int = -1
        private set

    /** Varint value when [wireType] == 0. */
    var varint: Long = 0L
        private set

    /** Fixed 64-bit value when [wireType] == 1. */
    var fixed64: Long = 0L
        private set

    /** Fixed 32-bit value when [wireType] == 5. */
    var fixed32: Int = 0
        private set

    /** Payload when [wireType] == 2 (a copy; zero-length when absent). */
    var bytes: ByteArray = EMPTY
        private set

    val eof: Boolean get() = pos >= end

    /** Advance to the next field. Returns false at end of input or on a malformed tag/body. */
    fun next(): Boolean {
        if (pos >= end) return false
        val tag = readVarint() ?: return false
        field = (tag ushr 3).toInt()
        wireType = (tag and 7L).toInt()
        if (field <= 0) return false
        when (wireType) {
            0 -> varint = readVarint() ?: return false
            1 -> {
                if (pos + 8 > end) return false
                var v = 0L
                for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
                fixed64 = v
                pos += 8
            }
            2 -> {
                val len = readVarint() ?: return false
                if (len < 0 || len > (end - pos)) return false
                bytes = buf.copyOfRange(pos, pos + len.toInt())
                pos += len.toInt()
            }
            5 -> {
                if (pos + 4 > end) return false
                var v = 0
                for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
                fixed32 = v
                pos += 4
            }
            else -> return false
        }
        return true
    }

    /** UTF-8 string of the current length-delimited payload. */
    fun string(): String = if (wireType == 2) String(bytes, Charsets.UTF_8) else ""

    /** A sub-reader over the current length-delimited payload. */
    fun sub(): BlProto = BlProto(bytes)

    /** Int view of the current varint (protobuf int32/uint32/enum/bool). */
    fun int(): Int = varint.toInt()

    fun bool(): Boolean = varint != 0L

    private fun readVarint(): Long? {
        var shift = 0
        var v = 0L
        while (pos < end && shift < 64) {
            val b = buf[pos++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return v
            shift += 7
        }
        return null
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** Lowercase hex of [bytes]; null for empty or all-zero (an unset Steam avatar hash). */
        fun hexOrNull(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty()) return null
            if (bytes.all { it.toInt() == 0 }) return null
            val hexChars = "0123456789abcdef"
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(hexChars[v ushr 4])
                sb.append(hexChars[v and 0x0F])
            }
            return sb.toString()
        }
    }
}
