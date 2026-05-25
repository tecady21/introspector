package com.boyz.introspector.data.repository

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.boyz.introspector.DecompilerService
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// ── Resource types ─────────────────────────────────────────────────────────────

enum class ResourceCategory { MANIFEST, RES_XML, ASSETS, LIB, OTHER }

data class ResourceInfo(
    val name: String,
    val apkPath: String,
    val category: ResourceCategory
)

// ── Class types ────────────────────────────────────────────────────────────────

data class ClassInfo(
    val fullName: String,
    val simpleName: String,
    val packageName: String
)

// ── Cross-file search result ───────────────────────────────────────────────────

data class OccurrenceResult(
    val displayName: String,      // "Foo" for classes, "AndroidManifest.xml" for resources
    val lineNumber: Int,          // 1-based
    val lineText: String,         // trimmed snippet of the matching line
    val classInfo: ClassInfo?,    // non-null → navigate to class
    val resourceInfo: ResourceInfo? // non-null → navigate to resource
)

// ── Repository ─────────────────────────────────────────────────────────────────

/**
 * Cache layout under [cacheDir]/decompiled/<fingerprint>/:
 *
 *   dex/                   ← extracted DEX files, shared with the service
 *     .extracted            ← sentinel: ZIP scan + DEX extraction complete
 *     0_classes.dex, …
 *   .classlist              ← all class names (one per line), written by service
 *   .complete               ← sentinel: full batch done; service never restarts
 *   com.example.Foo.java    ← decompiled source, one per class
 *   AndroidManifest.xml+decoded
 *   res+layout+main.xml+decoded
 *
 * The DEX files and all output files persist across sessions.  On re-open:
 *  - If .complete → no service, no JADX, pure disk reads
 *  - If dex/.extracted → skip ZIP scan, reuse DEX; service checks class+XML
 *    coverage and skips j.load() if everything is already on disk
 *  - If neither → full first-run path
 */
class SourceRepository(private val app: Application) {

    companion object {
        private const val MAX_RESULTS = 2000
    }

    private val cacheDir: File get() = app.cacheDir

    @Volatile private var closed = false

    private var sourceApkFiles  : List<File>             = emptyList()
    private data class ResEntry(val apkIdx: Int, val entryName: String)
    private var resourceEntryMap: Map<String, ResEntry>  = emptyMap()
    private var allResources    : List<ResourceInfo>     = emptyList()
    private var decompileCache  : File?                  = null

    // ── Service IPC ────────────────────────────────────────────────────────────
    @Volatile private var batchDoneFuture : CompletableFuture<Unit>? = null
    @Volatile private var serviceConn     : ServiceConnection?       = null
    @Volatile private var serviceMessenger: Messenger?               = null

    // ── Loading ────────────────────────────────────────────────────────────────

