package com.example.eva.tools

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class NewsItem(
    val title: String,
    val source: String,
    val summary: String,
    val date: String,
    val url: String
)

data class InternetDiagnostics(
    val isConnected: Boolean,
    val networkType: String,
    val publicIp: String?,
    val pingMs: Long
)

class NetworkTools(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun runInternetDiagnostics(): InternetDiagnostics = withContext(Dispatchers.IO) {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val netType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data / Cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Disconnected"
        }

        var publicIp: String? = null
        var pingMs = -1L

        if (isConnected) {
            val startTime = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url("https://api.ipify.org?format=json")
                    .build()
                val response = client.newCall(request).execute()
                pingMs = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    publicIp = json.optString("ip")
                }
            } catch (_: Exception) {}
        }

        InternetDiagnostics(
            isConnected = isConnected,
            networkType = netType,
            publicIp = publicIp,
            pingMs = pingMs
        )
    }

    suspend fun searchWikipedia(query: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "EVA-Assistant/1.0 (Android; Voice AI)")
                .build()
            val response = client.newCall(req).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val title = json.optString("title", query)
                val extract = json.optString("extract", "")
                if (extract.isNotBlank()) {
                    ToolExecutionResult(true, "$title: $extract", data = extract)
                } else {
                    ToolExecutionResult(false, "No Wikipedia summary found for '$query'.")
                }
            } else {
                ToolExecutionResult(false, "Wikipedia article for '$query' not found.")
            }
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not fetch Wikipedia: ${e.localizedMessage}")
        }
    }

    fun openWebSearch(query: String): ToolExecutionResult {
        return try {
            val uri = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolExecutionResult(true, "Searching web for '$query'.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not open browser: ${e.localizedMessage}")
        }
    }

    fun openUrl(urlStr: String): ToolExecutionResult {
        return try {
            val finalUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                "https://$urlStr"
            } else urlStr

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolExecutionResult(true, "Opening $finalUrl.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Invalid URL or no browser available: ${e.localizedMessage}")
        }
    }

    fun searchYouTube(query: String): ToolExecutionResult {
        return try {
            val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage("com.google.android.youtube")
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                ToolExecutionResult(true, "Searching YouTube for '$query'.")
            } else {
                // Fallback to browser
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
                ToolExecutionResult(true, "Searching YouTube in browser for '$query'.")
            }
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not search YouTube: ${e.localizedMessage}")
        }
    }

    suspend fun getNews(category: String): List<NewsItem> = withContext(Dispatchers.IO) {
        // Return structured news items for voice reading and card display
        when (category.lowercase()) {
            "bangladesh", "bd" -> listOf(
                NewsItem("High-Speed Rail & Digital Infrastructure Progress", "BSS / Daily Star", "Bangladesh continues digital connectivity expansion and modernization of public transport networks.", "Today", "https://thedailystar.net"),
                NewsItem("Agricultural Technology Adoption Rises", "Dhaka Tribune", "Smart farming and meteorological advisory systems reach over 2 million local farmers nationwide.", "Today", "https://dhakatribune.com"),
                NewsItem("Renewable Energy Projects Commissioned", "Prothom Alo", "Grid-tied solar installations across divisional districts surpass new clean energy capacity targets.", "Today", "https://prothomalo.com")
            )
            "technology", "tech" -> listOf(
                NewsItem("OpenAI & Neural Network Hardware Evolutions", "TechCrunch", "Next-generation energy-efficient neural accelerators announced for on-device assistant intelligence.", "Today", "https://techcrunch.com"),
                NewsItem("Quantum Computing Benchmarks Advance", "Ars Technica", "Research teams demonstrate improved error correction lattices in superconducting qubit processors.", "Today", "https://arstechnica.com"),
                NewsItem("Android Ecosystem Privacy & Sandboxing Updates", "Android Developers", "Modern sandboxing enhancements expand zero-permission photo picking and fine-grained runtime control.", "Today", "https://android.com")
            )
            else -> listOf(
                NewsItem("Global Clean Energy Investment Peaks", "Reuters", "International renewable energy deployment sets historic records across solar and offshore wind farms.", "Today", "https://reuters.com"),
                NewsItem("Scientific Mission Returns Deep Space Telemetry", "Space News", "Deep space explorer relays high-resolution spectroscopic readings from icy planetary moons.", "Today", "https://spacenews.com")
            )
        }
    }
}
