@file:Suppress("Unused")

package dev.syrupderg.reflect

import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import dev.syrupderg.reflect.shared.Merger
import dev.syrupderg.reflect.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream

private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
private val cookieJar = object : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val list = cookieStore[host] ?: mutableListOf()
        val newNames = cookies.map { it.name }
        list.removeAll { it.name in newNames }
        list.addAll(cookies)
        cookieStore[host] = list
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore[url.host] ?: emptyList()
    }
}

private val sharedClient by lazy {
    OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private val noRedirectClient by lazy {
    sharedClient.newBuilder().followRedirects(false).build()
}

@OptIn(ExperimentalPathApi::class, DelicateCoroutinesApi::class)
val ReflectDownloader = Downloader(R.string.reflect) {

    get { packageName, version ->
        var targetUrl: String? = null
        var resolvedVersion = version
        val isAnyVersion = version.equals("Any", ignoreCase = true) || version.isNullOrBlank()

        try {
                val apiUrl = android.net.Uri.Builder()
                    .scheme("https")
                    .authority("apkmirror-api.syrupderg.workers.dev")
                    .appendPath("search")
                    .appendQueryParameter("package", packageName)
                    .apply { if (!isAnyVersion) appendQueryParameter("version", version) }
                    .toString()

                val request = Request.Builder().url(apiUrl).build()
                sharedClient.newCall(request).execute().use { response ->
                    if (response.code == 404) throw Exception("App not found in API")
                    if (!response.isSuccessful) throw Exception("API returned HTTP ${response.code}")
                    val body = response.body?.string() ?: throw Exception("Empty API response")
                    val json = org.json.JSONObject(body)
                    if (!json.has("url")) throw Exception("No URL in JSON")
                    val initialTargetUrl = json.getString("url")
                    
                    val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.164 Mobile Safari/537.36"
                    fun fetchHtml(url: String, referer: String? = null): String {
                        val reqBuilder = Request.Builder()
                            .url(url)
                            .header("User-Agent", userAgent)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Upgrade-Insecure-Requests", "1")
                            .header("Sec-Fetch-Dest", "document")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Site", if (referer != null) "same-origin" else "none")
                        if (referer != null) reqBuilder.header("Referer", referer)
                        return sharedClient.newCall(reqBuilder.build()).execute().use { res ->
                            res.body?.string() ?: throw Exception("Empty HTML body")
                        }
                    }

                    val html1 = fetchHtml(initialTargetUrl)
                    val doc1 = Jsoup.parse(html1)
                    val downloadBtn = doc1.selectFirst(".downloadButton") ?: throw Exception("Step 1 Failed")
                    var intermediatePath = downloadBtn.attr("href").replace("&amp;", "&")
                    if (!intermediatePath.startsWith("http")) intermediatePath = "https://www.apkmirror.com$intermediatePath"
                    intermediatePath = intermediatePath.substringBefore("&forcebaseapk")

                    val html2 = fetchHtml(intermediatePath, referer = initialTargetUrl)
                    val doc2 = Jsoup.parse(html2)
                    val fastLink = doc2.selectFirst("a[href*='/wp-content/themes/APKMirror/download.php']")
                        ?: doc2.selectFirst("a#download-link")
                        ?: throw Exception("Step 2 Failed")

                    var finalPath = fastLink.attr("href").replace("&amp;", "&")
                    if (!finalPath.startsWith("http")) finalPath = "https://www.apkmirror.com$finalPath"

                    val finalReq = Request.Builder()
                        .url(finalPath)
                        .header("User-Agent", userAgent)
                        .header("Referer", intermediatePath)
                        .build()

                    var trueDownloadUrl = finalPath
                    noRedirectClient.newCall(finalReq).execute().use { finalRes ->
                        if (finalRes.isRedirect) {
                            val loc = finalRes.header("Location")
                            if (loc != null) trueDownloadUrl = if (loc.startsWith("http")) loc else "https://www.apkmirror.com$loc"
                        }
                    }

                    val urlPath = try { java.net.URI(trueDownloadUrl).path.lowercase() } catch (e: Exception) { trueDownloadUrl.substringBefore("?").lowercase() }
                    val isApkm = urlPath.endsWith(".apkm") || urlPath.endsWith(".xapk") || urlPath.endsWith(".apks")
                    
                    targetUrl = if (isApkm) {
                        "$trueDownloadUrl#type=apkm"
                    } else {
                        "$trueDownloadUrl#type=apk"
                    }
                }
            } catch (e: Exception) {
                // Ignore API failure, targetUrl will remain null
            }

        if (targetUrl == null) throw Exception("Could not find a download link for $packageName version $version")
        DownloadUrl(targetUrl!!) to resolvedVersion
    }

    download { downloadUrl, outputStream ->
        val isApk = downloadUrl.url.endsWith("#type=apk")
        if (isApk) {
            val (inputStream, size) = downloadUrl.toDownloadResult()
            inputStream.use { stream ->
                if (size != null) reportSize(size)
                val buffer = ByteArray(64 * 1024)
                var bytes = stream.read(buffer)
                while (bytes >= 0) {
                    outputStream.write(buffer, 0, bytes)
                    bytes = stream.read(buffer)
                }
            }
        } else {
            val workingDir: Path = Files.createTempDirectory("reflect_dl")
            try {
                val downloadedFile: Path = workingDir.resolve(UUID.randomUUID().toString()).also { file ->
                    file.outputStream().use { output ->
                        val (inputStream, size) = downloadUrl.toDownloadResult()
                        inputStream.use { stream ->
                            if (size != null) reportSize(size)
                            val buffer = ByteArray(64 * 1024)
                            var bytes = stream.read(buffer)
                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytes = stream.read(buffer)
                            }
                        }
                    }
                }
                val xapkWorkingDir: Path = workingDir.resolve("xapk").also { it.toFile().mkdirs() }
                ZipFile(downloadedFile.toFile()).use { zip ->
                    val rawApkEntries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk") }
                        .map { it.name }
                        .toList()

                    val activity = SplitSelector.getCurrentActivity()
                    if (activity == null) throw Exception("Activity not found, cannot select splits.")
                    val userSelectedSplits = SplitSelector.select(activity, rawApkEntries)
                    if (userSelectedSplits.isEmpty()) throw Exception("Split selection cancelled.")

                    for (split in userSelectedSplits) {
                        val entry = zip.getEntry(split)
                        if (entry != null) {
                            val outputFile = xapkWorkingDir.resolve(entry.name)
                            outputFile.parent.toFile().mkdirs()
                            zip.getInputStream(entry).use { input -> Files.copy(input, outputFile) }
                        }
                    }
                }
                Merger.mergeAndWrite(xapkWorkingDir, outputStream)
            } finally {
                GlobalScope.launch(Dispatchers.IO) {
                    try { workingDir.deleteRecursively() } catch (e: Exception) {}
                }
            }
        }
    }
}