package by.charge.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcmAuthTest {

    @Test
    fun candidatesFindKeysInOrderWithoutDuplicates() {
        val text = """
            Your apps
            Name: Charge HUD   API Key: 68bfccab-cb66-449f-9b0c-e4d8422990d1
            session 0f3b2c1a-1111-2222-3333-444455556666
            again 68bfccab-cb66-449f-9b0c-e4d8422990d1
        """.trimIndent()
        assertEquals(
            listOf("68bfccab-cb66-449f-9b0c-e4d8422990d1", "0f3b2c1a-1111-2222-3333-444455556666"),
            OcmAuth.candidates(text),
        )
    }

    @Test
    fun candidatesEmptyWhenNoGuid() {
        assertTrue(OcmAuth.candidates("Register An Application").isEmpty())
    }

    @Test
    fun loginAndAppsPagesAreRecognised() {
        assertTrue(OcmAuth.isLoginPage("https://openchargemap.org/loginprovider/beginlogin"))
        assertFalse(OcmAuth.isLoginPage("https://openchargemap.org/site/profile/applications"))
        assertTrue(OcmAuth.isAppsPage("https://openchargemap.org/site/profile/applications"))
    }

    @Test
    fun fillScriptEscapesCredentials() {
        val pass = "p\"a'ss\\w</script>"
        val js = OcmAuth.fillScript("user@example.com", pass)
        // Пароль вставляется как JSON-строка: кавычки и обратные слэши экранированы, скрипт не ломается
        assertTrue(js.contains("p.value = ${org.json.JSONObject.quote(pass)};"))
        assertTrue(js.contains("e.value = \"user@example.com\";"))
        assertTrue(js.contains("'submitted'"))
    }

    @Test
    fun validationUrlCarriesKey() {
        val url = OcmAuth.validationUrl("68bfccab-cb66-449f-9b0c-e4d8422990d1").toString()
        assertTrue(url.startsWith("https://api.openchargemap.io/v3/referencedata/"))
        assertTrue(url.contains("key=68bfccab-cb66-449f-9b0c-e4d8422990d1"))
        assertEquals(1, url.split("key=").size - 1)
    }
}
