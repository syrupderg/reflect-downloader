package dev.syrupderg.reflect.shared

import android.app.Activity
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.Toast
import dev.syrupderg.reflect.SplitSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object CloudflareHelper {

    private val webViewCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cookieManager = CookieManager.getInstance()
            for (cookie in cookies) {
                cookieManager.setCookie(url.toString(), cookie.toString())
            }
            cookieManager.flush()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieManager = CookieManager.getInstance()
            val cookiesStr = cookieManager.getCookie(url.toString())
            if (cookiesStr != null && cookiesStr.isNotEmpty()) {
                val cookieList = mutableListOf<Cookie>()
                for (pair in cookiesStr.split(";")) {
                    Cookie.parse(url, pair.trim())?.let { cookieList.add(it) }
                }
                return cookieList
            }
            return emptyList()
        }
    }

    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(webViewCookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val noRedirectClient: OkHttpClient by lazy {
        sharedClient.newBuilder().followRedirects(false).build()
    }

    fun getUserAgent(activity: Activity?): String {
        var userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
        if (activity != null) {
            try {
                userAgent = WebSettings.getDefaultUserAgent(activity)
            } catch (e: Exception) {}
        }
        return userAgent
    }

    fun fetchHtml(url: String, userAgent: String, referer: String? = null): Pair<String, String> {
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
            if (res.code == 403 || res.code == 503) throw Exception("CLOUDFLARE_BLOCK")
            if (!res.isSuccessful) throw Exception("HTTP ${res.code} from $url")
            
            val finalUrl = res.request.url.toString()
            val body = res.body?.string() ?: throw Exception("Empty HTML body")
            finalUrl to body
        }
    }

    private fun getClearanceCookie(): String? {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val cookies = cookieManager.getCookie("https://www.apkmirror.com") ?: return null
        return cookies.split(";").map { it.trim() }.firstOrNull { it.startsWith("cf_clearance=") }
    }

    suspend fun <T> solveCloudflare(
        activity: Activity?,
        launchWebView: suspend () -> T
    ): T? {
        Log.d("CloudflareHelper", "Triggering WebView for Cloudflare solve...")
        
        val oldClearance = getClearanceCookie()

        activity?.runOnUiThread {
            Toast.makeText(activity, "Cloudflare detected! Solve the Captcha. This window will close automatically.", Toast.LENGTH_LONG).show()
        }
        
        var solved = false
        var webViewResult: T? = null
        
        try {
            coroutineScope {
                val deferred = async { launchWebView() }

                val monitorJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        val currentClearance = getClearanceCookie()
                        
                        if (currentClearance != null && currentClearance != oldClearance) {
                            Log.d("CloudflareHelper", "New cf_clearance detected! Forcing sync and closing WebView...")
                            CookieManager.getInstance().flush() // Double ensure the flush happens
                            
                            withContext(Dispatchers.Main) {
                                val topActivity = SplitSelector.getCurrentActivity()
                                if (topActivity != null && topActivity !== activity) {
                                    topActivity.finish()
                                } else {
                                    Toast.makeText(activity, "Cloudflare solved! Please press BACK.", Toast.LENGTH_LONG).show()
                                }
                            }
                            solved = true
                            deferred.cancel()
                            break
                        }
                        delay(500)
                    }
                }

                webViewResult = deferred.await()
                monitorJob.cancel()
            }
        } catch (e: CancellationException) {
            if (solved) {
                Log.d("CloudflareHelper", "WebView closed automatically via cancellation. Resuming headless scraping...")
            } else {
                throw e 
            }
        } catch (e: Exception) {
            Log.d("CloudflareHelper", "WebView closed manually by user. Resuming headless scraping.")
        }
        
        return webViewResult
    }
}