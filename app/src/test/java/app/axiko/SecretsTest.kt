package app.axiko

import app.axiko.core.Secrets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A secret is used, not shown: whose a name is, and how a value is kept out of what a model reads. */
class SecretsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "sk-test-0123456789abcdef/+=XYZ"
    private val known = listOf("provider.demo" to key)

    @Test fun secretsOfTheShellAreToldFromSecretsOfModules() {
        for (name in listOf("provider.openrouter", "mcp.github", "oauth.google", "hf.token", "backup.password")) assertTrue(name, Secrets.shell(name))
        for (name in listOf("search.key", "gmail.password", "providers", "wallpapers.unsplash", "mcp", "oauth")) assertFalse(name, Secrets.shell(name))
    }

    @Test fun aStoredValueIsTakenOutOfTextInEveryFormItTakesThere() {
        val mark = Secrets.mark("provider.demo")
        assertEquals("Authorization: Bearer $mark", Secrets.scrub("Authorization: Bearer $key", known))
        // Inside JSON and inside an address the value looks different and is still the value.
        val json = JSONObject().put("providers", JSONObject().put("key", key)).toString()
        assertFalse(Secrets.scrub(json, known).contains("0123456789abcdef")); assertTrue(Secrets.scrub(json, known).contains(mark))
        val address = "https://api.example/search?key=" + java.net.URLEncoder.encode(key, "UTF-8") + "&q=1"
        assertEquals("https://api.example/search?key=$mark&q=1", Secrets.scrub(address, known))
        // The tokens of a sign-in are kept as one JSON value; each is looked for on its own.
        val tokens = listOf("oauth.google" to JSONObject().put("access_token", "ya29.a0Af-access-token-value").put("refresh_token", "1//0g-refresh-token-value").put("expires", 3600).toString())
        val seen = Secrets.scrub("token ya29.a0Af-access-token-value and 1//0g-refresh-token-value", tokens)
        assertEquals("token ${Secrets.mark("oauth.google")} and ${Secrets.mark("oauth.google")}", seen)
        // Cut in the middle (a clipped line, a shortened preview), what is left of the value goes too.
        assertEquals("\"key\":\"$mark\",\"detail\"", Secrets.scrub("\"key\":\"${key.take(20)}\",\"detail\"", known))
        assertEquals("twice: $mark and $mark.", Secrets.scrub("twice: ${key.take(15)} and $key.", known))
        // Text without the value is left as it is, and a value too short to tell from a word is not looked for.
        assertEquals("nothing to hide here", Secrets.scrub("nothing to hide here", known))
        assertEquals("the pin is 1234 today", Secrets.scrub("the pin is 1234 today", listOf("door.pin" to "1234")))
    }

    @Test fun aRequestNamesItsSecretsAndTheValuesArePutInByTheOneWhoSendsIt() {
        val asked = ArrayList<String>()
        val filled = Secrets.fill("Bearer {{secret:search.key}} and {{secret:other-one_2}}, not {{secret:}} nor {{ secret:x }}") { asked.add(it); "<$it>" }
        assertEquals("Bearer <search.key> and <other-one_2>, not {{secret:}} nor {{ secret:x }}", filled)
        assertEquals(listOf("search.key", "other-one_2"), asked)
        // A value with characters that mean something to a regular expression goes in as it is.
        assertEquals("k=" + "$" + "1" + "\\" + "x", Secrets.fill("k={{secret:a}}") { "$" + "1" + "\\" + "x" })
    }

    @Test fun whatWasAlreadyWrittenToDiskIsCleanedOnce() {
        val chats = File(folder.root, "state/chats/axiko").apply { mkdirs() }
        val chat = File(chats, "thread.json").apply { writeText(JSONObject().put("messages", "the list is [{\"key\":\"$key\"}]").toString()) }
        val clean = File(chats, "other.json").apply { writeText("{\"messages\":[]}") }
        val store = File(folder.root, "state/store").apply { mkdirs() }
        val skipped = File(store, "plan.json").apply { writeText(key) }
        val before = clean.lastModified()

        assertEquals(1, Secrets.scrubFiles(File(folder.root, "state"), known) { it.name == "store" })
        assertFalse(chat.readText().contains("0123456789abcdef")); assertTrue(chat.readText().contains("provider.demo"))
        JSONObject(chat.readText()) // still what it was: a chat that can be read
        assertEquals("{\"messages\":[]}", clean.readText()); assertEquals(before, clean.lastModified())
        assertEquals(key, skipped.readText())
        assertEquals(0, Secrets.scrubFiles(File(folder.root, "state"), known) { it.name == "store" })
    }
}
