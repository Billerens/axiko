package app.assi.agent

import app.assi.App
import app.assi.core.Vault
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which model does what. Providers are any OpenAI-compatible endpoints (base URL + key);
 * roles say which provider and model serve the main agent, each sub-agent, each AI function
 * of a module, and speech. A role without its own assignment uses the main one.
 *
 * A provider's key is a secret of the shell: it is kept in the vault under `provider.<id>`, is put
 * into requests here and is given to no page, script or model. The list of providers says only
 * whether a key is set.
 */
object Models {
    const val MAIN = "main"
    private const val PROVIDERS = "models.providers"
    private const val ROLES = "models.roles"

    class Target(val provider: String, val baseUrl: String, val key: String, val model: String, val options: JSONObject) {
        /** The model takes pictures; assumed unless the owner says otherwise. */
        val vision: Boolean get() = options.optBoolean("vision", true)

        /** How hard the model should think ("low", "high"…); empty leaves it to the provider. */
        val effort: String get() = options.optString("effort").trim()

        val openRouter: Boolean get() = baseUrl.contains("openrouter.ai")

        /** The model runs on the phone itself. */
        val onPhone: Boolean get() = baseUrl.startsWith(LocalLlm.BASE)

        /**
         * How many tokens the conversation may take at once: what the owner set for this role,
         * or else a common size for a cloud model and a roomy one for a model on the phone.
         * The conversation is folded before it outgrows this.
         */
        val window: Int
            get() = if (onPhone) LocalLlm.windowFor(model, options.optInt("context", 0))
            else options.optInt("context", 0).takeIf { it > 0 } ?: CLOUD_WINDOW
    }

    /** Assumed for a cloud model whose window the owner did not state. */
    const val CLOUD_WINDOW = 128_000

    private fun stored(): JSONArray = runCatching { JSONArray(App.prefs.getString(PROVIDERS, "[]")) }.getOrElse { JSONArray() }
    private fun keyName(id: String) = "provider.$id"

    /** The key of a provider, for the request that needs it; empty when none is set. */
    private fun keyOf(id: String): String = runCatching { Vault.get(keyName(id)) }.getOrNull().orEmpty().trim()

    /**
     * Keys used to lie in the list itself, in the open. Any found there (an install made before this,
     * a restored old backup, a list written past [setProviders]) are moved to the vault. Returns true
     * when it moved any: what was written to disk with them (chats, tasks) is then cleaned by the caller.
     */
    @Synchronized
    fun settle(): Boolean {
        val list = stored()
        val moved = ArrayList<Pair<String, String>>()
        for (i in 0 until list.length()) {
            val provider = list.optJSONObject(i) ?: continue
            val key = (provider.opt("key") as? String)?.trim().orEmpty().takeIf { it != KEY_MASK }.orEmpty()
            if (provider.has("key")) provider.remove("key")
            if (key.isEmpty()) continue
            Vault.set(keyName(provider.optString("id")), key)
            moved.add(keyName(provider.optString("id")) to key)
        }
        if (stored().toString() != list.toString()) App.prefs.edit().putString(PROVIDERS, list.toString()).commit()
        return moved.isNotEmpty()
    }

    /**
     * What stands in a provider's `key` when one is set. Pages written when the key itself was there
     * (the owner's own copy of the settings among them) go on saying "a key is set", and sending the
     * mask back changes nothing.
     */
    const val KEY_MASK = "••••••••"

    /** `[{id, name, baseUrl, hasKey, key?}]` — never the key itself: `key` is [KEY_MASK] when one is set. */
    fun providers(): JSONArray {
        settle()
        val list = stored()
        for (i in 0 until list.length()) list.optJSONObject(i)?.let {
            val has = Vault.has(keyName(it.optString("id")))
            it.put("hasKey", has)
            if (has) it.put("key", KEY_MASK)
        }
        return list
    }

    /**
     * Saves the list. A `key` in an entry is taken into the vault and not kept in the list; an entry
     * without one, or with the mask it was given, keeps the key it had; `dropKey: true` removes it.
     * Keys of providers that are gone are removed.
     */
    @Synchronized
    fun setProviders(list: JSONArray) {
        val clean = JSONArray()
        val kept = HashSet<String>()
        for (i in 0 until list.length()) {
            val provider = JSONObject((list.optJSONObject(i) ?: continue).toString())
            val id = provider.optString("id")
            val key = (provider.opt("key") as? String)?.trim().orEmpty().takeIf { it != KEY_MASK }.orEmpty()
            if (key.isNotEmpty()) Vault.set(keyName(id), key) else if (provider.optBoolean("dropKey")) Vault.remove(keyName(id))
            provider.remove("key"); provider.remove("hasKey"); provider.remove("dropKey")
            kept.add(id)
            clean.put(provider)
        }
        Vault.names().filter { it.startsWith("provider.") && it.removePrefix("provider.") !in kept }.forEach { Vault.remove(it) }
        App.prefs.edit().putString(PROVIDERS, clean.toString()).commit()
    }

