package app.axiko.voice

import app.axiko.core.Pages
import org.json.JSONObject

internal fun checkedSpeechId(value: String?): String? = value?.also {
    require(it.isNotBlank() && it.length <= 128) { "Некорректный идентификатор сеанса распознавания." }
}

/** The terminal clear shares a lock with publication, including Android's callbacks. */
internal class SpeechPartial(private val id: String, private val mode: String) : AutoCloseable {
    private var last = ""
    private var closed = false

    @Synchronized
    fun emit(text: String, segment: Int? = null) {
        if (closed || text == last) return
        last = text
        val data = JSONObject().put("id", id).put("mode", mode).put("text", text)
        if (segment != null) data.put("segment", segment)
        Pages.broadcast("speech.partial", data.toString())
    }

    @Synchronized
    override fun close() {
        emit("")
        closed = true
    }
}