    fun loadClasses(sourceFiles: List<File>, onProgress: ((String) -> Unit)? = null): List<ClassInfo> {
        if (sourceFiles.isEmpty()) throw IllegalArgumentException("No APK files found for this package")
        close()
        closed         = false
        sourceApkFiles = sourceFiles

        // ── Cache dirs ────────────────────────────────────────────────────────
        // Compute the fingerprint FIRST so we can reuse the persistent DEX dir.
        val cacheKey      = computeCacheKey(sourceFiles)
        val dCache        = File(cacheDir, "decompiled/$cacheKey").also { it.mkdirs() }
        decompileCache    = dCache
        val cacheComplete = File(dCache, ".complete").exists()

        // Persistent DEX dir — shared between main process (phase-1 parse) and
        // the :decompiler service (JADX input), so we only ever extract once.
        val dexDir      = File(dCache, "dex")
        val dexExtracted = File(dexDir, ".extracted").exists()

        // ── Resource + DEX enumeration ────────────────────────────────────────
        val entryMap = mutableMapOf<String, ResEntry>()
        val infoList = mutableListOf<ResourceInfo>()
        val dexFiles : List<File>

        if (dexExtracted) {
            // Fast path: DEX already on disk — only need resource metadata.
            // Use ZipFile (central-directory read) to avoid decompressing anything.
            onProgress?.invoke("Scanning resources…")
            for ((idx, apk) in sourceFiles.withIndex()) {
                try {
                    java.util.zip.ZipFile(apk).use { zf ->
                        val entries = zf.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            // Guard: skip paths already seen (split APKs each carry their own
                            // AndroidManifest.xml; only the base APK's copy is meaningful).
                            if (!e.isDirectory && isDisplayable(e.name) && !entryMap.containsKey(e.name)) {
                                entryMap[e.name] = ResEntry(idx, e.name)
                                infoList += ResourceInfo(
                                    name     = e.name.substringAfterLast('/'),
                                    apkPath  = e.name,
                                    category = categorize(e.name)
                                )
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            dexFiles = dexDir.listFiles { f -> f.name.endsWith(".dex") }
                ?.sortedBy { it.name } ?: emptyList()

        } else {
            // First run: extract DEX files and collect resource metadata in one pass.
            dexDir.mkdirs()
            val extracted = mutableListOf<File>()
            for ((idx, apk) in sourceFiles.withIndex()) {
                if (sourceFiles.size > 1) onProgress?.invoke("Extracting APK ${idx + 1} of ${sourceFiles.size}…")
                try {
                    ZipInputStream(apk.inputStream().buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            if (!e.isDirectory) when {
                                e.name.endsWith(".dex") -> {
                                    val out = File(dexDir, "${idx}_${e.name.replace('/', '_')}")
                                    out.outputStream().buffered().use { zip.copyTo(it) }
                                    extracted += out
                                }
                                // Guard: skip paths already seen (split APKs each carry
                                // their own AndroidManifest.xml; only base APK's copy counts).
                                isDisplayable(e.name) && !entryMap.containsKey(e.name) -> {
                                    entryMap[e.name] = ResEntry(idx, e.name)
                                    infoList += ResourceInfo(
                                        name     = e.name.substringAfterLast('/'),
                                        apkPath  = e.name,
                                        category = categorize(e.name)
                                    )
                                }
                            }
                            zip.closeEntry()
                            e = zip.nextEntry
                        }
                    }
                } catch (_: Exception) { }
            }
            File(dexDir, ".extracted").createNewFile()
            dexFiles = extracted
        }

        resourceEntryMap = entryMap
        allResources     = infoList

        // ── Parse class names from DEX headers (instant, no JADX) ────────────
        onProgress?.invoke("Parsing ${dexFiles.size} DEX file(s)…")
        val fastClasses = dexFiles.flatMap { DexParser.parseClassInfos(it) }.sortedBy { it.fullName }

        // ── Start service or skip if cache is complete ────────────────────────
        if (cacheComplete) {
            onProgress?.invoke("Loaded from cache (${fastClasses.size} classes).")
            if (fastClasses.isNotEmpty()) return fastClasses
            // CDEX/v41: no header parse result — read class list the service wrote
            return readClassList(dCache) ?: emptyList()
        }

        onProgress?.invoke("Starting decompiler service…")
        startService(sourceFiles, dCache, cacheKey, onProgress)

        return if (fastClasses.isNotEmpty()) {
            fastClasses
        } else {
            // CDEX/v41 fallback: the service writes .classlist before sending
            // MSG_READY, so startService() above already blocked until it exists.
            // Read it directly — no further waiting needed.
            readClassList(dCache) ?: emptyList()
        }
    }

    /**
     * Binds to [DecompilerService], sends MSG_LOAD, and blocks until the service
     * signals MSG_READY (binary XMLs decoded + class list usable) or fails.
     */
    private fun startService(
        sourceFiles: List<File>,
        dCache     : File,
        cacheKey   : String,
        onProgress : ((String) -> Unit)?
    ) {
        val readyLatch  = CountDownLatch(1)
        val batchFuture = CompletableFuture<Unit>()
        batchDoneFuture = batchFuture

        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                DecompilerService.MSG_PROGRESS ->
                    onProgress?.invoke(
                        msg.data?.getString(DecompilerService.KEY_MSG_TEXT) ?: return@Handler true
                    )
                DecompilerService.MSG_READY ->
                    readyLatch.countDown()
                DecompilerService.MSG_DONE -> {
                    val ok  = msg.data?.getBoolean(DecompilerService.KEY_SUCCESS) ?: false
                    val err = msg.data?.getString(DecompilerService.KEY_ERROR)
                    readyLatch.countDown()          // guard in case READY was never sent
                    if (ok) batchFuture.complete(Unit)
                    else    batchFuture.completeExceptionally(RuntimeException(err ?: "Decompiler failed"))
                }
            }
            true
        })

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val svc = Messenger(binder)
                serviceMessenger = svc
                try {
                    svc.send(Message.obtain(null, DecompilerService.MSG_LOAD).apply {
                        replyTo = replyMessenger
                        data = Bundle().apply {
                            putStringArray(
                                DecompilerService.KEY_APK_PATHS,
                                sourceFiles.map { it.absolutePath }.toTypedArray()
                            )
                            putString(DecompilerService.KEY_CACHE_DIR, cacheDir.absolutePath)
                            putString(DecompilerService.KEY_CACHE_KEY, cacheKey)
                        }
                    })
                } catch (e: Exception) {
                    readyLatch.countDown()
                    batchFuture.completeExceptionally(e)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                serviceMessenger = null
                // Unbind immediately so Android does NOT auto-restart the service
                // (BIND_AUTO_CREATE would otherwise restart a crashing/OOM-ing service
                // over and over, causing the repeated "Decoding XML…" loop the user sees).
                val c = serviceConn
                serviceConn = null
                if (c != null) try { app.unbindService(c) } catch (_: Exception) { }
                readyLatch.countDown()
                batchFuture.completeExceptionally(
                    RuntimeException("Decompiler process terminated (possibly OOM)")
                )
            }
        }
        serviceConn = conn