    /** `{role: {provider, model, vision?, effort?, ...options}}` */
    fun roles(): JSONObject = runCatching { JSONObject(App.prefs.getString(ROLES, "{}")!!) }.getOrElse { JSONObject() }

    fun setRoles(roles: JSONObject) = App.prefs.edit().putString(ROLES, roles.toString()).apply()

    /** The role of an agent: the main agent is `main`, any other is `agent:<id>`. */
    fun roleOf(agent: String): String = if (agent == Agent.MAIN) MAIN else "agent:$agent"

    private fun own(role: String): JSONObject? =
        roles().optJSONObject(role)?.takeIf { it.optString("model").isNotBlank() }

    /**
     * Where requests of this role go; with [inherit], an unassigned role is served by the main model.
     * [chosen] is what one chat asks for instead: `{provider, model, …}` puts another model in the
     * role's place with its own options, and without a model (`{effort}`) only changes the options
     * of the role's model.
     */
    fun resolve(role: String, inherit: Boolean = true, chosen: JSONObject? = null): Target {
        val usual = own(role) ?: (if (inherit) own(MAIN) else null)
        val picked = chosen?.takeIf { it.length() > 0 }
        val another = picked != null && picked.optString("model").isNotBlank() &&
            (picked.optString("model") != usual?.optString("model") || picked.optString("provider") != usual?.optString("provider"))
        val assignment = when {
            picked == null -> usual
            another -> picked
            usual == null -> null
            else -> JSONObject(usual.toString()).apply { picked.keys().forEach { put(it, picked.get(it)) } }
        }
            ?: throw LlmError(
                if (inherit) "Не выбрана основная модель. Откройте «Настройки» и назначьте её."
                else "Для роли «$role» не выбрана модель. Откройте «Настройки» и назначьте её.",
            )
        val all = providers()
        val wanted = assignment.optString("provider")
        var provider: JSONObject? = null
        for (i in 0 until all.length()) {
            val candidate = all.getJSONObject(i)
            if (candidate.optString("id") == wanted) provider = candidate
        }
        provider = provider ?: all.optJSONObject(0)
            ?: throw LlmError("Не задан ни один провайдер моделей. Откройте «Настройки» и добавьте его.")
        val baseUrl = provider.optString("baseUrl").trim().trimEnd('/')
        if (baseUrl.isEmpty()) throw LlmError("У провайдера «${provider.optString("name")}» не указан адрес.")
        return Target(provider.optString("id"), baseUrl, keyOf(provider.optString("id")), assignment.getString("model"), assignment)
    }

    /**
     * Models the provider offers (`GET /models`): `[{id, vision?, reasoning?}]`. The two flags
     * are there only when the provider describes its models (OpenRouter does).
     */
    fun list(providerId: String): JSONArray {
        val all = providers()
        var provider: JSONObject? = null
        for (i in 0 until all.length()) {
            if (all.getJSONObject(i).optString("id") == providerId) provider = all.getJSONObject(i)
        }
        provider ?: throw LlmError("Нет такого провайдера")
        if (provider.optString("baseUrl").startsWith(LocalLlm.BASE)) {
            // The models on the phone: those that are downloaded.
            val local = LocalLlm.status()
            return JSONArray((0 until local.length()).map { local.getJSONObject(it) }.filter { it.getBoolean("installed") }
                .map { JSONObject().put("id", it.getString("id")) })
        }
        val request = Request.Builder().url(provider.optString("baseUrl").trim().trimEnd('/') + "/models").apply {
            val key = keyOf(provider.optString("id"))
            if (key.isNotEmpty()) header("Authorization", "Bearer $key")
        }.build()
        Llm.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmError("Провайдер ответил ошибкой ${response.code}: ${body.take(300)}")
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val data = parsed?.optJSONArray("data") ?: parsed?.optJSONArray("models")
                ?: runCatching { JSONArray(body) }.getOrNull()
                ?: throw LlmError("Провайдер не отдал список моделей")
            val found = ArrayList<JSONObject>()
            for (i in 0 until data.length()) {
                val item = data.get(i)
                val id = if (item is JSONObject) item.optString("id").ifEmpty { item.optString("name") } else item.toString()
                if (id.isBlank()) continue
                val entry = JSONObject().put("id", id)
                if (item is JSONObject) {
                    item.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { inputs ->
                        entry.put("vision", (0 until inputs.length()).any { inputs.optString(it) == "image" })
                    }
                    item.optJSONArray("supported_parameters")?.let { supported ->
                        entry.put("reasoning", (0 until supported.length()).any { supported.optString(it) == "reasoning" })
                    }
                }
                found.add(entry)
            }
            return JSONArray(found.sortedBy { it.getString("id") })
        }
    }
}
