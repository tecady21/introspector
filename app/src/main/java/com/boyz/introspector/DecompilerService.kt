package com.boyz.introspector

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.security.JadxSecurityFlag
import jadx.api.security.impl.JadxSecurity
import jadx.core.xmlgen.BinaryXMLParser
import jadx.plugins.input.dex.DexInputPlugin
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Isolated JADX worker running in the `:decompiler` process.
 *
 * Moving JADX out of the main process means that an OOM during type-graph
 * construction crashes only this service — the UI continues running and shows
 * a graceful error instead of killing the whole app.
 *
 * ── Protocol (Messenger, no AIDL) ────────────────────────────────────────────
 *  Main → Service  [MSG_LOAD]     APK paths + cache root + cache key
 *  Main → Service  [MSG_CANCEL]   Abort current batch
 *  Service → Main  [MSG_PROGRESS] Human-readable status update
 *  Service → Main  [MSG_READY]    XMLs decoded; class list is usable — main
 *                                 process unblocks here
 *  Service → Main  [MSG_DONE]     Batch complete (success = true) or failed
 *
 * ── Two-phase JADX loading ───────────────────────────────────────────────────
 *  Large game APKs were killed by Android's LMK because the old single-JADX
 *  approach held the full type graph (all DEX) + XML decode buffers in memory
 *  simultaneously.
 *
 *  Phase A – XML decoding:
 *    Load JADX over just the base-APK DEX files (name prefix "0_").  This type
 *    graph is a fraction of all split-APK DEX combined.  isSkipResources=true
 *    (same as Phase B) — BinaryXMLParser decodes binary XML from the embedded
 *    string pool; it does not need the app's resources.arsc.  Decode all XMLs
 *    sequentially (flat peak heap), then close JADX and GC.
 *
 *  Phase B – Class decompilation:
 *    Load JADX over all DEX files, isSkipResources=true, full thread count.
 *    Because Phase A is already closed, the two type graphs never coexist.
 *    Peak RSS = max(phaseA, phaseB) instead of phaseA + phaseB.
 */
class DecompilerService : Service() {

    companion object {
        const val MSG_LOAD     = 1
        const val MSG_PROGRESS = 2
        const val MSG_READY    = 3
        const val MSG_DONE     = 4
        const val MSG_CANCEL   = 5

        const val KEY_APK_PATHS = "apk_paths"
        const val KEY_CACHE_DIR = "cache_dir"
        const val KEY_CACHE_KEY = "cache_key"
        const val KEY_SUCCESS   = "success"
        const val KEY_ERROR     = "error"
        const val KEY_MSG_TEXT  = "msg_text"
    }

