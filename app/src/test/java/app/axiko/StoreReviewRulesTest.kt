package app.axiko

import app.axiko.store.StoreBanned
import app.axiko.store.StoreReviewAccess
import app.axiko.store.StoreRules
import app.axiko.store.StoreService
import app.axiko.store.StoreSourceFault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The rules of the store review: who decides the status, what is banned, and what only the owner may decide. */
class StoreReviewRulesTest {
    @get:Rule val folder = TemporaryFolder()
    private val transport = StoreServiceFixtures.Transport()
    private val workspace get() = File(folder.root, "axiko")
    private val state get() = File(folder.root, "store")
    private fun service() = StoreService(workspace, state, transport) { "snapshot" }
    private fun add(service: StoreService) = service.sources.add(StoreServiceFixtures.INDEX).getString("id")

    private fun finding(rule: String, severity: String, certainty: String = "observed", path: String = "code/main.js", line: Int = 2) = JSONObject()
        .put("rule", rule).put("severity", severity).put("certainty", certainty)
        .put("evidence", JSONObject().put("path", path).put("line", line).put("quote", "return 1;"))
        .put("explanation", "Пакет отправляет заметки владельца на сторонний сервер.").put("resolution", "Не устанавливать.")
    private fun report(vararg findings: JSONObject, unknowns: List<String> = emptyList(), complete: Boolean = true) = JSONObject()
        .put("summary", "Прочитан весь подготовленный кандидат.").put("findings", JSONArray(findings.toList())).put("unknowns", JSONArray(unknowns))
        .put("coverage", JSONObject().put("complete", complete).put("filesReviewed", JSONArray().put("code/main.js")).put("unreviewedFiles", JSONArray()))
    private fun normalize(report: JSONObject, complete: Boolean = true) =
        StoreRules.normalize(report, setOf("code/main.js", "package.json"), complete) { 4 }
    private fun refused(block: () -> Unit): String = try { block(); fail("Must be refused"); "" } catch (error: IllegalArgumentException) { error.message.orEmpty() }
    private fun submit(service: StoreService, plan: JSONObject, report: JSONObject) = service.submitReview(plan.getString("id"), plan.getString("digest"), report)
    private fun manual(service: StoreService, plan: JSONObject) = service.apply(plan.getString("id"), plan.getString("digest"), true)

    // ---- the list itself ------------------------------------------------------------------------

    @Test fun rulesAreOneConsistentList() {
        val codes = StoreRules.all.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(StoreRules.all.all { it.level == StoreRules.VIOLATION || it.level == StoreRules.SUSPICIOUS })
        assertTrue(codes.all { Regex("[a-z]+(?:\\.[a-z]+(?:-[a-z]+)*)+").matches(it) })
        // A reviewer that cannot prove a violation always has a named suspicion to report instead.
        for (rule in StoreRules.all.filter { it.level == StoreRules.VIOLATION }) {
            assertEquals(rule.code, StoreRules.SUSPICIOUS, StoreRules.rule(rule.unclear!!).level)
        }
        // What the task names must be covered, each by a rule that bans.
        for (code in listOf("data.exfiltration", "secrets.access", "code.remote", "code.obfuscated", "privilege.abuse", "integrity.foreign",
            "agent.manipulation", "background.hidden", "content.mismatch", "fraud.deception")) assertEquals(code, StoreRules.VIOLATION, StoreRules.rule(code).level)
        val prompt = StoreRules.prompt(); val json = StoreRules.json()
        assertEquals(codes.size, json.length())
        assertTrue(codes.all { prompt.contains("- $it — ") })
    }

    /** The document's table is rendered from the code. `AXIKO_WRITE_STORE_RULES=1` rewrites it after a change of the rules. */
    @Test fun documentCarriesExactlyTheRulesOfTheCode() {
        val file = File("../docs/marketplace/review-rules.md")
        assertTrue("docs/marketplace/review-rules.md is missing", file.isFile)
        val text = file.readText().replace("\r\n", "\n")
        val begin = "<!-- rules:begin -->\n"; val end = "<!-- rules:end -->"
        assertTrue(text.contains(begin) && text.contains(end))
        val rendered = text.substringBefore(begin) + begin + StoreRules.markdown() + end + text.substringAfter(end)
        if (System.getenv("AXIKO_WRITE_STORE_RULES") == "1" && rendered != text) file.writeText(rendered)
        else assertEquals("The rules table differs from StoreRules: run the test with AXIKO_WRITE_STORE_RULES=1", rendered, text)
    }