        val bound = app.bindService(
            Intent(app, DecompilerService::class.java), conn, Context.BIND_AUTO_CREATE
        )
        if (!bound) {
            readyLatch.countDown()
            batchFuture.completeExceptionally(RuntimeException("Could not start decompiler service"))
            return
        }

        readyLatch.await(10, TimeUnit.MINUTES)
    }

    // ── Decompile on demand ────────────────────────────────────────────────────

    fun decompileClass(className: String): String {
        val dCache = decompileCache ?: return "// Repository not initialised"
        val cf     = classFile(dCache, className)
        if (cf.exists()) return cf.readTextSafe() ?: "// Cache read error"

        // Batch in progress: poll until the service writes this specific file
        val future = batchDoneFuture
        if (future != null && !future.isDone) {
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            while (!future.isDone && System.currentTimeMillis() < deadline) {
                if (cf.exists()) return cf.readTextSafe() ?: "// Cache read error"
                Thread.sleep(300)
            }
            if (cf.exists()) return cf.readTextSafe() ?: "// Cache read error"
        }

        if (cf.exists()) return cf.readTextSafe() ?: "// Cache read error"

        return try {
            future?.get()
            "// Class not found: $className"
        } catch (e: java.util.concurrent.ExecutionException) {
            "// Decompiler failed: ${e.cause?.message ?: e.message}"
        } catch (_: Exception) {
            "// Decompiler was cancelled or timed out"
        }
    }

    // ── Resource access ────────────────────────────────────────────────────────

    fun getResources(): List<ResourceInfo> = allResources

    fun getResourceContent(apkPath: String): String {
        val entry = resourceEntryMap[apkPath] ?: return "// Resource not found: $apkPath"
        if (entry.entryName.startsWith("lib/")) return "// Binary native library — cannot display source"
        val apkFile = sourceApkFiles.getOrNull(entry.apkIdx) ?: return "// Source file not available"

        val isBinaryXml = entry.entryName == "AndroidManifest.xml" ||
                          (entry.entryName.startsWith("res/") && entry.entryName.endsWith(".xml"))
        if (isBinaryXml) {
            val dCache = decompileCache
            if (dCache != null) {
                val decoded = xmlCacheFile(dCache, entry.entryName)
                if (decoded.exists()) return decoded.readTextSafe() ?: "// Cache read error"
            }
            return "// XML not yet decoded — decompiler service is still running"
        }

        val bytes = readEntryBytes(apkFile, entry.entryName) ?: return "// Could not read resource from APK"
        return try {
            bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() } ?: "// Empty file"
        } catch (_: Exception) {
            "// Binary content (${bytes.size} bytes)"
        }
    }

    // ── Cross-file occurrence search ───────────────────────────────────────────

    /**
     * Searches every cached decompiled `.java` class file and every decoded
     * XML `.decoded` file for lines containing [query] (case-insensitive).
     *
     * Must be called on an IO thread. Results are capped at [MAX_RESULTS].
     */
    fun findAllOccurrences(query: String, classList: List<ClassInfo>): List<OccurrenceResult> {
        val dCache = decompileCache ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val lower   = query.lowercase()
        val results = mutableListOf<OccurrenceResult>()

        // Search decompiled class files
        for (cls in classList) {
            val file = classFile(dCache, cls.fullName)
            if (!file.exists()) continue
            try {
                file.readLines().forEachIndexed { idx, line ->
                    if (line.lowercase().contains(lower))
                        results += OccurrenceResult(cls.simpleName, idx + 1, line.trim(), cls, null)
                }
            } catch (_: Exception) { }
            if (results.size >= MAX_RESULTS) return results
        }

        // Search decoded XML resource files
        for (res in allResources) {
            val file = xmlCacheFile(dCache, res.apkPath)
            if (!file.exists()) continue
            try {
                file.readLines().forEachIndexed { idx, line ->
                    if (line.lowercase().contains(lower))
                        results += OccurrenceResult(res.name, idx + 1, line.trim(), null, res)
                }
            } catch (_: Exception) { }
            if (results.size >= MAX_RESULTS) return results
        }

        return results
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun close() {
        closed = true
        serviceMessenger?.let { svc ->
            try { svc.send(Message.obtain(null, DecompilerService.MSG_CANCEL)) } catch (_: Exception) { }
        }
        serviceConn?.let { try { app.unbindService(it) } catch (_: Exception) { } }
        serviceConn      = null
        serviceMessenger = null
        batchDoneFuture?.cancel(true)
        batchDoneFuture  = null
        sourceApkFiles   = emptyList()
        resourceEntryMap = emptyMap()
        allResources     = emptyList()
        // decompileCache points at persistent disk files — null the reference but keep files
        decompileCache   = null
    }

    // ── Disk-cache helpers (must mirror DecompilerService) ────────────────────

    private fun classFile(dir: File, className: String): File {
        val safe = className.replace('/', '_').replace('\\', '_')
        val name = if (safe.length <= 200) "$safe.java"
                   else "${safe.hashCode().toString(16)}.java"
        return File(dir, name)
    }

    private fun xmlCacheFile(dir: File, entryPath: String): File =
        File(dir, entryPath.replace('/', '+') + ".decoded")

    private fun readClassList(dCache: File): List<ClassInfo>? =
        try {
            File(dCache, ".classlist").readLines()
                .filter { it.isNotBlank() }
                .map { name ->
                    val dot = name.lastIndexOf('.')
                    ClassInfo(
                        fullName    = name,
                        simpleName  = if (dot >= 0) name.substring(dot + 1) else name,
                        packageName = if (dot >= 0) name.substring(0, dot)  else "(default)"
                    )
                }.sortedBy { it.fullName }
        } catch (_: Exception) { null }

    private fun File.readTextSafe(): String? =
        try { readText().takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    private fun computeCacheKey(sourceFiles: List<File>): String {
        var h = 1125899906842597L
        for (f in sourceFiles) { h = h * 31 + f.length(); h = h * 31 + f.lastModified() }
        return java.lang.Long.toUnsignedString(h, 16)
    }

    // ── ZIP helpers ────────────────────────────────────────────────────────────

    private fun readEntryBytes(apkFile: File, entryName: String): ByteArray? =
        try {
            ZipInputStream(apkFile.inputStream().buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == entryName) return zip.readBytes()
                    zip.closeEntry(); e = zip.nextEntry
                }
                null
            }
        } catch (_: Exception) { null }

    private fun isDisplayable(name: String): Boolean = when {
        name == "AndroidManifest.xml"                       -> true
        name.startsWith("res/")    && name.endsWith(".xml") -> true
        name.startsWith("res/")    && name.endsWith(".json")-> true
        name.startsWith("assets/")                          -> true
        name.startsWith("lib/")    && name.endsWith(".so")  -> true
        else                                                -> false
    }

    private fun categorize(name: String): ResourceCategory = when {
        name == "AndroidManifest.xml" -> ResourceCategory.MANIFEST
        name.startsWith("res/")       -> ResourceCategory.RES_XML
        name.startsWith("assets/")    -> ResourceCategory.ASSETS
        name.startsWith("lib/")       -> ResourceCategory.LIB
        else                          -> ResourceCategory.OTHER
    }
}
