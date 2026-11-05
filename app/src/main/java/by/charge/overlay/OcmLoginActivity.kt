package by.charge.overlay

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import by.charge.overlay.data.OcmAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вход в Open Charge Map и получение API-ключа.
 * Страница входа OCM открыта во встроенном окне: логин и пароль заполняются на ней же.
 * После входа открывается список приложений аккаунта, ключ находится на странице,
 * пользователь выбирает нужный, и приложение проверяет его запросом к API.
 * Пароль хранится только в памяти этого экрана и очищается после отправки формы.
 */
class OcmLoginActivity : AppCompatActivity() {

    // LOGIN — страница входа: здесь заполняем поля. APPS — список приложений: здесь ищем ключ.
    private enum class Stage { LOGIN, APPS }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var btnFind: Button
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Логин и пароль живут только в полях этого экрана. Пароль очищается при переходе на APPS и в onDestroy.
    private var email: String? = null
    private var password: String? = null
    private var stage = Stage.LOGIN
    // Сколько раз пробовали автозаполнить форму. Больше MAX_AUTOFILL_ATTEMPTS — пользователь входит вручную.
    private var autofillAttempts = 0
    // Ошибка загрузки главного кадра: страница с ошибкой не должна считаться страницей входа
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocm_login)
        title = getString(R.string.ocm_title)

        web = findViewById(R.id.ocm_web)
        status = findViewById(R.id.ocm_status)
        btnFind = findViewById(R.id.btn_find_key)

        // Данные приходят из Intent. removeExtra убирает пароль из extras, чтобы он не остался в Intent.
        email = intent.getStringExtra(EXTRA_EMAIL)
        password = intent.getStringExtra(EXTRA_PASSWORD)
        intent.removeExtra(EXTRA_PASSWORD)

        // JavaScript нужен для заполнения формы и чтения текста страницы. DOM storage нужен сайту для сессии входа.
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Мост ChargeBridge.onText: JavaScript передаёт текст страницы в приложение.
        // ВНИМАНИЕ: мост доступен всем страницам этого WebView. Внешние адреса в коде не блокируются, поэтому WebView задуман только для OCM.
        // Если когда-нибудь появятся внешние ссылки, мост нужно ограничить проверкой адреса.
        web.addJavascriptInterface(Bridge(), "ChargeBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            // Новая загрузка страницы: сбрасываем признак ошибки предыдущей.
                loadFailed = false
            }

            // Редирект на http:// (он блокируется Android): переводим на https и открываем снова
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val upgraded = request?.url?.toString()?.let { OcmAuth.httpsUpgrade(it) } ?: return false
                view?.loadUrl(upgraded)
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            // Ошибка загрузки главного кадра (например, нет сети). Для http-адреса пробуем https, иначе показываем статус сети.
                val req = request ?: return
                if (!req.isForMainFrame) return
                loadFailed = true
                val upgraded = req.url?.toString()?.let { OcmAuth.httpsUpgrade(it) }
                if (upgraded != null) {
                    view?.loadUrl(upgraded)
                } else {
                    status.text = getString(R.string.ocm_status_network)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val current = url.orEmpty()
                // Страницы с ошибкой и незащищённые адреса обрабатывать не нужно
                if (loadFailed || current.startsWith("http://")) return
                onPage(current)
            }
        }

        // Кнопка «Найти ключ»: читает текст текущей страницы вручную, если автоматически ключ не нашёлся.
        btnFind.setOnClickListener {
            status.text = getString(R.string.ocm_status_searching)
            web.evaluateJavascript(OcmAuth.READ_TEXT_JS, null)
        }
        findViewById<Button>(R.id.btn_ocm_cancel).setOnClickListener { finish() }

        // Первый шаг: открываем страницу входа OCM. Дальше всё происходит в onPage после каждой загрузки.
        status.text = getString(R.string.ocm_status_login)
        web.loadUrl(OcmAuth.LOGIN_URL)
    }

    // Вызывается после загрузки каждой страницы. Решаем: заполнить форму входа, перейти к списку приложений или искать ключ.
    private fun onPage(url: String) {
        if (OcmAuth.isLoginPage(url)) {
            if (stage == Stage.APPS) {
                // Со страницы списка приложений нас вернули на вход: логин или пароль не подошли
                status.text = getString(R.string.ocm_status_failed)
                return
            }
            val e = email
            val p = password
            if (e != null && p != null && autofillAttempts < MAX_AUTOFILL_ATTEMPTS) {
                // Пароль остаётся в памяти до успешного входа: если страница перезагрузится, форма заполнится снова
                autofillAttempts++
                // Автозаполнение. JavaScript возвращает submitted, nofields или nosubmit; по ответу выбираем текст статуса.
                web.evaluateJavascript(OcmAuth.fillScript(e, p)) { result ->
                    status.text = if (result.contains("nofields") || result.contains("nosubmit")) {
                        getString(R.string.ocm_status_need_manual)
                    } else {
                        getString(R.string.ocm_status_login)
                    }
                }
            } else if (autofillAttempts > 0) {
                status.text = getString(R.string.ocm_status_failed)
            } else {
                status.text = getString(R.string.ocm_status_need_manual)
            }
            return
        }

        // Страница уже не входа, а мы были на этапе LOGIN: значит вход выполнен (автоматически или вручную в окне).
        // Переходим к списку приложений и стираем пароль из памяти.
        if (stage == Stage.LOGIN) {
            // Вход выполнен (автоматически или вручную): переходим к списку приложений.
            // Пароль больше не нужен, очищаем его из памяти
            stage = Stage.APPS
            email = null
            password = null
            status.text = getString(R.string.ocm_status_apps)
            web.loadUrl(OcmAuth.APPS_URL)
            return
        }
        // На списке приложений читаем весь текст страницы; дальше handleText ищет GUID-кандидаты.
        if (OcmAuth.isAppsPage(url)) {
            status.text = getString(R.string.ocm_status_searching)
            web.evaluateJavascript(OcmAuth.READ_TEXT_JS, null)
        }
    }

    // Объект, который JavaScript видит как ChargeBridge. Вызов из JavaScript идёт не в UI-потоке, поэтому переносим в runOnUiThread.
    /** Мост для JavaScript: страница отдаёт свой текст, приложение ищет в нём ключи. */
    private inner class Bridge {
        @JavascriptInterface
        fun onText(text: String) {
            runOnUiThread { handleText(text) }
        }
    }

    private fun handleText(text: String) {
        val found = OcmAuth.candidates(text)
        if (found.isEmpty()) {
            status.text = getString(R.string.ocm_status_no_key)
            return
        }
        // Всегда спрашиваем: GUID на странице может быть и не ключом приложения
        AlertDialog.Builder(this)
            .setTitle(R.string.ocm_pick_key)
            .setItems(found.toTypedArray()) { _, which -> confirmKey(found[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Проверка выбранного ключа запросом к API. При успехе: сохраняем ключ, чистим сессию WebView, возвращаем RESULT_OK.
    // MainActivity по RESULT_OK перезагрузит станции с ключом.
    private fun confirmKey(key: String) {
        status.text = getString(R.string.ocm_checking)
        scope.launch {
            // Сетевой запрос выполняется в IO-потоке, в UI только результат.
            val error: String? = withContext(Dispatchers.IO) {
                try {
                    OcmAuth.checkKey(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            // null означает, что ключ принят (см. OcmAuth.checkKey). Иначе показываем причину.
            if (error == null) {
                getSharedPreferences(OcmAuth.PREFS, MODE_PRIVATE).edit().putString(OcmAuth.PREF_KEY, key).apply()
                clearWebSession()
                setResult(RESULT_OK)
                Toast.makeText(this@OcmLoginActivity, R.string.ocm_key_saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                status.text = getString(R.string.ocm_key_rejected, error)
            }
        }
    }

    /** Сессия OCM не нужна после получения ключа: чистим cookie и локальное хранилище WebView. */
    private fun clearWebSession() {
        // Сессия OCM после получения ключа не нужна: удаляем cookie и локальное хранилище WebView.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    override fun onDestroy() {
    // Пароль стираем даже если окно закрыли до входа. WebView уничтожаем, чтобы не держать сессию в памяти.
        email = null
        password = null
        scope.cancel()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        // Имена extras, которые MainActivity передаёт через intent(...).
        const val EXTRA_EMAIL = "ocm_email"
        const val EXTRA_PASSWORD = "ocm_password"
        // Больше двух попыток не делаем: при неверном пароле сайт возвращает на вход, и автозаполнение зациклилось бы.
        private const val MAX_AUTOFILL_ATTEMPTS = 2

        // Intent для запуска окна входа. Логин и пароль передаются в extras, а не сохраняются.
        fun intent(context: android.content.Context, email: String, password: String): Intent =
            Intent(context, OcmLoginActivity::class.java)
                .putExtra(EXTRA_EMAIL, email)
                .putExtra(EXTRA_PASSWORD, password)
    }
}
