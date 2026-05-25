package com.boyz.introspector.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.boyz.introspector.data.model.InstalledApp
import java.io.File

class AppRepository(private val context: Context) {

    fun getInstalledUserApps(): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { info ->
                InstalledApp(
                    name = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    sourceDir = info.sourceDir
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun getAllInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { info ->
                InstalledApp(
                    name = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    sourceDir = info.sourceDir
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun getSourceDir(packageName: String): String {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0).sourceDir
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /** Returns the base APK plus any split APKs for the given package. */
    fun getSourceFiles(packageName: String): List<File> {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            val files = mutableListOf(File(ai.sourceDir))
            ai.splitSourceDirs?.mapTo(files) { File(it) }
            files
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }
}