    @Volatile private var worker    : Thread? = null
    @Volatile private var cancelled : Boolean = false

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_LOAD   -> handleLoad(msg)
            MSG_CANCEL -> { cancelled = true; worker?.interrupt() }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = incoming.binder

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        worker?.interrupt()
    }

    private fun handleLoad(msg: Message) {
        val reply    = msg.replyTo ?: return
        val data     = msg.data    ?: return
        val apkPaths = data.getStringArray(KEY_APK_PATHS) ?: return
        val cacheDir = data.getString(KEY_CACHE_DIR)      ?: return
        val cacheKey = data.getString(KEY_CACHE_KEY)      ?: return

        cancelled = false
        worker?.interrupt()
        worker = Thread(null, {
            doWork(reply, apkPaths.map(::File), File(cacheDir), cacheKey)
        }, "decompiler-worker").also { it.isDaemon = true; it.start() }
    }

    // ── Worker ─────────────────────────────────────────────────────────────────

    private fun doWork(
        reply      : Messenger,
        apkFiles   : List<File>,
        appCacheDir: File,
        cacheKey   : String
    ) {
        val dCache = File(appCacheDir, "decompiled/$cacheKey").also { it.mkdirs() }
        val dexDir  = File(dCache, "dex").also { it.mkdirs() }

        fun send(what: Int, bundle: Bundle? = null) {
            if (cancelled) return
            try { reply.send(Message.obtain(null, what).also { it.data = bundle }) }
            catch (_: Exception) { }
        }
        fun progress(text: String) = send(MSG_PROGRESS,
            Bundle().apply { putString(KEY_MSG_TEXT, text) })
        fun done(ok: Boolean, err: String? = null) = send(MSG_DONE, Bundle().apply {
            putBoolean(KEY_SUCCESS, ok)
            if (err != null) putString(KEY_ERROR, err)
        })

        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

        try {
            // ── 1. DEX extraction ────────────────────────────────────────────
            val dexSentinel = File(dexDir, ".extracted")
            data class XmlEntry(val apkIdx: Int, val path: String)
            val xmlEntries = mutableListOf<XmlEntry>()
            val dexFiles: List<File>

            if (dexSentinel.exists()) {
                progress("Using cached DEX files…")
                dexFiles = dexDir.listFiles { f -> f.name.endsWith(".dex") }
                    ?.sortedBy { it.name } ?: emptyList()
                for ((idx, apk) in apkFiles.withIndex()) {
                    if (cancelled) return
                    try {
                        ZipFile(apk).use { zf ->
                            val entries = zf.entries()
                            while (entries.hasMoreElements()) {
                                val e = entries.nextElement()
                                if (!e.isDirectory && (
                                    e.name == "AndroidManifest.xml" ||
                                    (e.name.startsWith("res/") && e.name.endsWith(".xml"))
                                )) xmlEntries += XmlEntry(idx, e.name)
                            }
                        }
                    } catch (_: Exception) { }
                }
            } else {
                progress("Extracting APK files…")
                val extracted = mutableListOf<File>()
                for ((idx, apk) in apkFiles.withIndex()) {
                    if (cancelled) return
                    try {
                        ZipInputStream(apk.inputStream().buffered()).use { zip ->
                            var e = zip.nextEntry
                            while (e != null) {
                                if (!e.isDirectory) when {
                                    e.name.endsWith(".dex") -> {
                                        val f = File(dexDir, "${idx}_${e.name.replace('/', '_')}")
                                        f.outputStream().buffered().use { zip.copyTo(it) }
                                        extracted += f
                                    }
                                    e.name == "AndroidManifest.xml" ||
                                    (e.name.startsWith("res/") && e.name.endsWith(".xml")) ->
                                        xmlEntries += XmlEntry(idx, e.name)
                                }
                                zip.closeEntry()
                                e = zip.nextEntry
                            }
                        }
                    } catch (_: Exception) { }
                }
                dexSentinel.createNewFile()
                dexFiles = extracted
            }

            if (cancelled) return

            // ── 2. Checkpoint ────────────────────────────────────────────────
            val xmlsAllCached = xmlEntries.all { xmlCacheFile(dCache, it.path).exists() }
            val classlistFile = File(dCache, ".classlist")
            val classesAllCached = classlistFile.exists() &&
                classlistFile.readLines().filter { it.isNotBlank() }
                             .all { classFile(dCache, it).exists() }

            if (xmlsAllCached && classesAllCached) {
                progress("All files cached — skipping decompilation.")
                if (!File(dCache, ".complete").exists()) File(dCache, ".complete").createNewFile()
                send(MSG_READY)
                done(ok = true)
                return
            }

            // ── 3. Phase A: XML decoding with a minimal JADX instance ────────
            //
            // Load ONLY the base APK's DEX files (prefix "0_") so the type graph
            // is small.  isSkipResources=true keeps memory identical to Phase B —
            // BinaryXMLParser needs root to be non-null but decodes binary XML
            // from the file's own string pool, not from resources.arsc.
            //
            // Closing jadxXml before Phase B means the two type graphs never
            // coexist: peak RSS = max(phaseA, phaseB) instead of their sum.
            if (!xmlsAllCached) {
                val baseDex: List<File> = when {
                    dexFiles.isNotEmpty() ->
                        dexFiles.filter { it.name.startsWith("0_") }
                                .ifEmpty { listOf(dexFiles.first()) }
                    else ->
                        listOf(apkFiles.first())
                }

                progress("Preparing XML decoder…")
                val jadxXml = JadxDecompiler(JadxArgs().apply {
                    setInputFiles(baseDex)
                    isSkipResources = true  // no resource table needed for XML string-pool decoding
                    threadsCount    = 1     // single thread — smallest possible type-graph pressure
                    security        = JadxSecurity(JadxSecurityFlag.none())
                }).also { it.registerPlugin(DexInputPlugin()); it.load() }

                if (cancelled) { jadxXml.close(); return }

                progress("Decoding XML resources…")
                val xmlRoot  = jadxXml.root
                val xmlTotal = xmlEntries.size
                var xmlDone  = 0

                // Sequential loop — no thread pool.  Raw bytes + decoded string
                // for each entry live only for the duration of that iteration so
                // peak heap stays flat regardless of how many XML files the APK has.
                for ((apkIdx, entryPath) in xmlEntries) {
                    if (cancelled) break
                    val out = xmlCacheFile(dCache, entryPath)
                    if (out.exists()) { xmlDone++; continue }
                    val raw = readEntry(apkFiles[apkIdx], entryPath)
                        ?: run { xmlDone++; continue }
                    try {
                        val text = BinaryXMLParser(xmlRoot).parse(raw.inputStream())?.codeStr
                        if (text != null) {
                            out.parentFile?.mkdirs()
                            out.writeText(text)
                        }
                    } catch (_: Throwable) { /* catches OutOfMemoryError too */ }
                    xmlDone++
                    if (xmlTotal > 10 && xmlDone % 25 == 0)
                        progress("Decoding XML resources ($xmlDone / $xmlTotal)…")
                }

                jadxXml.close()  // free before Phase B — the two type graphs must not overlap
                System.gc()
            }

            if (cancelled) return

            // ── 4. Phase B: Full type graph + class decompilation ────────────
            if (!classesAllCached) {
                progress("Building type graph ($threads threads)…")
                val inputs = if (dexFiles.isNotEmpty()) dexFiles else apkFiles
                val jadx = JadxDecompiler(JadxArgs().apply {
                    setInputFiles(inputs)
                    isSkipResources = true
                    threadsCount    = threads
                    security        = JadxSecurity(JadxSecurityFlag.none())
                }).also { it.registerPlugin(DexInputPlugin()); it.load() }

                if (cancelled) { jadx.close(); return }

                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

                val all = jadx.classesWithInners.sortedBy { it.fullName }
                try { classlistFile.writeText(all.joinToString("\n") { it.fullName }) }
                catch (_: Exception) { }
                send(MSG_READY)   // class list on disk — unblock main process

                val batchPool = Executors.newFixedThreadPool(threads)
                for (cls in all) {
                    if (cancelled) break
                    val cf = classFile(dCache, cls.fullName)
                    if (cf.exists()) continue
                    batchPool.submit {
                        try {
                            if (cancelled) return@submit
                            val rt     = Runtime.getRuntime()
                            val freeMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1_048_576L
                            if (freeMb < 64) { Thread.sleep(500); System.gc() }
                            if (cancelled) return@submit
                            cf.writeText(cls.code ?: return@submit)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (_: Exception) { }
                    }
                }
                batchPool.shutdown()
                if (cancelled) batchPool.shutdownNow()
                batchPool.awaitTermination(30, TimeUnit.MINUTES)

                jadx.close()
                if (!cancelled) File(dCache, ".complete").createNewFile()
                done(ok = !cancelled)

            } else {
                // Phase A decoded the XMLs; class list was already cached.
                // Unblock the main process without a second JADX load.
                send(MSG_READY)
                if (!File(dCache, ".complete").exists()) File(dCache, ".complete").createNewFile()
                done(ok = true)
            }

        } catch (e: Exception) {
            done(ok = false, err = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── File-naming helpers (must match SourceRepository) ─────────────────────

    internal fun classFile(dir: File, className: String): File {
        val safe = className.replace('/', '_').replace('\\', '_')
        val name = if (safe.length <= 200) "$safe.java"
                   else "${safe.hashCode().toString(16)}.java"
        return File(dir, name)
    }

    internal fun xmlCacheFile(dir: File, entryPath: String): File =
        File(dir, entryPath.replace('/', '+') + ".decoded")

    /**
     * Reads a single entry from [apkFile] by name using central-directory lookup
     * (O(1)) instead of scanning from the start of the archive (O(n)).
     */
    private fun readEntry(apkFile: File, entryName: String): ByteArray? =
        try {
            ZipFile(apkFile).use { zf ->
                zf.getEntry(entryName)?.let { entry ->
                    zf.getInputStream(entry).use { it.readBytes() }
                }
            }
        } catch (_: Exception) { null }
}
