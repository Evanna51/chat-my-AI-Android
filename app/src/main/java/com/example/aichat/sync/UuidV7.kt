package com.example.aichat.sync

import java.security.SecureRandom

/**
 * RFC 9562 UUID v7: 48-bit Unix ms timestamp + 4-bit version + 12-bit rand_a
 * + 2-bit variant + 62-bit rand_b. Lexicographically sortable when generated
 * within the same millisecond if rand monotonically increases — we don't need
 * monotonic guarantee here (single-process, low rate).
 */
object UuidV7 {

    private val random = SecureRandom()

    fun next(): String = next(System.currentTimeMillis())

    fun next(unixMs: Long): String {
        val ts = unixMs and 0x0000_FFFF_FFFF_FFFFL
        val randA = random.nextInt(0x1000)
        val msb = (ts shl 16) or (0x7L shl 12) or randA.toLong()

        val randB = ByteArray(8)
        random.nextBytes(randB)
        randB[0] = ((randB[0].toInt() and 0x3F) or 0x80).toByte()
        var lsb = 0L
        for (b in randB) lsb = (lsb shl 8) or (b.toLong() and 0xFF)

        return formatUuid(msb, lsb)
    }

    private fun formatUuid(msb: Long, lsb: Long): String {
        val sb = StringBuilder(36)
        appendHex(sb, msb ushr 32, 8); sb.append('-')
        appendHex(sb, (msb ushr 16) and 0xFFFF, 4); sb.append('-')
        appendHex(sb, msb and 0xFFFF, 4); sb.append('-')
        appendHex(sb, lsb ushr 48, 4); sb.append('-')
        appendHex(sb, lsb and 0x0000_FFFF_FFFF_FFFFL, 12)
        return sb.toString()
    }

    private fun appendHex(sb: StringBuilder, value: Long, width: Int) {
        val hex = java.lang.Long.toHexString(value)
        for (i in 0 until (width - hex.length)) sb.append('0')
        sb.append(hex)
    }
}
