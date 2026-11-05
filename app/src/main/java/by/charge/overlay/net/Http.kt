package by.charge.overlay.net

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    private const val USER_AGENT = "ChargeHUD/1.0 (Android; +https://github.com/zigorminsk-debug/charge)"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun get(url: HttpUrl): String =
        execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
        )

    fun postForm(url: String, fields: Map<String, String>): String {
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        return execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()
        )
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} от ${request.url.host}")
            response.body?.string() ?: throw IOException("пустой ответ от ${request.url.host}")
        }
}
