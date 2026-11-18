package app.axiko.store

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * What a listing says in the interface's language. A card and the details of a package are written
 * in English, the required language, and may carry the same texts in others under `locales`
 * (docs/marketplace/catalog-pages.md). A language that has a locale gets its words, and anything the
 * locale does not give stays English; a language without one gets English as it is.
 *
 * A tag is its English text: that is what the catalog counts and filters by, whatever the language.
 * A locale gives the words to show for the same tags, one for each, in the same order.
 */
object StoreLocale {
    /** The words a card shows: title, summary and tags in a language, and the tags as the catalog counts them. */
    class View(val title: String, val summary: String, val tags: List<String>, val ids: List<String>)

    /**
     * The locale for [language]: the exact code (`pt-BR`), then the language itself (`pt`), then any
     * variant of it (`pt-PT`, the first by code). English is the base text and has none; null means the base text.
     */
    fun pick(locales: JSONObject?, language: String): JSONObject? {
        if (locales == null || locales.length() == 0) return null
        val code = language.replace('_', '-').trim()
        val main = code.substringBefore('-').lowercase(Locale.ROOT)
        if (main.isEmpty() || main == BASE) return null
        val keys = locales.keys().asSequence().toList()
        val chosen = keys.firstOrNull { it.equals(code, ignoreCase = true) } ?: keys.firstOrNull { it == main }
            ?: keys.filter { it.substringBefore('-') == main }.minOrNull()
        return chosen?.let { locales.optJSONObject(it) }
    }

    fun view(entry: JSONObject, language: String): View {
        val ids = entry.optJSONArray("tags")?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: emptyList()
        val locale = pick(entry.optJSONObject("locales"), language)
        val words = locale?.optJSONArray("tags")?.takeIf { it.length() == ids.size }?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: ids
        return View(locale?.optString("title")?.ifEmpty { null } ?: entry.optString("title"),
            locale?.optString("summary")?.ifEmpty { null } ?: entry.optString("summary"), words, ids)
    }

    /** A card as the screen shows it: [entry] is a copy that may be changed; its `locales` are spent. */
    fun card(entry: JSONObject, language: String): JSONObject {
        val shown = view(entry, language)
        entry.remove("locales")
        if (entry.has("title")) entry.put("title", shown.title)
        if (entry.has("summary")) entry.put("summary", shown.summary)
        if (entry.has("tags")) entry.put("tags", JSONArray(shown.tags))
        return entry
    }

    /** Details as the screen shows them: [details] is a copy that may be changed. */
    fun details(details: JSONObject, language: String): JSONObject {
        val locale = pick(details.optJSONObject("locales"), language)
        details.remove("locales")
        if (locale == null) return details
        locale.optString("description").takeIf { it.isNotEmpty() }?.let { details.put("description", it) }
        val notes = locale.optJSONObject("changes")
        val changes = details.optJSONArray("changes")
        if (notes != null && changes != null) for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            notes.optString(change.getString("version")).takeIf { it.isNotEmpty() }?.let { change.put("notes", it) }
        }
        val captions = locale.optJSONArray("captions")
        val shots = details.optJSONArray("screenshots")
        if (captions != null && shots != null) for (i in 0 until minOf(captions.length(), shots.length())) {
            (captions.opt(i) as? String)?.takeIf { it.isNotEmpty() }?.let { shots.getJSONObject(i).put("caption", it) }
        }
        return details
    }

    private const val BASE = "en"
}