    // ---- the report: form and status ------------------------------------------------------------

    @Test fun statusFollowsTheFindingsAndNotTheReviewersWord() {
        assertEquals("pass", normalize(report()).getString("status"))
        val bragging = normalize(report(finding("network.undeclared", "suspicious")).put("status", "pass"))
        assertEquals("needsAttention", bragging.getString("status")); assertEquals("pass", bragging.getString("claimedStatus"))
        assertEquals("needsAttention", normalize(report(unknowns = listOf("Не разобран большой словарь."))).getString("status"))
        val mixed = normalize(report(finding("code.dynamic", "suspicious", "inferred"), finding("data.exfiltration", "violation")).put("status", "needsAttention"))
        assertEquals("reject", mixed.getString("status"))
        val first = mixed.getJSONArray("findings").getJSONObject(1)
        assertEquals("Утечка данных владельца", first.getString("title")); assertEquals("violation", first.getString("severity"))
        assertEquals(2, first.getJSONObject("evidence").getInt("line")); assertEquals("return 1;", first.getJSONObject("evidence").getString("quote"))
        // Nothing found in sources that were not read to the end is not a verdict at all.
        assertTrue(refused { normalize(report(), complete = false) }.contains("Прочитаны не все"))
        val partial = normalize(report(unknowns = listOf("Не хватило лимита на sim.js.")), complete = false)
        assertEquals("needsAttention", partial.getString("status")); assertEquals(2, partial.getJSONArray("unknowns").length())
        assertEquals("reject", normalize(report(finding("code.remote", "violation")), complete = false).getString("status"))
    }

    @Test fun unknownRuleMissingEvidenceAndWrongLevelAreNotAccepted() {
        assertTrue(refused { normalize(report(finding("totally.fine", "suspicious"))) }.contains("totally.fine"))
        assertTrue(refused { normalize(report(finding("data.exfiltration", "violation").apply { remove("evidence") })) }.contains("evidence"))
        refused { normalize(report(finding("data.exfiltration", "violation", path = "code/other.js"))) }
        refused { normalize(report(finding("data.exfiltration", "violation", line = 0))) }
        assertTrue(refused { normalize(report(finding("data.exfiltration", "violation", line = 5))) }.contains("всего 4"))
        refused { normalize(report(finding("data.exfiltration", "violation").put("evidence", JSONObject().put("path", "code/main.js")))) }
        // The level belongs to the rule: a violation cannot be filed as a mere suspicion, nor a guess as a violation.
        assertTrue(refused { normalize(report(finding("data.exfiltration", "suspicious"))) }.contains("data.sensitive-access"))
        refused { normalize(report(finding("network.undeclared", "violation"))) }
        assertTrue(refused { normalize(report(finding("secrets.access", "violation", "inferred"))) }.contains("secrets.own"))
        refused { normalize(report(finding("code.dynamic", "suspicious", "certain"))) }
        refused { normalize(report(finding("code.dynamic", "suspicious").put("explanation", " "))) }
        refused { normalize(report(finding("code.dynamic", "suspicious").apply { remove("resolution") })) }
        refused { normalize(report().put("findings", JSONArray().put("data.exfiltration"))) }
        refused { normalize(report().put("summary", "")) }
        refused { normalize(report(unknowns = listOf(""))) }
    }

    // ---- reject: the ban ------------------------------------------------------------------------

    @Test fun rejectBansAtOnceAndNoPathPreparesReviewsOrInstallsTheArchive() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        val sha = plan.getString("artifactSha256")
        val saved = submit(service, plan, report(finding("data.exfiltration", "violation"), finding("code.dynamic", "suspicious", "inferred")).put("status", "pass"))
        assertEquals("reject", saved.getString("status")); assertTrue(saved.getBoolean("banned"))
        val ban = service.bans.list().getJSONObject(0)
        assertEquals(sha, ban.getString("artifactSha256")); assertEquals(source, ban.getString("sourceId")); assertEquals("demo.counter", ban.getString("packageId"))
        assertEquals("1.0.0", ban.getString("version")); assertEquals("review", ban.getString("origin")); assertTrue(ban.getLong("bannedAt") > 0)
        // Only what broke a rule is the reason of the ban; the explanation is kept for the owner.
        assertEquals(1, ban.getJSONArray("violations").length())
        assertTrue(ban.getJSONArray("violations").getJSONObject(0).getString("explanation").contains("сторонний сервер"))
        assertTrue(File(state, "bans.json").isFile); assertFalse(state.canonicalPath.startsWith(workspace.canonicalPath))

