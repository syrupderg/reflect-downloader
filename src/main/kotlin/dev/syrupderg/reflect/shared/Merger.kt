package dev.syrupderg.reflect.shared

import android.util.Log
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Path

object Merger {

    private object ArscLogger : APKLogger {
        const val TAG = "ARSCLib"
        override fun logMessage(msg: String) {
            if (msg.startsWith("Merging") || msg.startsWith("Writing") || msg.startsWith("Found")) {
                Log.i(TAG, msg)
            }
        }
        override fun logError(msg: String, tr: Throwable?) { Log.e(TAG, msg, tr) }
        override fun logVerbose(msg: String) {}
    }

    suspend fun mergeAndWrite(apkDir: Path, outputStream: OutputStream) {
        var bundle: ApkBundle? = null
        var merged: ApkModule? = null

        try {
            merged = withContext(Dispatchers.Default) {
                val localBundle = ApkBundle()
                bundle = localBundle
                localBundle.setAPKLogger(ArscLogger)
                localBundle.loadApkDirectory(apkDir.toFile())
                localBundle.mergeModules()
            }

            merged.androidManifest.apply {
                arrayOf(
                    AndroidManifest.ID_isSplitRequired,
                    AndroidManifest.ID_extractNativeLibs
                ).forEach {
                    applicationElement.removeAttributesWithId(it)
                    manifestElement.removeAttributesWithId(it)
                }

                arrayOf(
                    AndroidManifest.NAME_requiredSplitTypes,
                    AndroidManifest.NAME_splitTypes
                ).forEach {
                    manifestElement.removeAttributeIf { attribute -> attribute.name == it }
                }

                val pattern = "^com\\.android\\.(stamp|vending)\\.".toRegex()
                applicationElement.removeElementsIf { element ->
                    if (element.name != AndroidManifest.TAG_meta_data) return@removeElementsIf false
                    val nameAttr = element.getAttributes { it.nameId == AndroidManifest.ID_name }.asSequence().single()
                    pattern.containsMatchIn(nameAttr.valueString)
                }
                refresh()
            }

            withContext(Dispatchers.IO) {
                val tempApk = apkDir.resolve("temp_merged.apk").toFile()
                merged.writeApk(tempApk)

                tempApk.inputStream().use { input ->
                    input.copyTo(outputStream, bufferSize = 128 * 1024)
                }
            }
        } finally {
            try { merged?.close() } catch (e: Exception) {}
            try { bundle?.modules?.forEach { it.close() } } catch (e: Exception) {}
        }
    }
}