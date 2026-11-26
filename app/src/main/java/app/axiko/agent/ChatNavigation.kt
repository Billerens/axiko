package app.axiko.agent

import app.axiko.MainActivity
import app.axiko.sense.OnScreenAssistant
import app.axiko.store.StoreAgent
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap

/** Searches saved conversations locally and binds each navigation result to the surface that requested it. */
internal object ChatNavigation {
    private const val SEARCH_RECENT = 80
    private const val SEARCH_RESULT_LIMIT = 12
    private const val MAX_CHAT_BYTES = 3_000_000L
    private val words = Regex("[\\p{L}\\p{N}]+")

    private class Search(val panel: String?, val app: WeakReference<MainActivity>?, val matches: Map<String, JSONObject>)
    private val searches = WeakHashMap<Conversation, Search>()

    fun roomExists(agent: String, thread: String): Boolean =
        Agent.isKnown(agent) && Threads.validId(thread) && Threads.file(agent, thread).isFile

    private fun normalized(text: String): String = text.lowercase(Locale.ROOT).replace('ё', 'е')

    private fun match(text: String, terms: List<String>): Boolean {
        val body = normalized(text)
        return terms.all { it in body }
    }

    private data class Match(val thread: JSONObject, val score: Int)

    fun search(query: String): JSONArray {
        val terms = words.findAll(normalized(query).take(160)).map { it.value }.take(8).toList()
        if (terms.isEmpty()) return JSONArray()
        val recent = Agent.ids().asSequence().flatMap { id ->
            val list = Threads.list(id)
            (0 until list.length()).asSequence().map { list.getJSONObject(it).put("agent", id) }
        }.sortedByDescending { it.optLong("time") }.take(SEARCH_RECENT)
        val matches = recent.mapNotNull { item ->
            val id = item.getString("agent")
            val thread = item.getString("id")
            if (!roomExists(id, thread)) return@mapNotNull null
            val title = item.optString("title")
            val titleMatches = match(title, terms)
            val hit = if (titleMatches) null else snippet(id, thread, terms)
            if (!titleMatches && hit == null) return@mapNotNull null
            val position = normalized(title).indexOf(terms.joinToString(" "))
            val score = if (titleMatches && position >= 0) 3 else if (titleMatches) 2 else 1
            Match(item.put("agentTitle", Agent.spec(id).title).put("snippet", hit.orEmpty()), score)
        }.sortedWith(compareByDescending<Match> { it.score }.thenByDescending { it.thread.optLong("time") })
            .take(SEARCH_RESULT_LIMIT).toList()
        return JSONArray(matches.map { it.thread })
    }

    private fun snippet(agent: String, thread: String, terms: List<String>): String? {
        val file = Threads.file(agent, thread)
        if (file.length() > MAX_CHAT_BYTES) return null
        val messages = runCatching { JSONObject(file.readText()).getJSONArray("messages") }.getOrNull() ?: return null
        for (i in messages.length() - 1 downTo maxOf(0, messages.length() - 24)) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role")
            val text = (message.opt("content") as? String)?.take(8_000) ?: continue
            if (role !in setOf("user", "assistant") || !match(text, terms)) continue
            val offset = normalized(text).indexOf(terms.first()).coerceAtLeast(0)
            return text.drop(maxOf(0, offset - 36)).take(140).replace(Regex("\\s+"), " ")
        }
        return null
    }

    fun find(chat: Conversation, query: String): JSONArray {
        check(chat.depth == 1 && !StoreAgent.restricted(chat)) { "Нет доступа к истории чатов из этого разговора" }
        val panel = OnScreenAssistant.navigationScope(chat)
        val activity = if (panel == null) MainActivity.current?.takeIf { it.displaysChat(chat.id, chat.thread) } else null
        val results = search(query)
        val matches = LinkedHashMap<String, JSONObject>()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val token = UUID.randomUUID().toString()
            result.put("handle", token)
            matches[token] = result
        }
        synchronized(searches) { searches[chat] = Search(panel, activity?.let(::WeakReference), matches) }
        return results
    }

    fun open(chat: Conversation, handle: String): Boolean {
        if (chat.depth != 1 || StoreAgent.restricted(chat)) return false
        val search = synchronized(searches) { searches[chat] } ?: return false
        val item = search.matches[handle] ?: return false
        val agent = item.getString("agent")
        val thread = item.getString("id")
        if (!roomExists(agent, thread)) return false
        if (search.panel != null) {
            if (!OnScreenAssistant.navigate(chat, search.panel, agent, thread)) return false
        } else {
            val activity = search.app?.get()?.takeIf { MainActivity.current === it && it.displaysChat(chat.id, chat.thread) } ?: return false
            activity.navigateChat(agent, thread)
        }
        synchronized(searches) { searches.remove(chat, search) }
        return true
    }
}
