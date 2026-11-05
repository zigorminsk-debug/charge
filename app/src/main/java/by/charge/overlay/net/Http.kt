package by.charge.overlay.net

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    // User-Agent обязателен для Nominatim и полезен для OSM-серверов: без него запросы могут отклоняться.
    private const val USER_AGENT = "ChargeHUD/1.0 (Android; +https://github.com/zigorminsk-debug/charge)"

    // Один общий клиент на приложение. Таймауты: соединение 10 с, чтение 25 с, весь вызов 30 с.
    // Overpass иногда отвечает долго, поэтому чтение ждём 25 с.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
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

    // POST с формой (application/x-www-form-urlencoded). Нужен Overpass: тело запроса передаётся как data=...
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

    // Любой ответ не 2xx — IOException с кодом и хостом. Пустое тело — тоже IOException.
    // Всё это ловят вызывающие: они решают, показывать ошибку или пропустить источник.
    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} от ${request.url.host}")
            response.body?.string() ?: throw IOException("пустой ответ от ${request.url.host}")
        }
}
