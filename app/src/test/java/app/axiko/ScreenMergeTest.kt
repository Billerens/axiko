package app.axiko

import app.axiko.core.ScreenMerge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMergeTest {
    @Test fun appliesDisjointEditsToOriginalWhileKeepingLocalCustomization() {
        val local = """
            <main class="my-layout">
              <div class="my-widget">Custom widget</div>
              <!-- actions -->
              <!-- handlers -->
            </main>
        """.trimIndent()
        val withButton = "<button>Check for updates</button>"
        val withHandler = "<script>wireUpdateCheck()</script>"
        val candidate = ScreenMerge.candidate(local, proposal(
            "<!-- handlers -->" to withHandler,
            "<!-- actions -->" to withButton,
        ))
        assertEquals(local.replace("<!-- actions -->", withButton).replace("<!-- handlers -->", withHandler), candidate.text)
        assertTrue(candidate.diff.contains("+  $withButton"))
        assertTrue(candidate.diff.contains("+  $withHandler"))
    }

    @Test fun lineEndingNormalizationPreservesTheLocalFile() {
        val local = "<main>\r\n  <div>My layout</div>\r\n  old action\r\n  old handler\r\n</main>\r\n"
        val result = ScreenMerge.candidate(local, proposal("old action\n  old handler" to "new action\n  new handler"))
        assertEquals("<main>\r\n  <div>My layout</div>\r\n  new action\r\n  new handler\r\n</main>\r\n", result.text)
    }

    @Test fun rejectsAmbiguousMissingAndOverlappingAnchors() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenMerge.candidate("a\nb\na\r\nb", proposal("a\nb" to "replacement"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenMerge.candidate("one", proposal("one" to "two", "two" to "three"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenMerge.candidate("alpha beta gamma", proposal("alpha beta" to "one", "beta gamma" to "two"))
        }
    }

    @Test fun rejectsInvalidAndExcessiveModelResponses() {
        val cases = listOf(
            "{}",
            """{"edits":[]}""",
            """{"edits":[{"find":"old","replace":true}]}""",
            """{"edits":[{"find":"old","replace":"new","path":"other"}]}""",
            proposal("old" to "new") + "extra content",
            proposal("old" to "x".repeat(16_001)),
            proposal("old" to "old"),
        )
        cases.forEach { response ->
            assertThrows(IllegalArgumentException::class.java) {
                ScreenMerge.candidate("old", response)
            }
        }
    }

    @Test fun rejectsReplacementOfTheEntireScreen() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenMerge.candidate("\n<main>Owner layout</main>\n", proposal("<main>Owner layout</main>" to "<main>Replacement</main>"))
        }
    }

    @Test fun rejectsInvalidUnicodeIntroducedByTheModel() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenMerge.candidate("<main>old</main>", """{"edits":[{"find":"old","replace":"\uD800"}]}""")
        }
    }

    @Test fun rejectsEditsThatWouldTouchAStoredSecret() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenMerge.candidate("secret-value", proposal("secret-value" to "new")) { "secret-value" !in it }
        }
    }

    @Test fun showsBothChangedFragmentsWhenWholeFileDiffExceedsItsBudget() {
        val middle = List(2_100) { "unchanged line $it" }.joinToString("\n")
        val local = "start marker\n$middle\nend marker"
        val result = ScreenMerge.candidate(local, proposal("start marker" to "start action", "end marker" to "end handler"))
        assertEquals("start action\n$middle\nend handler", result.text)
        assertTrue(result.diff.contains("@@ -1,1 +1,1 @@\n-start marker\n+start action"))
        assertTrue(result.diff.contains("@@ -2102,1 +2102,1 @@\n-end marker\n+end handler"))
    }

    private fun proposal(vararg edits: Pair<String, String>): String = JSONObject()
        .put("edits", JSONArray(edits.map { (find, replace) -> JSONObject().put("find", find).put("replace", replace) }))
        .toString()
}
