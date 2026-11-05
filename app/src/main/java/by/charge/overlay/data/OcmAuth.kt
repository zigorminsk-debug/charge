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
    // Хранилище ключа: SharedPreferences «settings», поле ocm_api_key.
    // Логин и пароль нигде не сохраняются; ключ остаётся на устройстве.
    const val PREFS = "settings"
    const val PREF_KEY = "ocm_api_key"

    // Адреса страниц OCM. Если сайт поменяет адреса входа или списка приложений, менять здесь.
    const val LOGIN_URL = "https://openchargemap.org/loginprovider/beginlogin"
    const val APPS_URL = "https://openchargemap.org/site/profile/applications"

    // Признаки страниц по адресу: страница входа содержит «loginprovider»; список приложений — «/site/profile/applications».
    private const val LOGIN_MARK = "loginprovider"
    private const val APPS_MARK = "/site/profile/applications"

    // Ключ OCM выглядит как GUID (8-4-4-4-12 шестнадцатеричных символов). Это эвристика:
    // на странице может быть и другой GUID, поэтому пользователь всегда выбирает ключ сам.
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
          var p = document.querySelector('input[type=password]');
          if (!p) return 'nofields';
          // Работаем только внутри формы входа: в шапке сайта есть ссылки «Sign In», которые не отправляют форму
          var scope = p.form || document;
          var e = scope.querySelector('input[type=email], input[name*=mail i], input[id*=mail i], input[name*=user i]');
          if (!e) return 'nofields';
          e.value = ${JSONObject.quote(email)};
          p.value = ${JSONObject.quote(password)};
          e.dispatchEvent(new Event('input', {bubbles: true}));
          p.dispatchEvent(new Event('input', {bubbles: true}));
          // Только кнопки и submit-элементы, без ссылок
          var buttons = Array.prototype.slice.call(scope.querySelectorAll('button, input[type=submit]'));
          var btn = buttons.find(function (b) { return /sign\s*in|log\s*in|войти/i.test(b.value || b.textContent || ''); })
            || buttons.find(function (b) { return (b.type || '').toLowerCase() === 'submit'; });
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
    // Возвращает null для всех адресов, кроме http://openchargemap.org: их не трогаем.
        val parsed = url.toHttpUrlOrNull() ?: return null
        // Некорректный адрес тоже даёт null: лучше ничего не открывать, чем открыть неизвестно что.
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
    // Проверка ключа: успешный ответ referencedata означает, что ключ принят.
    // Ошибка HTTP (например 403) и сетевые ошибки — IOException из Http.get; их текст возвращается пользователю.
        Http.get(validationUrl(key))
        null
    } catch (e: java.io.IOException) {
        e.message ?: "ошибка сети"
    }
}
