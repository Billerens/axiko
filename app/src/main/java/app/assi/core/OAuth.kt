package app.assi.core

import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.assi.App
import app.assi.MainActivity
import app.assi.agent.Llm
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Signing in to services (Google, GitHub, anything that speaks OAuth 2) so that modules can use
 * their APIs. The owner signs in in the browser; the service sends the browser back to a
 * one-time address on this phone (`http://127.0.0.1:port/callback`), which is the redirect
 * every provider allows to installed apps. Tokens are kept in the vault and renewed when due.
 */
object OAuth {
    private class Pending(val config: JSONObject, val verifier: String, val state: String, val redirect: String, val server: ServerSocket) {
        val done = CountDownLatch(1)
        var error: String? = null
    }

    private val pending = HashMap<String, Pending>()
    private val random = SecureRandom()

    private fun token(bytes: Int): String =
        Base64.encodeToString(ByteArray(bytes).also { random.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Begins a sign-in and returns the address to open in a browser.
     * [config]: `{authUrl, tokenUrl, clientId, clientSecret?, scope, extra?: {param: value}}`.
     */
    fun start(name: String, config: JSONObject): String {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val redirect = "http://127.0.0.1:${server.localPort}/callback"
        val verifier = token(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val waiting = Pending(config, verifier, token(16), redirect, server)
        synchronized(pending) { pending.put(name, waiting)?.server?.close() }

        Thread({ listen(name, waiting) }, "assi-oauth").apply { isDaemon = true }.start()

        val address = Uri.parse(config.getString("authUrl")).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", config.getString("clientId"))
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("scope", config.optString("scope"))
            .appendQueryParameter("state", waiting.state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
        config.optJSONObject("extra")?.let { extra -> extra.keys().forEach { address.appendQueryParameter(it, extra.optString(it)) } }
        return address.build().toString()
    }

    /** Waits for the browser to come back, then trades the code for tokens. */
    private fun listen(name: String, waiting: Pending) {
        try {
            waiting.server.soTimeout = 10 * 60_000
            waiting.server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()   // GET /callback?code=…&state=… HTTP/1.1
                val query = line.substringAfter('?', "").substringBefore(' ')
                val values = query.split('&').filter { it.contains('=') }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val failure = when {
                    values["error"] != null -> "Сервис отказал во входе: ${values["error_description"] ?: values["error"]}"
                    values["state"] != waiting.state -> "Ответ сервиса не относится к этому входу"
                    values["code"].isNullOrBlank() -> "Сервис не прислал код входа"
                    else -> null
                }
                if (failure == null) {
                    runCatching { exchange(name, waiting, values.getValue("code")) }.onFailure { waiting.error = it.message }
                } else {
                    waiting.error = failure
                }
                val page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                    "<body style='font:17px system-ui;background:#101010;color:#ededed;padding:32px'>" +
                    (if (waiting.error == null) "Вход выполнен. Можно вернуться в Axiko." else "Не получилось: ${waiting.error}") + "</body>"
                val bytes = page.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        } catch (error: Exception) {
            if (waiting.error == null) waiting.error = "Вход не завершён: ${error.message}"
        } finally {
            runCatching { waiting.server.close() }
            synchronized(pending) { if (pending[name] === waiting) pending.remove(name) }
            waiting.done.countDown()
            // Back to the app from the browser.
            runCatching {
                App.instance.startActivity(Intent(App.instance, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun exchange(name: String, waiting: Pending, code: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", waiting.redirect).add("client_id", waiting.config.getString("clientId"))
            .add("code_verifier", waiting.verifier)
        waiting.config.optString("clientSecret").takeIf { it.isNotBlank() }?.let { form.add("client_secret", it) }
        save(name, waiting.config, request(waiting.config.getString("tokenUrl"), form.build()), null)
    }

    private fun request(url: String, form: FormBody): JSONObject {
        val call = Request.Builder().url(url).header("Accept", "application/json").post(form).build()
        Llm.client.newCall(call).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val answer = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful || answer == null || answer.optString("access_token").isBlank()) {
                error("Сервис не выдал доступ: " + (answer?.optString("error_description")?.ifBlank { answer.optString("error") } ?: text.take(200)))
            }
            return answer
        }
    }

    private fun save(name: String, config: JSONObject, answer: JSONObject, oldRefresh: String?) {
        val kept = JSONObject().put("config", config)
            .put("access", answer.getString("access_token"))
            .put("refresh", answer.optString("refresh_token").ifBlank { oldRefresh.orEmpty() })
            .put("expires", System.currentTimeMillis() + answer.optLong("expires_in", 3600) * 1000)
        Vault.set("oauth.$name", kept.toString())
    }

    /** Waits until the sign-in started with [start] is over; throws with the reason when it failed. */
    fun await(name: String, timeoutSeconds: Long = 600): Boolean {
        val waiting = synchronized(pending) { pending[name] } ?: return Vault.get("oauth.$name") != null
        if (!waiting.done.await(timeoutSeconds, TimeUnit.SECONDS)) error("Вход не завершён за отведённое время")
        waiting.error?.let { error(it) }
        return true
    }

    /** The whole sign-in: opens the browser and waits for the owner to finish there. */
    fun connect(name: String, config: JSONObject): Boolean {
        val address = start(name, config)
        App.instance.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return await(name)
    }

    /** A valid access token of that sign-in, renewed if it has run out. */
    fun token(name: String): String {
        val kept = JSONObject(Vault.get("oauth.$name") ?: error("Вход «$name» ещё не выполнен: вызовите oauth.connect"))
        if (kept.getLong("expires") - 60_000 > System.currentTimeMillis()) return kept.getString("access")
        val refresh = kept.optString("refresh")
        if (refresh.isBlank()) error("Доступ «$name» истёк, а обновить его нечем: войдите заново")
        val config = kept.getJSONObject("config")
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
            .add("client_id", config.getString("clientId"))
        config.optString("clientSecret").takeIf { it.isNotBlank() }?.let { form.add("client_secret", it) }
        val answer = request(config.getString("tokenUrl"), form.build())
        save(name, config, answer, refresh)
        return answer.getString("access_token")
    }

    fun names(): List<String> = Vault.names().filter { it.startsWith("oauth.") }.map { it.removePrefix("oauth.") }
}
