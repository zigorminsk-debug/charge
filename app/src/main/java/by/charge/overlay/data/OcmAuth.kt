package by.charge.overlay.data

import by.charge.overlay.net.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Получение API-ключа Open Charge Map из приложения.
 *
 * Официального API для создания ключа нет: ключ создаётся в личном кабинете на openchargemap.org
 * (My Profile → My Apps). Поэтому приложение открывает страницу входа OCM во встроенном окне,
 * вводит логин и пароль на ней и читает ключ со страницы списка приложений аккаунта.
 * Пароль не сохраняется; на сервер OCM он уходит только через его собственную форму входа.
 */
object OcmAuth {
    const val PREFS = "settings"
    const val PREF_KEY = "ocm_api_key"

    const val LOGIN_URL = "https://openchargemap.org/loginprovider/beginlogin"
    const val APPS_URL = "https://openchargemap.org/site/profile/applications"

    private const val LOGIN_MARK = "loginprovider"
    private const val APPS_MARK = "/site/profile/applications"

    private val GUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun isLoginPage(url: String): Boolean = url.contains(LOGIN_MARK, ignoreCase = true)

    fun isAppsPage(url: String): Boolean = url.contains(APPS_MARK)

    /** Кандидаты ключа из текста страницы: GUID в порядке появления, без повторов. */
    fun candidates(text: String): List<String> =
        GUID.findAll(text).map { it.value }.distinct().toList()

    /**
     * JavaScript для страницы входа: заполняет поля e-mail и пароля и нажимает «Sign In».
     * Строки передаются через JSONObject.quote, поэтому кавычки и спецсимволы не ломают скрипт.
     * Возвращает 'submitted', 'nofields' или 'nosubmit'.
     */
    fun fillScript(email: String, password: String): String = """
        (function () {
          var e = document.querySelector('input[type=email], input[name*=mail i], input[id*=mail i], input[name*=user i]');
          var p = document.querySelector('input[type=password]');
          if (!e || !p) return 'nofields';
          e.value = ${JSONObject.quote(email)};
          p.value = ${JSONObject.quote(password)};
          e.dispatchEvent(new Event('input', {bubbles: true}));
          p.dispatchEvent(new Event('input', {bubbles: true}));
          var items = Array.prototype.slice.call(document.querySelectorAll('button, input[type=submit], a'));
          var btn = items.find(function (b) { return /sign\s*in|войти/i.test(b.value || b.textContent || ''); });
          if (btn) { btn.click(); return 'submitted'; }
          if (p.form) { p.form.submit(); return 'submitted'; }
          return 'nosubmit';
        })();
    """.trimIndent()

    /** JavaScript: отдаёт весь видимый текст страницы в нативный мост ChargeBridge.onText. */
    const val READ_TEXT_JS =
        "(function(){ ChargeBridge.onText(document.body ? document.body.innerText : ''); })();"

    /**
     * Если адрес — незащищённая ссылка на openchargemap.org, возвращает ту же ссылку на https.
     * Открытый HTTP для входа недопустим: пароль и cookie сессии ушли бы без шифрования.
     * Для любых других адресов возвращает null.
     */
    fun httpsUpgrade(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" || parsed.host != "openchargemap.org") return null
        return parsed.newBuilder().scheme("https").build().toString()
    }

    /** Адрес для проверки ключа: справочник OCM отвечает только при верном ключе. */
    fun validationUrl(key: String): HttpUrl =
        "https://api.openchargemap.io/v3/referencedata/".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .build()

    /** Проверка ключа запросом к API. Возвращает null, если ключ принят, иначе текст ошибки. */
    fun checkKey(key: String): String? = try {
        Http.get(validationUrl(key))
        null
    } catch (e: java.io.IOException) {
        e.message ?: "ошибка сети"
    }
}
