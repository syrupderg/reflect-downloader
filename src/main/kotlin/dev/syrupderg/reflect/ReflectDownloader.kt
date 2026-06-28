@file:Suppress("Unused")

package dev.syrupderg.reflect

import android.util.Log
import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import app.revanced.manager.downloader.webview.runWebView
import dev.syrupderg.reflect.shared.CloudflareHelper
import dev.syrupderg.reflect.shared.Merger
import dev.syrupderg.reflect.R
import okhttp3.Request
import org.jsoup.Jsoup
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream

@OptIn(ExperimentalPathApi::class)
val ReflectDownloader = Downloader(R.string.reflect) {

    get { packageName, version ->
        var targetUrl: DownloadUrl? = null
        var resolvedVersion = version

        val searchQuery = version?.let { "$packageName $it" } ?: packageName
        val searchUrl = "https://www.apkmirror.com/?apk_files&s=" + java.net.URLEncoder.encode(searchQuery, "UTF-8")

        val baseActivity = SplitSelector.getCurrentActivity()
        val userAgent = CloudflareHelper.getUserAgent(baseActivity)

        var retryCount = 0
        var needCaptchaSolve = false
        val maxRetries = 2

        while (targetUrl == null && retryCount <= maxRetries) {
            try {
                if (needCaptchaSolve) {
                    val webViewResult = CloudflareHelper.solveCloudflare(baseActivity) {
                        runWebView("Solve Captcha") {
                            download { url, _, ua ->
                                finish(DownloadUrl(url, mapOf("User-Agent" to ua)))
                            }
                            searchUrl
                        }
                    }
                    if (webViewResult != null) {
                        targetUrl = webViewResult
                        break
                    }
                    needCaptchaSolve = false
                }

                Log.d("ReflectDownloader", "=== Starting headless scrape (Attempt ${retryCount + 1}) ===")
                var (currentUrl, html) = CloudflareHelper.fetchHtml(searchUrl, userAgent)
                var currentDoc = Jsoup.parse(html)
                
                if (currentUrl.contains("?apk_files") || currentUrl.contains("&s=")) {
                    val mainContent = currentDoc.selectFirst("#primary") ?: currentDoc.selectFirst(".listWidget") ?: currentDoc
                    val releaseHref = mainContent.selectFirst("a.infoLink[href*=-release/]")?.attr("href")
                        ?: mainContent.selectFirst(".appRow a[href*=-release/]")?.attr("href")
                        ?: throw Exception("No release link found in search results")
                    
                    val nextUrl = releaseHref.toAbsoluteApkMirrorUrl()
                    val res = CloudflareHelper.fetchHtml(nextUrl, userAgent, referer = currentUrl)
                    currentUrl = res.first
                    currentDoc = Jsoup.parse(res.second)
                }

                val variantRows = currentDoc.select(".variants-table .table-row:has(a.accent_color)")
                if (variantRows.isNotEmpty()) {
                    Log.d("ReflectDownloader", "[Step 4] Found ${variantRows.size} variants. Executing smart selection.")

                    val preferredArchs = listOf("universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                    val preferredDpis = listOf("nodpi", "xxhdpi", "xhdpi", "hdpi", "480dpi", "320dpi")

                    val targetRow = variantRows.maxByOrNull { row ->
                        var score = 0
                        val badges = row.select(".apkm-badge").map { it.text().trim().uppercase() }
                        val cells = row.select(".table-cell")

                        if (badges.contains("APK") && !badges.contains("BUNDLE")) score += 10000

                        if (cells.size >= 4) {
                            val archText = cells[1].text().trim().lowercase()
                            val dpiText = cells[3].text().trim().lowercase()

                            val archIndex = preferredArchs.indexOfFirst { archText.contains(it) }
                            if (archIndex != -1) score += (1000 - (archIndex * 100))

                            val dpiIndex = preferredDpis.indexOfFirst { dpiText.contains(it) }
                            if (dpiIndex != -1) score += (10 - dpiIndex)
                        }
                        score
                    }

                    targetRow?.select("a.accent_color")?.attr("href")?.takeIf { it.isNotBlank() }?.let { href ->
                        val variantUrl = href.toAbsoluteApkMirrorUrl()
                        Log.d("ReflectDownloader", "[Step 5] Navigating to optimal target variant: $variantUrl")
                        val res = CloudflareHelper.fetchHtml(variantUrl, userAgent, referer = currentUrl)
                        currentUrl = res.first
                        currentDoc = Jsoup.parse(res.second)
                    }
                }

                val downloadBtn = currentDoc.selectFirst(".downloadButton") ?: throw Exception("Download button not found")
                val intermediatePath = downloadBtn.attr("href").toAbsoluteApkMirrorUrl().substringBefore("&forcebaseapk")
                
                val doc2 = Jsoup.parse(CloudflareHelper.fetchHtml(intermediatePath, userAgent, referer = currentUrl).second)
                val fastLink = doc2.selectFirst("a[href*='/wp-content/themes/APKMirror/download.php']")
                    ?: doc2.selectFirst("a#download-link")
                    ?: throw Exception("Fast download link not found")

                val finalPath = fastLink.attr("href").toAbsoluteApkMirrorUrl()

                val finalReq = Request.Builder()
                    .url(finalPath)
                    .header("User-Agent", userAgent)
                    .header("Referer", intermediatePath)
                    .build()

                var trueDownloadUrl = finalPath
                CloudflareHelper.noRedirectClient.newCall(finalReq).execute().use { finalRes ->
                    if (finalRes.isRedirect) {
                        val loc = finalRes.header("Location")
                        if (loc != null) trueDownloadUrl = loc.toAbsoluteApkMirrorUrl()
                    }
                }
                
                val urlPath = try { java.net.URI(trueDownloadUrl).path.lowercase() } catch (e: Exception) { trueDownloadUrl.substringBefore("?").lowercase() }
                val isApkm = urlPath.endsWith(".apkm") || urlPath.endsWith(".xapk") || urlPath.endsWith(".apks")
                val formattedUrl = if (isApkm) "$trueDownloadUrl#type=apkm" else "$trueDownloadUrl#type=apk"
                
                targetUrl = DownloadUrl(formattedUrl, mapOf("User-Agent" to userAgent))
                break

            } catch (e: Exception) {
                Log.e("ReflectDownloader", "Scrape failed", e)
                if (e.message?.contains("CLOUDFLARE_BLOCK") == true) {
                    needCaptchaSolve = true
                    retryCount++
                } else {
                    Log.w("ReflectDownloader", "Headless scraping failed/App not found (${e.message}). Breaking to WebView fallback.")
                    break
                }
            }
        }

        if (targetUrl == null) {
            Log.d("ReflectDownloader", "Launching fallback WebView...")
            targetUrl = runWebView("APKMirror") {
                download { url, _, ua ->
                    finish(DownloadUrl(url, mapOf("User-Agent" to ua)))
                }
                searchUrl
            }
        }

        val finalTargetUrl = targetUrl ?: throw Exception("Could not find a download link for $packageName version $version")
        finalTargetUrl to resolvedVersion 
    }

    download { downloadUrl, outputStream ->
        val cleanUrl = downloadUrl.url.substringBefore("#")
        val parsedPath = try { java.net.URI(cleanUrl).path.lowercase() } catch (e: Exception) { "" }
        val isApk = downloadUrl.url.endsWith("#type=apk") || parsedPath.endsWith(".apk")
        
        if (isApk) {
            val (inputStream, size) = downloadUrl.toDownloadResult()
            inputStream.use { stream ->
                size?.let { reportSize(it) }
                stream.copyTo(outputStream, 128 * 1024)
            }
        } else {
            val workingPath = Files.createTempDirectory("reflect_dl")
            try {
                val downloadedZipPath = workingPath.resolve(UUID.randomUUID().toString())
                
                downloadedZipPath.outputStream().use { output ->
                    val (inputStream, _) = downloadUrl.toDownloadResult()
                    inputStream.use { stream ->
                        stream.copyTo(output, 128 * 1024)
                    }
                }
                
                val xapkWorkingPath = workingPath.resolve("xapk").also { it.toFile().mkdirs() }
                
                ZipFile(downloadedZipPath.toFile()).use { zip ->
                    val rawApkEntries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk") }
                        .map { it.name }
                        .toList()

                    val activity = SplitSelector.getCurrentActivity()
                        ?: throw Exception("Activity not found, cannot select splits.")
                        
                    val userSelectedSplits = SplitSelector.select(activity, rawApkEntries)
                    if (userSelectedSplits.isEmpty()) throw Exception("Split selection cancelled.")

                    userSelectedSplits.forEach { split ->
                        zip.getEntry(split)?.let { entry ->
                            val extractedApkPath = xapkWorkingPath.resolve(entry.name)
                            extractedApkPath.parent.toFile().mkdirs()
                            zip.getInputStream(entry).use { input -> 
                                Files.copy(input, extractedApkPath) 
                            }
                        }
                    }
                }
                Merger.mergeAndWrite(xapkWorkingPath, outputStream)
            } finally {
                runCatching { workingPath.deleteRecursively() }
            }
        }
    }
}

private fun String.toAbsoluteApkMirrorUrl(): String {
    val unescaped = this.replace("&amp;", "&")
    return if (unescaped.startsWith("http")) unescaped else "https://www.apkmirror.com$unescaped"
}