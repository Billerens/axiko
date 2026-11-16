package app.assi

import app.assi.store.StoreReviewAccess
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StoreReviewAccessTest {
    private fun plan(digest: String = "d1", hash: String = "source1") = JSONObject().put("id", "p1").put("digest", digest)
        .put("artifactSha256", "archive1").put("status", "ready").put("reviewFiles", JSONArray().put(JSONObject()
            .put("path", "code/main.js").put("sha256", hash).put("size", 10).put("executable", true)))
    private fun read(start: Int, end: Int, side: String = "candidate", hash: String = "source1") = JSONObject()
        .put("path", "code/main.js").put("offset", start).put("nextOffset", end).put("totalLength", 10).put("side", side).put("sha256", hash)
    private fun run(block: (StoreReviewAccess, java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("store-review-test-").toFile()
        try { block(StoreReviewAccess(directory), directory) } finally { directory.deleteRecursively() }
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Must reject") } catch (_: IllegalArgumentException) {} }

    @Test fun auditRequiresConsentAndHasNoExecutionTools() = run { access, _ ->
        rejected { access.create("assi", "review1", plan(), false) }
        assertNull(access.session("assi", "review1"))
        assertFalse(StoreReviewAccess.TOOLS.any { it in listOf("js_eval", "shell", "fs_read", "ui_open", "subtasks") })
        access.create("assi", "review1", plan(), true)
        assertEquals("p1", access.forPlan("p1")!!.getString("planId"))
    }

    @Test fun readCoverageMergesRangesButRejectsGapsAndWrongSide() = run { access, _ ->
        val plan = plan(); access.create("assi", "review1", plan, true)
        access.record("assi", "review1", plan, read(0, 10, "local"))
        assertFalse(access.coverage("assi", "review1", plan).getBoolean("complete"))
        access.record("assi", "review1", plan, read(0, 4)); access.record("assi", "review1", plan, read(6, 10))
        assertFalse(access.coverage("assi", "review1", plan).getBoolean("complete"))
        access.record("assi", "review1", plan, read(3, 7))
        assertTrue(access.coverage("assi", "review1", plan).getBoolean("complete"))
    }

    @Test fun actualSubjectAndFileHashesAreBoundToCoverage() = run { access, _ ->
        val plan = plan(); access.create("assi", "review1", plan, true)
        access.record("assi", "review1", plan, read(0, 10, hash = "wrong"))
        assertFalse(access.coverage("assi", "review1", plan).getBoolean("complete"))
        access.record("assi", "review1", plan, read(0, 10))
        assertTrue(access.coverage("assi", "review1", plan).getBoolean("complete"))
        rejected { access.bind("assi", "review1", plan().put("id", "p2")) }
        rejected { access.bind("assi", "review1", plan().put("artifactSha256", "archive2")) }
        assertFalse(access.coverage("assi", "review1", plan("d2", "source2")).getBoolean("complete"))
    }

    @Test fun applyNeedsCurrentPassCoverageAndResolvedConflicts() = run { access, _ ->
        val plan = plan(); access.create("assi", "review1", plan, true)
        val pass = JSONObject().put("status", "pass").put("planDigest", "d1")
        rejected { access.requireApply("assi", "review1", plan, pass) }
        access.record("assi", "review1", plan, read(0, 10))
        access.requireApply("assi", "review1", plan, pass)
        rejected { access.requireApply("assi", "review1", plan, JSONObject().put("status", "needsAttention").put("planDigest", "d1")) }
        rejected { access.requireApply("assi", "review1", plan, JSONObject().put("status", "pass").put("planDigest", "obsolete")) }
        rejected { access.requireApply("assi", "review1", plan.put("status", "conflicts"), pass) }
    }

    @Test fun sessionAndCoverageSurviveRestart() = run { access, directory ->
        access.create("assi", "review1", plan(), true); access.record("assi", "review1", plan(), read(0, 10))
        val after = StoreReviewAccess(directory)
        assertTrue(after.coverage("assi", "review1", plan()).getBoolean("complete"))
        assertNull(after.session("assi", "other"))
    }

    @Test fun malformedSessionFailsClosedAndTextCoverageIsExplicit() = run { access, directory ->
        access.create("assi", "review1", plan(), true)
        directory.listFiles()!!.single { it.extension == "json" }.writeText("not json")
        try { access.session("assi", "review1"); fail("Must not turn into ordinary conversation") } catch (_: org.json.JSONException) {}
        assertTrue(StoreReviewAccess.reviewable("code/prompts.md", false))
        assertTrue(StoreReviewAccess.reviewable("assets/icon.svg", false))
        assertFalse(StoreReviewAccess.reviewable("assets/banner.png", false))
    }

    @Test fun serializedReadFitsConversationLimitEvenWithEscapes() {
        val content = "\u0001".repeat(StoreReviewAccess.READ_CHUNK)
        var offset = 0; val received = StringBuilder()
        while (offset < content.length) {
            val remaining = content.substring(offset)
            val view = StoreReviewAccess.readView(read(offset, content.length).put("totalLength", content.length)
                .put("eof", true).put("content", remaining).put("text", remaining))
            assertFalse(view.has("text"))
            assertTrue(view.toString().length < 28000)
            val delivered = JSONObject(view.toString()).getString("content")
            assertEquals(delivered.length, view.getInt("nextOffset") - offset)
            assertEquals(view.getInt("nextOffset") == content.length, view.getBoolean("eof"))
            received.append(delivered); offset = view.getInt("nextOffset")
        }
        assertEquals(content, received.toString())
        val readable = "x".repeat(StoreReviewAccess.READ_CHUNK)
        assertEquals(readable, StoreReviewAccess.readView(read(0, readable.length).put("content", readable)).getString("content"))
        rejected { StoreReviewAccess.readView(read(0, 10).put("content", readable + "x")) }
    }

    @Test fun largeInventoryIsPagedWithoutLosingFilesOrLeakingFullManifest() {
        val input = plan().put("reviewFiles", JSONArray((0..511).map { JSONObject().put("path", "code/source$it.js").put("sha256", "a".repeat(64)).put("executable", true) }))
            .put("manifest", JSONObject().put("huge", "x".repeat(80000)))
            .put("conflicts", JSONArray().put(JSONObject().put("path", "main.js")))
        val found = mutableSetOf<String>(); var offset = 0
        do {
            val page = StoreReviewAccess.planView(input, JSONObject().put("filesOffset", offset))
            assertTrue(page.toString().length < 28000)
            assertFalse(page.has("manifest"))
            assertEquals("code/main.js", page.getJSONArray("conflicts").getJSONObject(0).getString("readPath"))
            val list = page.getJSONArray("reviewFiles")
            for (i in 0 until list.length()) assertTrue(found.add(list.getJSONObject(i).getString("path")))
            offset = if (page.isNull("nextFilesOffset")) -1 else page.getInt("nextFilesOffset")
        } while (offset >= 0)
        assertEquals(512, found.size)
    }
}
