package com.boyz.introspector.data.repository

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File

data class ClassInfo(
    val fullName: String,
    val simpleName: String,
    val packageName: String
)

class SourceRepository {

    private var jadx: JadxDecompiler? = null

    fun loadClasses(sourceDir: String): List<ClassInfo> {
        close()
        val args = JadxArgs().apply {
            setInputFile(File(sourceDir))
            isSkipResources = true
            threadsCount = 1
        }
        val j = JadxDecompiler(args)
        j.load()
        jadx = j
        return j.classes
            .map { cls ->
                val dot = cls.fullName.lastIndexOf('.')
                ClassInfo(
                    fullName = cls.fullName,
                    simpleName = if (dot >= 0) cls.fullName.substring(dot + 1) else cls.fullName,
                    packageName = if (dot >= 0) cls.fullName.substring(0, dot) else "(default)"
                )
            }
            .sortedBy { it.fullName }
    }

    fun decompileClass(className: String): String {
        val cls = jadx?.classes?.find { it.fullName == className }
            ?: return "// Class not found: $className"
        return try {
            cls.code ?: "// No source for $className"
        } catch (e: Exception) {
            "// Decompilation error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun close() {
        jadx?.close()
        jadx = null
    }
}
