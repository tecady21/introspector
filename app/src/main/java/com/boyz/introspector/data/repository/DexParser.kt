package com.boyz.introspector.data.repository

import java.io.File

/**
 * Instant DEX binary-header parser — no JADX, no significant memory.
 *
 * Returns fully-qualified class names (e.g. "com.example.Foo") directly from
 * the DEX file's class-definition table.  Empty list is returned for:
 *   • files smaller than the minimum DEX header (112 bytes)
 *   • CDEX / compact-DEX files (magic "cdex" ≠ "dex\n")
 *   • any I/O or parse error
 *
 * Mirrored in [DecompilerService] previously; now the single source of truth.
 */
object DexParser {

    fun parseClassNames(file: File): List<String> =
        try { parseBytes(file.readBytes()) } catch (_: Exception) { emptyList() }

    fun parseClassInfos(file: File): List<ClassInfo> =
        parseClassNames(file).map { fullName ->
            val dot = fullName.lastIndexOf('.')
            ClassInfo(
                fullName    = fullName,
                simpleName  = if (dot >= 0) fullName.substring(dot + 1) else fullName,
                packageName = if (dot >= 0) fullName.substring(0, dot)  else "(default)"
            )
        }

    private fun parseBytes(b: ByteArray): List<String> {
        // Validate DEX magic: "dex\n" (0x64 0x65 0x78 0x0A)
        if (b.size < 112) return emptyList()
        if (b[0] != 0x64.toByte() || b[1] != 0x65.toByte() ||
            b[2] != 0x78.toByte() || b[3] != 0x0A.toByte()) return emptyList()

        fun i32(off: Int) =
            (b[off].toInt()     and 0xFF)         or
            ((b[off+1].toInt()  and 0xFF) shl  8) or
            ((b[off+2].toInt()  and 0xFF) shl 16) or
            ((b[off+3].toInt()  and 0xFF) shl 24)

        val strIdsOff    = i32(0x3C)
        val typeIdsSize  = i32(0x40)
        val typeIdsOff   = i32(0x44)
        val classDefsN   = i32(0x5C)
        val classDefsOff = i32(0x60)

        if (classDefsN <= 0) return emptyList()
        if (classDefsOff.toLong() + classDefsN.toLong() * 32 > b.size) return emptyList()
        if (typeIdsOff.toLong()   + typeIdsSize.toLong() *  4 > b.size) return emptyList()

        val result = ArrayList<String>(classDefsN)
        for (i in 0 until classDefsN) {
            val defOff   = classDefsOff + i * 32
            val classIdx = i32(defOff)
            if (classIdx >= typeIdsSize) continue
            val strIdx     = i32(typeIdsOff + classIdx * 4)
            val strDataOff = i32(strIdsOff  + strIdx   * 4)
            if (strDataOff <= 0 || strDataOff >= b.size) continue

            // ULEB128 string length
            var pos = strDataOff; var len = 0; var shift = 0
            while (pos < b.size && shift <= 28) {
                val byte = b[pos++].toInt() and 0xFF
                len = len or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            if (len < 2 || pos + len > b.size) continue
            // Dalvik type descriptors start with 'L' and end with ';'
            if (b[pos] != 0x4C.toByte() || b[pos + len - 1] != 0x3B.toByte()) continue

            result += String(b, pos + 1, len - 2, Charsets.UTF_8).replace('/', '.')
        }
        return result
    }
}