        val banned = service.plan(plan.getString("id"))
        assertEquals("banned", banned.getString("status")); assertEquals(plan.getString("digest"), banned.getString("digest"))
        assertEquals("reject", service.review(plan.getString("id")).getString("status"))
        assertThrows(StoreBanned::class.java) { manual(service, plan) }
        assertThrows(StoreBanned::class.java) { service.installWithWarnings(plan.getString("id"), plan.getString("digest")) { true } }
        assertThrows(StoreBanned::class.java) { submit(service, plan, report()) }
        // Below the service too: the installer alone refuses the staged plan and a new staging.
        assertThrows(StoreBanned::class.java) { service.installer.apply(plan.getString("id"), banned.getString("installerDigest")) }
        val requests = transport.requests.size
        assertTrue(refused { service.prepare(source, "demo.counter") }.contains("Утечка данных владельца"))
        assertEquals("a banned archive is not even downloaded", requests, transport.requests.size)
        val entry = service.catalog().getJSONObject(0)
        assertEquals("banned", entry.getString("status")); assertEquals(sha, entry.getJSONObject("ban").getString("artifactSha256"))
        assertEquals(0, service.installed().length()); assertFalse(File(workspace, "modules/demo.counter").exists())

        val restarted = service()
        assertEquals(1, restarted.bans.list().length())
        assertThrows(StoreBanned::class.java) { restarted.prepare(source, "demo.counter") }
        assertThrows(StoreBanned::class.java) { manual(restarted, plan) }
    }

    @Test fun newVersionAfterABanIsReviewedAfreshAndShowsWhatTheOldOneDid() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val old = service.prepare(source, "demo.counter")
        submit(service, old, report(finding("agent.manipulation", "violation", path = "package.json", line = 1)))
        transport.publish(StoreServiceFixtures.bundle(version = "1.1.0", main = "exports.value = function () {\n  return 2;\n};\n")); service.sources.refresh(source)
        val entry = service.catalog().getJSONObject(0)
        assertEquals("available", entry.getString("status")); assertFalse(entry.has("ban"))
        val earlier = entry.getJSONArray("previousBans").getJSONObject(0)
        assertEquals("1.0.0", earlier.getString("version")); assertEquals("Попытка управлять агентом", earlier.getString("reason"))
        val plan = service.prepare(source, "demo.counter")
        assertEquals("ready", plan.getString("status")); assertEquals("1.0.0", plan.getJSONArray("previousBans").getJSONObject(0).getString("version"))
        assertEquals("pending", service.review(plan.getString("id")).getString("status"))
        assertEquals("pass", submit(service, plan, report()).getString("status"))
        manual(service, plan)
        assertEquals("1.1.0", service.installed().getJSONObject(0).getString("version"))
        assertEquals(1, service.bans.list().length())
    }

    @Test fun banIsLiftedOnlyByTheOwnersOwnConfirmation() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        val sha = plan.getString("artifactSha256")
        submit(service, plan, report(finding("fraud.deception", "violation")))
        var asked: JSONObject? = null
        assertEquals("cancelled", service.unban(sha) { asked = it; false }.getString("status"))
        assertEquals("the owner is shown what the ban is for", "demo.counter", asked!!.getString("packageId"))
        assertEquals(1, service.bans.list().length()); assertThrows(StoreBanned::class.java) { service.prepare(source, "demo.counter") }
        assertThrows(IllegalArgumentException::class.java) { service.unban("f".repeat(64)) { true } }
        assertEquals("lifted", service.unban(sha) { true }.getString("status"))
        assertEquals(0, service.bans.list().length())
        assertEquals(1, JSONObject(File(state, "bans.json").readText()).getJSONArray("lifted").length())
        // Back to "not reviewed": the release can be staged and looked at again.
        assertEquals("ready", service.plan(plan.getString("id")).getString("status"))
        assertEquals("pending", service.review(plan.getString("id")).getString("status"))
        assertEquals("ready", service.prepare(source, "demo.counter").getString("status"))

        // No agent is given a way to lift a ban or to decide for the owner: not in a review, not in an ordinary chat.
        assertFalse(StoreReviewAccess.TOOLS.any { it.contains("ban") || it.contains("accept") || it.contains("discard") })
        val tools = Regex("tool\\(\"(store_[a-z_]+)\"").findAll(File("src/main/java/app/axiko/agent/Tools.kt").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("store_sources", "store_add_source", "store_catalog", "store_details", "store_prepare", "store_review_start", "store_review_status",
            "store_plan", "store_read", "store_resolve", "store_review", "store_apply",
            // Carrying a mod over: started from an ordinary chat, done with tools that write only into the plan's candidate.
            "store_mod_carry", "store_mod_task", "store_mod_read", "store_mod_copy", "store_mod_write", "store_mod_edit", "store_mod_delete", "store_mod_done"), tools)
        // The executor of a carry-over installs nothing and reviews nothing: its tools end at handing the work in.
        assertFalse(app.axiko.store.StoreAgent.CARRY_TOOLS.any { it.contains("apply") || it.contains("review") || it.contains("prepare") || it.contains("source") })
    }

    @Test fun validatorViolationBansTheReleaseButADamagedDownloadDoesNot() {
        val packed = StoreServiceFixtures.bundle(main = "eval(atob('YWxlcnQoMSk='));\n")
        transport.publish(packed)
        val service = service(); val source = add(service)
        assertTrue(refused { service.prepare(source, "demo.counter") }.contains("Выпуск забанен"))
        val ban = service.bans.list().getJSONObject(0)
        assertEquals("validator", ban.getString("origin")); assertEquals(packed.entry.getJSONObject("archive").getString("sha256"), ban.getString("artifactSha256"))
        val violation = ban.getJSONArray("violations").getJSONObject(0)
        assertEquals("code.obfuscated", violation.getString("rule")); assertEquals("code/main.js", violation.getJSONObject("evidence").getString("path"))
        assertEquals(0, service.plans().length())
        assertThrows(StoreBanned::class.java) { service.prepare(source, "demo.counter") }

        // The next release is clean, but its download arrives damaged: that says nothing about the package.
        val clean = StoreServiceFixtures.bundle(version = "1.0.1"); transport.publish(clean); service.sources.refresh(source)
        val url = clean.entry.getJSONObject("archive").getString("url")
        transport.put(url, clean.zip.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertThrows(StoreSourceFault::class.java) { service.prepare(source, "demo.counter") }
        assertEquals("only the first release is banned", 1, service.bans.list().length())
        val problem = service.sources.source(source).getJSONArray("problems").getJSONObject(0)
        assertEquals("demo.counter", problem.getString("packageId")); assertEquals("1.0.1", problem.getString("version"))
        transport.put(url, clean.zip)
        assertEquals("ready", service.prepare(source, "demo.counter").getString("status"))
        assertEquals(0, service.sources.source(source).optJSONArray("problems")?.length() ?: 0)
    }

    @Test fun binaryAndRemoteCodeAndUndeclaredFilesAreValidatorBans() {
        val service = service()
        transport.publish(StoreServiceFixtures.bundle(extra = mapOf("code/lib.so" to byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1))))
        val source = add(service)
        refused { service.prepare(source, "demo.counter") }
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1", extra = mapOf("code/ui/cdn.html" to "<script src=\"https://cdn.example/x.js\"></script>\n".toByteArray())))
        service.sources.refresh(source)
        refused { service.prepare(source, "demo.counter") }
        val rules = (0 until service.bans.list().length()).map { service.bans.list().getJSONObject(it).getJSONArray("violations").getJSONObject(0).getString("rule") }.toSet()
        assertEquals(setOf("code.binary", "code.remote"), rules)
    }

    // ---- needsAttention: the owner decides ------------------------------------------------------

    @Test fun warningsAreInstalledOnlyByTheOwnersDecisionForTheSameDigest() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        val id = plan.getString("id"); val digest = plan.getString("digest")
        val saved = submit(service, plan, report(finding("network.undeclared", "suspicious"), unknowns = listOf("Не проверен формат ответа сервера.")))
        assertEquals("needsAttention", saved.getString("status")); assertFalse(saved.optBoolean("banned")); assertEquals(0, service.bans.list().length())
        // Neither the manual button nor an agent's apply installs it: both end in the same refusal.
        assertTrue(refused { manual(service, plan) }.contains("несмотря на предупреждения"))
        refused { StoreReviewAccess(File(folder.root, "sessions")).apply { create("axiko", "t1", plan, true) }.requireApply("axiko", "t1", plan, service.review(id)) }
        // The decision is bound to the plan as shown: another digest is not what the owner saw.
        refused { service.installWithWarnings(id, "0".repeat(64)) { true } }
        var shown: JSONObject? = null
        assertEquals("cancelled", service.installWithWarnings(id, digest) { shown = it; false }.getString("status"))
        assertEquals(0, service.installed().length()); assertFalse(File(workspace, "modules/demo.counter").exists())
        val warning = shown!!.getJSONArray("warnings").getJSONObject(0)
        assertEquals("Сеть: необъявленный адрес", warning.getString("title")); assertEquals("code/main.js", warning.getString("path")); assertEquals(2, warning.getInt("line"))
        assertEquals(1, shown!!.getInt("unknowns"))

        val result = service.installWithWarnings(id, digest) { true }
        assertEquals("applied", result.getString("status")); assertEquals("installDespiteWarnings", result.getJSONObject("ownerDecision").getString("decision"))
        val installed = service.installed().getJSONObject(0).getJSONObject("review")
        assertEquals("needsAttention", installed.getString("verdict")); assertEquals(digest, installed.getJSONObject("ownerDecision").getString("planDigest"))
        assertEquals("network.undeclared", installed.getJSONArray("findings").getJSONObject(0).getString("rule"))
        assertEquals(1, installed.getJSONArray("unknowns").length())
        val historical = service.review(id)
        assertTrue(historical.getBoolean("historicalOnly")); assertEquals("installDespiteWarnings", historical.getJSONObject("ownerDecision").getString("decision"))
        // The record stays with the installed package across a restart.
        assertTrue(service().installed().getJSONObject(0).getJSONObject("review").has("ownerDecision"))
    }

    @Test fun aFreshPlanOfAWarnedArchiveDoesNotStartWithACleanSlate() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val first = service.prepare(source, "demo.counter")
        submit(service, first, report(finding("data.sensitive-access", "suspicious")))
        // Preparing again gives a new plan and digest; the archive, and what was found in it, are the same.
        val second = service.prepare(source, "demo.counter")
        assertNotEquals(first.getString("id"), second.getString("id"))
        val pending = service.review(second.getString("id"))
        assertEquals("pending", pending.getString("status")); assertEquals("needsAttention", pending.getJSONObject("inherited").getString("status"))
        assertTrue(refused { manual(service, second) }.contains("несмотря на предупреждения"))
        // A new review that finds nothing clears it; one that changes while the owner decides stops the decision.
        refused { service.installWithWarnings(second.getString("id"), second.getString("digest")) { submit(service, second, report(finding("code.dynamic", "suspicious"))); true } }
        assertEquals(0, service.installed().length())
        assertEquals("pass", submit(service, second, report()).getString("status"))
        refused { service.installWithWarnings(second.getString("id"), second.getString("digest")) { true } }
        manual(service, second)
        assertEquals("pass", service.installed().getJSONObject(0).getJSONObject("review").getString("verdict"))
    }

    @Test fun decliningDiscardsThePlanAndInstallsNothing() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        submit(service, plan, report(finding("background.declared", "suspicious", path = "package.json", line = 1)))
        assertTrue(service.discard(plan.getString("id")).getBoolean("ok"))
        assertEquals(0, service.plans().length()); assertEquals(0, service.installed().length())
        assertThrows(Exception::class.java) { service.plan(plan.getString("id")) }
        val applied = service.prepare(source, "demo.counter")
        service.installWithWarnings(applied.getString("id"), applied.getString("digest")) { true }
        refused { service.discard(applied.getString("id")) }
    }
}
