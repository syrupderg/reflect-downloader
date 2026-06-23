package dev.syrupderg.reflect

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

object SplitSelector {

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun getCurrentActivity(): Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(activityThread) as Map<*, *>

            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecord!!.javaClass
                val pausedField = activityRecordClass.getDeclaredField("paused").apply { isAccessible = true }
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity").apply { isAccessible = true }
                    return activityField.get(activityRecord) as Activity
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    suspend fun select(context: Context, rawApkEntries: List<String>): List<String> {
        val hasBaseApk = rawApkEntries.contains("base.apk")
        val splitsOnly = rawApkEntries.filter { it != "base.apk" }

        val abiSplits = splitsOnly.filter { it.contains("arm") || it.contains("x86") || it.contains("mips") }.sorted()
        val dpiSplits = splitsOnly.filter { it.contains("dpi") }.sorted()
        val otherSplits = splitsOnly.filter { !abiSplits.contains(it) && !dpiSplits.contains(it) }.sorted()

        val sortedApkEntries = abiSplits + dpiSplits + otherSplits
        val defaultSplits = mutableListOf<String>()

        val densityDpi = context.resources.displayMetrics.densityDpi
        val targetDpiString = when {
            densityDpi <= 120 -> "ldpi"
            densityDpi <= 160 -> "mdpi"
            densityDpi <= 213 -> "tvdpi"
            densityDpi <= 240 -> "hdpi"
            densityDpi <= 320 -> "xhdpi"
            densityDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }

        val dpiPreferences = listOf(targetDpiString, "xxhdpi", "xhdpi", "hdpi", "nodpi")
        for (pref in dpiPreferences) {
            val splitMatch = dpiSplits.firstOrNull { it.contains(pref) }
            if (splitMatch != null) { defaultSplits.add(splitMatch); break }
        }

        val supportedAbis = Build.SUPPORTED_ABIS.map { it.replace("-", "_") }
        for (abi in supportedAbis) {
            val abiSplit = abiSplits.firstOrNull { it.contains(abi) }
            if (abiSplit != null) { defaultSplits.add(abiSplit); break }
        }

        val deviceLanguage = Locale.getDefault().language
        otherSplits.firstOrNull { it.contains("config.$deviceLanguage") }?.let { defaultSplits.add(it) }
        if (deviceLanguage != "en") {
            otherSplits.firstOrNull { it.contains("config.en") }?.let { defaultSplits.add(it) }
        }

        val displayNames = sortedApkEntries.map { rawName ->
            val cleanName = rawName.substringBeforeLast(".apk")
                .removePrefix("split_config.")
                .removePrefix("config.")
                .removePrefix("split_")

            when {
                abiSplits.contains(rawName) -> "Arch: ${cleanName.replace("_", "-")}"
                dpiSplits.contains(rawName) -> {
                    val dpiNumber = when (cleanName) {
                        "ldpi" -> "(~120 dpi)"
                        "mdpi" -> "(~160 dpi)"
                        "tvdpi" -> "(~213 dpi)"
                        "hdpi" -> "(~240 dpi)"
                        "xhdpi" -> "(~320 dpi)"
                        "xxhdpi" -> "(~480 dpi)"
                        "xxxhdpi" -> "(~640 dpi)"
                        "nodpi" -> "(All Densities)"
                        else -> ""
                    }
                    "DPI: $cleanName $dpiNumber".trim()
                }
                else -> if (cleanName.length <= 3 || cleanName.contains("-") || cleanName.contains("+")) {
                    "Language: $cleanName"
                } else {
                    "Feature: $cleanName"
                }
            }
        }.toTypedArray()

        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val availableArray = sortedApkEntries.toTypedArray()
                val checkedItems = BooleanArray(availableArray.size) { i -> defaultSplits.contains(availableArray[i]) }

                AlertDialog.Builder(context)
                    .setTitle("Select App Components")
                    .setMultiChoiceItems(displayNames, checkedItems) { _, which, isChecked -> checkedItems[which] = isChecked }
                    .setPositiveButton("Confirm") { _, _ ->
                        val selected = availableArray.filterIndexed { index, _ -> checkedItems[index] }.toMutableList()
                        if (hasBaseApk) selected.add(0, "base.apk")
                        continuation.resume(selected)
                    }
                    .setNegativeButton("Cancel") { _, _ -> continuation.resume(emptyList()) }
                    .setOnCancelListener { continuation.resume(emptyList()) }
                    .setCancelable(false)
                    .show()
            }
        }
    }
}