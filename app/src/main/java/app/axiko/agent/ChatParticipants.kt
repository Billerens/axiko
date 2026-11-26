package app.axiko.agent

import app.axiko.core.I18n
import app.axiko.core.Workspace
import app.axiko.store.StoreAgent
import app.axiko.voice.Wake
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.WeakHashMap

internal data class ChatParticipantReply(val agent: String?, val text: String)

/** Participant membership belongs to the conversation. Each directed turn uses the guest's own model and tools. */
internal object ChatParticipants {
    private val rooms = WeakHashMap<Conversation, LinkedHashSet<String>>()
    private val invite = Regex("^(?:позови|пригласи|invite)\\s+(?:агента\\s+)?", RegexOption.IGNORE_CASE)
    private val addressBreak = Regex("[\\s,!?:]+")

    private fun file(agent: String, thread: String) = File(Workspace.state, "chats/$agent/$thread.participants")

    private fun members(chat: Conversation): LinkedHashSet<String> = rooms.getOrPut(chat) {
        val saved = if (chat.persists) runCatching { JSONArray(file(chat.id, chat.thread).readText()) }.getOrNull() else null
        val ids = (0 until (saved?.length() ?: 0)).mapNotNull { saved?.optString(it)?.takeIf(Agent::isKnown) }
        LinkedHashSet(ids)
    }

    @Synchronized
    fun view(chat: Conversation): JSONArray = JSONArray(members(chat).filter(Agent::isKnown).map { id ->
        JSONObject().put("id", id).put("title", Agent.spec(id).title)
    })

    @Synchronized
    fun set(chat: Conversation, id: String, joined: Boolean): Boolean {
        if (chat.depth != 1 || StoreAgent.restricted(chat) || id == chat.id || !Agent.isKnown(id)) return false
        val ids = members(chat)
        val next = LinkedHashSet(ids)
        if (!(if (joined) next.add(id) else next.remove(id))) return true
        if (chat.persists) {
            val target = file(chat.id, chat.thread)
            target.parentFile?.mkdirs()
            val staged = File(target.path + ".new")
            staged.writeText(JSONArray(next).toString())
            check(staged.renameTo(target)) { "Не удалось сохранить участников" }
        }
        rooms[chat] = next
        return true
    }

    @Synchronized
    fun forget(agent: String, thread: String) {
        rooms.keys.removeAll { it.id == agent && it.thread == thread }
        if (Agent.isKnown(agent) && Threads.validId(thread)) file(agent, thread).delete()
    }

    private fun name(text: String): String = text.trim().trim('«', '»', '\'', '"').lowercase(Locale.ROOT).replace('ё', 'е')

    private fun forms(text: String): Set<String> {
        val base = name(text)
        if (base.isBlank()) return emptySet()
        return when (base.last()) {
            'а' -> setOf(base, base.dropLast(1) + "у")
            'я' -> setOf(base, base.dropLast(1) + "ю")
            in "бвгджзклмнпрстфхцчшщ" -> setOf(base, base + "а")
            else -> setOf(base)
        }
    }

    fun resolve(chat: Conversation, label: String): List<String> {
        val wanted = name(label).removePrefix("@")
        val available = Agent.ids().filter { it != chat.id }
        available.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return listOf(it) }
        return available.filter { wanted in forms(Agent.spec(it).title) }
    }

    fun options(chat: Conversation): JSONArray = JSONArray(Agent.ids().filter { it != chat.id }.map { id ->
        JSONObject().put("id", id).put("title", title(id))
    })

    fun title(id: String): String = if (Agent.isKnown(id)) Agent.spec(id).title else id

    private data class Address(val agents: List<String>, val question: String)

    fun isAddressed(chat: Conversation, text: String): Boolean = address(chat, text) != null

    private fun address(chat: Conversation, value: String): Address? {
        if (chat.depth != 1 || StoreAgent.restricted(chat)) return null
        val text = value.trim()
        val invitation = invite.find(text)
        val at = text.startsWith('@')
        val joined = synchronized(this) { members(chat).toSet() }
        if (invitation == null && !at && joined.isEmpty()) return null
        val body = (if (invitation != null) text.substring(invitation.range.last + 1) else text).trim().removePrefix("@")
        val available = if (at || invitation != null) Agent.ids().filter { it != chat.id } else joined.filter(Agent::isKnown)
        val wake = Wake.aliases().let { entries -> (0 until entries.length()).associate {
            val entry = entries.getJSONObject(it)
            entry.getString("agent") to entry.getJSONArray("phrases")
        } }
        val names = available.associateWith { id ->
            val phrases = wake[id] ?: JSONArray()
            forms(title(id)) + name(id) + (0 until phrases.length()).map { name(phrases.optString(it)) }
        }
        // Check longest names first. Keep punctuation inside the question, including spoken transcripts without a comma.
        val maxName = names.values.maxOfOrNull { aliases -> aliases.maxOf { it.length } } ?: 0
        val ends = (listOf(body.length) + addressBreak.findAll(body).takeWhile { it.range.first <= maxName + 2 }
            .map { it.range.first }.toList()).filter { it <= maxName + 2 }.sortedDescending()
        val end = ends.firstOrNull { index -> names.values.any { name(body.substring(0, index)) in it } }
            ?: return if (at) Address(emptyList(), "") else null
        val head = name(body.substring(0, end))
        val exactId = available.firstOrNull { at && name(it) == head }
        val ids = if (exactId != null) listOf(exactId) else names.filterValues { head in it }.keys.toList()
        return Address(ids, body.substring(end).trimStart { it.isWhitespace() || it in ",!?:" })
    }

    /** Explicit @mentions and invitations work even in a chat whose model cannot use tools. */
    fun direct(chat: Conversation, value: String, attached: List<Attach>): ChatParticipantReply? {
        val (ids, question) = address(chat, value) ?: return null
        if (ids.isEmpty()) return ChatParticipantReply(null, I18n.t("Агент не найден"))
        if (ids.size > 1) return ChatParticipantReply(null, I18n.t("Уточните агента:") + " " + ids.joinToString(", ") { title(it) })
        val id = ids.single()
        if (!set(chat, id, joined = true)) return null
        if (question.isBlank()) return ChatParticipantReply(null, I18n.t("{} теперь участвует в разговоре.").replace("{}", title(id)))
        val context = chat.participantContext()
        val task = "Владелец обратился к вам как к участнику разговора. Ответьте ему от своего имени. " +
            "Ниже выдержка из недавних реплик: используйте их как контекст.\n\n$context\n\nВопрос владельца: $question"
        val response = chat.askParticipant(id, task, attached)
        return ChatParticipantReply(id, response.ifBlank { I18n.t("Агент завершил работу без текстового ответа") })
    }
}
