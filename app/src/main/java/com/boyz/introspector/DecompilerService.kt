package com.boyz.introspector

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.boyz.introspector.data.repository.DexParser
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
 * ── Why the old approach crashed ────────────────────────────────────────────
 *  JADX.load() performs cross-DEX type resolution and must hold the entire
 *  type graph for ALL input DEX files simultaneously.  For a large game APK
 *  (100–200 MB DEX), the graph is 400–800 MB — Android's LMK kills the process
 *  before MSG_READY is ever sent, leaving the main process hung or showing an
 *  error without a class list.
 *
 * ── Three-phase design — no phase loads all DEX at once ─────────────────────
 *
 *  Phase A – XML decoding
 *    BinaryXMLParser reads binary XML from the file's own embedded string pool.
 *    It calls root.getResources() for attribute-ID lookup, but that returns null
 *    with isSkipResources=true and it falls back gracefully.  We load JADX with
 *    one DEX file so JadxArgsValidator is satisfied; the type graph is irrelevant.
 *    One file keeps peak heap to ~50–100 MB regardless of app size.
 *
 *  Phase B – Class list (no JADX)
 *    DEX files use a well-documented binary header format.  We parse class names
 *    directly from the header via DexParser (same logic as SourceRepository).
 *    This is instant and uses negligible memory.  .classlist is written and
 *    MSG_READY is sent here so the main process unblocks and shows the class
 *    list without waiting for JADX at all.
 *    CDEX / compact-DEX files (magic ≠ "dex\n") return an empty list from
 *    DexParser; they are handled in Phase C via per-file JADX load.
 *
 *  Phase C – Per-DEX class decompilation
 *    Each DEX file is loaded into a fresh JADX instance, decompiled, then closed
 *    and GC'd before the next file is processed.  Peak memory is proportional to
 *    a single DEX file's type graph (typically 80–200 MB), not the whole app.
 *    Cross-DEX type references show placeholder types, but the code is readable.
 *    Files larger than 150 MB are skipped (a single extreme file would exhaust
 *    the heap even with per-DEX loading).  Java-heap OOM is caught per-file so
 *    one large DEX can be skipped without losing the rest.
 *
 * ── LMK immunity (root devices) ──────────────────────────────────────────────
 *  tryLmkImmunity() writes -1000 to /proc/<pid>/oom_score_adj, making Android's
 *  Low Memory Killer skip this process entirely.  Requires root; silently no-ops
 *  on non-rooted devices where the per-DEX heap strategy alone must suffice.
 *
 * ── Protocol (Messenger, no AIDL) ────────────────────────────────────────────
 *  Main → Service  [MSG_LOAD]     APK paths + cache root + cache key
 *  Main → Service  [MSG_CANCEL]   Abort current batch
 *  Service → Main  [MSG_PROGRESS] Human-readable status update
 *  Service → Main  [MSG_READY]    Class list on disk; main process unblocks
 *  Service → Main  [MSG_DONE]     All done (success=true) or failed
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

    // ── LMK immunity ──────────────────────────────────────────────────────────

    /**
     * Writes -1000 to this process's oom_score_adj so Android's Low Memory
     * Killer will never select it for termination.  Requires root; silently
     * no-ops on non-rooted devices.
     */
    private fun tryLmkImmunity() {
        val pid = android.os.Process.myPid()
        try {
            File("/proc/$pid/oom_score_adj").writeText("-1000")
        } catch (_: Exception) {
            try {
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "echo -1000 > /proc/$pid/oom_score_adj"))
                    .waitFor()
            } catch (_: Exception) { }
        }
    }

    // ── Worker ─────────────────────────────────────────────────────────────────

    private fun doWork(
        reply      : Messenger,
        apkFiles   : List<File>,
        appCacheDir: File,
        cacheKey   : String
    ) {
        tryLmkImmunity()

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

            // ── 3. Phase A: XML decoding ──────────────────────────────────────
            //
            // Load JADX with one DEX file so JadxArgsValidator is satisfied.
            // BinaryXMLParser only uses root.getResources() for attribute-ID
            // lookup, which returns null with isSkipResources=true and falls back
            // gracefully.  The type graph content is irrelevant; one file keeps
            // peak heap to ~50–100 MB regardless of app size.
            if (!xmlsAllCached) {
                progress("Decoding XML resources…")
                val phaseAInput = if (dexFiles.isNotEmpty()) listOf(dexFiles.first())
                                  else listOf(apkFiles.first())
                val jadxXml = JadxDecompiler(JadxArgs().apply {
                    setInputFiles(phaseAInput)
                    isSkipResources = true
                    threadsCount    = 1
                    security        = JadxSecurity(JadxSecurityFlag.none())
                }).also { it.registerPlugin(DexInputPlugin()); it.load() }

                if (cancelled) { jadxXml.close(); return }

                val xmlRoot  = jadxXml.root
                val xmlTotal = xmlEntries.size
                var xmlDone  = 0
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
                    } catch (_: Throwable) { }
                    xmlDone++
                    if (xmlTotal > 10 && xmlDone % 25 == 0)
                        progress("Decoding XML resources ($xmlDone / $xmlTotal)…")
                }
                jadxXml.close()
                System.gc()
            }

            if (cancelled) return

            // ── 4. Phase B: Class list from DEX headers — no JADX ────────────
            //
            // DexParser reads class names directly from the DEX binary header —
            // instant and negligible memory.  Writing .classlist here and sending
            // MSG_READY means the main process can show the class list immediately,
            // without waiting for JADX to finish decompiling.
            //
            // CDEX files (magic ≠ "dex\n") return empty from DexParser; they are
            // handled in Phase C via per-file JADX load.
            if (!classlistFile.exists()) {
                progress("Reading class list…")
                val allNames = dexFiles.flatMap { DexParser.parseClassNames(it) }.sorted()
                if (allNames.isNotEmpty()) {
                    try { classlistFile.writeText(allNames.joinToString("\n")) }
                    catch (_: Exception) { }
                }
            }
            send(MSG_READY)   // unblock main process — it uses fastClasses or .classlist

            // ── 5. Phase C: Per-DEX class decompilation ───────────────────────
            //
            // Process one DEX file at a time.  JADX is closed and GC'd between
            // files so the peak heap is proportional to a single file's type
            // graph (~80–200 MB) rather than the whole app (400–800 MB+).
            //
            // Files over 150 MB are skipped: a single extreme DEX would exhaust
            // the heap even with per-file loading, with no benefit since LMK
            // immunity from tryLmkImmunity() only prevents proactive SIGKILL —
            // Java-heap OutOfMemoryError is a separate failure mode.
            //
            // Java-heap OOM is caught per-file so one large DEX does not abort
            // the rest of the decompilation.
            if (!classesAllCached) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

                for ((fileIdx, dex) in dexFiles.withIndex()) {
                    if (cancelled) break

                    val sizeMb = dex.length() / 1_048_576L
                    if (sizeMb > 150) {
                        progress("Skipped ${dex.name}: ${sizeMb}MB exceeds safe per-file limit")
                        continue
                    }

                    val label = "${fileIdx + 1}/${dexFiles.size} (${dex.name})"
                    progress("Decompiling DEX $label…")

                    var jadx: JadxDecompiler? = null
                    try {
                        jadx = JadxDecompiler(JadxArgs().apply {
                            setInputFiles(listOf(dex))
                            isSkipResources = true
                            threadsCount    = threads
                            security        = JadxSecurity(JadxSecurityFlag.none())
                        }).also { it.registerPlugin(DexInputPlugin()); it.load() }

                        val classes = jadx.classesWithInners.sortedBy { it.fullName }
                        val pool = Executors.newFixedThreadPool(threads)
                        for (cls in classes) {
                            if (cancelled) break
                            val cf = classFile(dCache, cls.fullName)
                            if (cf.exists()) continue
                            pool.submit {
                                try {
                                    if (cancelled) return@submit
                                    val rt     = Runtime.getRuntime()
                                    val freeMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1_048_576L
                                    if (freeMb < 48) { Thread.sleep(300); System.gc() }
                                    if (cancelled) return@submit
                                    cf.writeText(cls.code ?: return@submit)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                } catch (_: Exception) { }
                            }
                        }
                        pool.shutdown()
                        pool.awaitTermination(15, TimeUnit.MINUTES)

                    } catch (e: Throwable) {
                        // Catches OutOfMemoryError for this one file — skip and continue.
                        progress("Skipped ${dex.name}: ${e.javaClass.simpleName}")
                    } finally {
                        try { jadx?.close() } catch (_: Throwable) { }
                        System.gc()
                    }
                }

                if (!cancelled) File(dCache, ".complete").createNewFile()
                done(ok = !cancelled)
            } else {
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
     * Reads one entry from [apkFile] via central-directory lookup (O(1)).
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
