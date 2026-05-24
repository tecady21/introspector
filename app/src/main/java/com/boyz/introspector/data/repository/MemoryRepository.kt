package com.boyz.introspector.data.repository

import android.content.Context
import android.os.Build
import com.boyz.introspector.data.model.MemoryAddress
import com.boyz.introspector.root.RootManager
import java.io.File

class MemoryRepository(private val context: Context) {

    private val scanmemDest = "/data/local/tmp/scanmem"
    private val memreadDest = "/data/local/tmp/memread"

    fun installBinary(): Boolean {
        val abi = Build.SUPPORTED_ABIS[0]
        return try {
            installAsset(abi, "scanmem", scanmemDest)
            installAsset(abi, "memread", memreadDest)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun installAsset(abi: String, name: String, dest: String) {
        val tmp = File(context.cacheDir, name)
        context.assets.open("$abi/$name").use { it.copyTo(tmp.outputStream()) }
        tmp.setExecutable(true)
        RootManager.runCommand("cp ${tmp.absolutePath} $dest && chmod 755 $dest")
    }

    fun getPid(packageName: String): Int {
        val result = RootManager.runCommand(
            "for d in /proc/[0-9]*; do " +
            "c=\$(cat \"\$d/cmdline\" 2>/dev/null | tr '\\0' ' '); " +
            "if echo \"\$c\" | grep -q '^$packageName'; then basename \$d; break; fi; done"
        ).trim()
        return result.toIntOrNull() ?: -1
    }

    fun scan(pid: Int, value: Int): List<MemoryAddress> {
        // Use -c flag to pass commands; default scan level is REGION_ALL_RW (patched in scanmem.c)
        val output = RootManager.runCommand(
            "$scanmemDest -c '$value;list;exit' $pid 2>/dev/null"
        )
        return parseListOutput(output, value)
    }

    // Filter a known address list to those still holding `value` — O(n) pread calls via memread.
    fun narrowDown(pid: Int, addresses: List<String>, value: Int): List<String> {
        if (addresses.isEmpty()) return emptyList()
        val addrList = addresses.joinToString(" ")
        val output = RootManager.runCommand(
            "echo '$addrList' | $memreadDest $pid 2>/dev/null"
        )
        return output.lines().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(colon + 1).toIntOrNull() == value)
                line.substring(0, colon).trim()
            else null
        }
    }

    fun write(pid: Int, address: String, value: Int) {
        RootManager.runCommand(
            "$scanmemDest -c 'write int32 $address $value;exit' $pid 2>/dev/null"
        )
    }

    // Scanmem list output (64-bit): "[ 0] 7fab12345678,  0 + 7fab12345678,  misc, 12345, [I32 ]"
    // POINTER_FMT = "%12lx" (no 0x prefix), value comes before the bracket type annotation.
    private fun parseListOutput(output: String, fallbackValue: Int): List<MemoryAddress> {
        val regex = Regex("""^\[\s*\d+\]\s+([0-9a-fA-F]+),\s*\d+\s*\+\s*[0-9a-fA-F]+,\s*\w+,\s*(-?\d+),""")
        return output.lines().mapNotNull { line ->
            regex.find(line)?.let { match ->
                MemoryAddress(
                    hex = match.groupValues[1],
                    currentValue = match.groupValues[2].toIntOrNull() ?: fallbackValue
                )
            }
        }
    }
}
