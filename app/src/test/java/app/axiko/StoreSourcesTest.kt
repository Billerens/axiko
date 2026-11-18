package app.axiko

import app.axiko.store.StoreFetch
import app.axiko.store.StoreHttpTransport
import app.axiko.store.StoreSources
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class StoreSourcesTest {
    @get:Rule val folder = TemporaryFolder()
    private val transport = StoreServiceFixtures.Transport()
    private fun sources() = StoreSources(File(folder.root, "store"), transport)

    @Test fun indexIsMandatoryAndMalformedSourcesNeverEnterRegistry() {
        val sources = sources()
        assertThrows(IllegalArgumentException::class.java) { sources.add("https://catalog.example/package.zip") }
        assertEquals(0, transport.requests.size)
        transport.put(StoreServiceFixtures.INDEX, "{}".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { sources.add(StoreServiceFixtures.INDEX) }
        assertEquals(0, sources.list().length())
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry()))
        val added = sources.add(StoreServiceFixtures.INDEX)
        assertEquals("demo.catalog", added.getString("repositoryId"))
        assertEquals(1, sources.cached(added.getString("id")).getJSONArray("packages").length())
        sources.add(StoreServiceFixtures.INDEX)
        assertEquals(1, sources.list().length())
    }

    @Test fun factorySourceIsConnectedOnceAndStaysRemovedWhenTheOwnerRemovesIt() {
        val sources = sources()
        // No network yet: nothing is recorded, so the next look at the store tries again.
        assertThrows(IOException::class.java) { sources.ensureFactory(StoreServiceFixtures.INDEX) }
        assertFalse(sources.factorySettled()); assertEquals(0, sources.list().length())
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry()))
        val added = sources.ensureFactory(StoreServiceFixtures.INDEX)!!
        assertTrue(sources.factorySettled()); assertEquals(1, sources.list().length())
        assertNull(sources.ensureFactory(StoreServiceFixtures.INDEX))
        sources.remove(added.getString("id"))
        assertNull(sources().ensureFactory(StoreServiceFixtures.INDEX)); assertEquals(0, sources().list().length())
    }

    @Test fun factorySourceAlreadyAddedByHandIsKeptAsItIs() {
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry()))
        val sources = sources(); val byHand = sources.add(StoreServiceFixtures.INDEX)
        sources.enable(byHand.getString("id"), false)
        assertEquals(byHand.getString("id"), sources.ensureFactory(StoreServiceFixtures.INDEX)!!.getString("id"))
        assertEquals(1, sources.list().length()); assertFalse(sources.list().getJSONObject(0).getBoolean("enabled"))
    }

    private fun entries() = listOf("demo.alpha", "demo.beta", "demo.gamma").map { StoreServiceFixtures.entry(it) }
    private fun requested(from: Int) = transport.requests.drop(from).map { it.url.substringAfter("https://catalog.example/") }

    @Test fun pagedCatalogFetchesOnlyChangedPagesAndIsTakenWholeOrNotAtAll() {
        val first = entries()
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(*first.toTypedArray(), perPage = 1))
        val sources = sources(); val id = sources.add(StoreServiceFixtures.INDEX).getString("id")
        assertEquals(listOf("index.json", "catalog/p-0.json", "catalog/p-1.json", "catalog/p-2.json"), requested(0))
        assertEquals(3, sources.source(id).getInt("packageCount")); assertEquals(3, sources.cached(id).getJSONArray("packages").length())

        // One package is released: its page alone is downloaded, the other two are the files already kept.
        val second = first.map { JSONObject(it.toString()) }.also { it[1].put("version", "1.1.0").getJSONObject("archive").put("sha256", "1".repeat(64)) }
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(*second.toTypedArray(), perPage = 1))
        val before = transport.requests.size
        sources.refresh(id)
        assertEquals(listOf("index.json", "catalog/p-1.json"), requested(before))
        assertEquals("1.1.0", sources.packageEntry(id, "demo.beta").getString("version"))

        // A page that is not what the head pins, a page with another count, an ID on two pages: nothing of such a catalog is taken.
        val third = second.map { JSONObject(it.toString()) }.also { it[2].put("version", "2.0.0").getJSONObject("archive").put("sha256", "2".repeat(64)) }
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(*third.toTypedArray(), perPage = 1))
        transport.put("https://catalog.example/catalog/p-2.json", StoreServiceFixtures.page(third[2].put("summary", "Swapped on the way")))
        assertThrows(IllegalArgumentException::class.java) { sources.refresh(id) }
        transport.responses.remove("https://catalog.example/catalog/p-2.json")
        val twice = JSONObject(String(StoreServiceFixtures.index(*second.toTypedArray(), perPage = 1)))
        twice.getJSONArray("pages").put(JSONObject(twice.getJSONArray("pages").getJSONObject(0).toString()).put("path", "catalog/p-9.json")); twice.put("packageCount", 4)
        transport.put(StoreServiceFixtures.INDEX, twice.toString().toByteArray())
        transport.put("https://catalog.example/catalog/p-9.json", StoreServiceFixtures.page(second[0]))
        assertThrows(IllegalArgumentException::class.java) { sources.refresh(id) }
        assertEquals("1.0.0", sources.packageEntry(id, "demo.gamma").getString("version")); assertEquals(3, sources.cached(id).getJSONArray("packages").length())
        assertTrue(sources.source(id).getString("error").isNotBlank())
        // The catalog survives a restart from what is on disk, and removing the source removes its pages.
        assertEquals(3, sources().cached(id).getJSONArray("packages").length())
        sources.remove(id); assertFalse(File(folder.root, "store/catalogs/$id").exists())
    }

    @Test fun detailsAndPicturesAreFetchedOnDemandPinnedByHashAndKept() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"><path d=\"M1 1h6v6H1z\"/></svg>".toByteArray()
        val details = JSONObject().put("schemaVersion", 1).put("id", "demo.counter").put("version", "1.0.0").put("description", "A **counter**.")
            .put("changes", JSONArray().put(JSONObject().put("version", "1.0.0").put("notes", "First release."))).toString().toByteArray()
        fun pinned(path: String, raw: ByteArray) = JSONObject().put("path", path).put("sha256", app.axiko.store.StoreFormat.sha256(raw)).put("size", raw.size)
        val entry = StoreServiceFixtures.entry().put("details", pinned("catalog/details/demo.counter.json", details))
            .put("icon", pinned("counter/store/icon.svg", svg).put("type", "image/svg+xml"))
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(entry))
        transport.put("https://catalog.example/catalog/details/demo.counter.json", details)
        transport.put("https://catalog.example/counter/store/icon.svg", svg)
        val sources = sources(); val id = sources.add(StoreServiceFixtures.INDEX).getString("id")
        val before = transport.requests.size
        assertEquals("A **counter**.", sources.details(id, "demo.counter").getString("description"))
        assertArrayEquals(svg, sources.picture(id, entry.getJSONObject("icon")))
        assertEquals(2, transport.requests.size - before)
        // Asked again, and after a restart, both come from the files kept by hash.
        sources.details(id, "demo.counter"); sources().picture(id, entry.getJSONObject("icon"))
        assertEquals(2, transport.requests.size - before)
        assertEquals(svg.size, transport.requests.last().limit)

        // What arrives must be exactly what the catalog pinned, and a picture must be the type it is declared to be.
        val script = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>steal()</script></svg>".toByteArray()
        transport.put("https://catalog.example/counter/store/bad.svg", script)
        assertThrows(IllegalArgumentException::class.java) { sources.picture(id, pinned("counter/store/bad.svg", script).put("type", "image/svg+xml")) }
        assertThrows(IllegalArgumentException::class.java) { sources.picture(id, pinned("counter/store/bad.svg", script).put("type", "image/png")) }
        transport.put("https://catalog.example/counter/store/other.svg", svg)
        assertThrows(IllegalArgumentException::class.java) { sources.picture(id, pinned("counter/store/other.svg", script).put("type", "image/svg+xml")) }
        val other = JSONObject(String(details)).put("version", "9.9.9").toString().toByteArray()
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry().put("details", pinned("catalog/details/demo.counter.json", other))))
        transport.put("https://catalog.example/catalog/details/demo.counter.json", other)
        sources.refresh(id)
        assertThrows(IllegalArgumentException::class.java) { sources.details(id, "demo.counter") }
        // A package without details still opens: there is simply nothing more to show.
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry())); sources.refresh(id)
        assertFalse(sources.details(id, "demo.counter").has("description"))
    }

    @Test fun githubRepoUsesItsActualDefaultBranchAndRootIndex() {
        transport.put("https://api.github.com/repos/example/catalog", "{\"default_branch\":\"stable\"}".toByteArray())
        transport.put("https://raw.githubusercontent.com/example/catalog/stable/index.json", StoreServiceFixtures.index())
        val source = sources().add("https://github.com/example/catalog.git/")
        assertEquals("https://raw.githubusercontent.com/example/catalog/stable/index.json", source.getString("indexUrl"))
        assertEquals(2, transport.requests.size)
    }

    @Test fun conditionalRefreshAndOfflineFailurePreserveValidatedCache() {
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry()), "\"v1\"")
        val sources = sources(); val id = sources.add(StoreServiceFixtures.INDEX).getString("id")
        val digest = sources.source(id).getString("indexSha256")
        transport.conditional = true
        sources.refresh(id)
        assertEquals("\"v1\"", transport.requests.last().etag)
        assertEquals(digest, sources.source(id).getString("indexSha256"))
        transport.responses.clear()
        assertThrows(IOException::class.java) { sources.refresh(id) }
        assertEquals(1, sources.cached(id).getJSONArray("packages").length())
        assertTrue(sources.source(id).getString("error").isNotBlank())
        assertTrue(sources.source(id).getLong("lastSuccess") > 0)
    }

    @Test fun sourceIdentityPublisherAndSameVersionHashCannotSilentlyChange() {
        val entry = StoreServiceFixtures.entry()
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(entry))
        val sources = sources(); val id = sources.add(StoreServiceFixtures.INDEX).getString("id")
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(entry, id = "other.catalog"))
        assertThrows(IllegalArgumentException::class.java) { sources.refresh(id) }
        val changedPublisher = JSONObject(entry.toString()).put("publisher", "other")
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(changedPublisher))
        assertThrows(IllegalArgumentException::class.java) { sources.refresh(id) }
        val changedZip = JSONObject(entry.toString()).apply { getJSONObject("archive").put("sha256", "f".repeat(64)) }
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(changedZip))
        assertThrows(IllegalArgumentException::class.java) { sources.refresh(id) }
        assertEquals("demo", sources.cached(id).getJSONArray("packages").getJSONObject(0).getString("publisher"))
        assertEquals("0".repeat(64), sources.cached(id).getJSONArray("packages").getJSONObject(0).getJSONObject("archive").getString("sha256"))
    }

    @Test fun disablingAndRemovingSourcesOnlyChangeSourceState() {
        transport.put(StoreServiceFixtures.INDEX, StoreServiceFixtures.index(StoreServiceFixtures.entry()))
        val sources = sources(); val id = sources.add(StoreServiceFixtures.INDEX).getString("id")
        val installed = File(folder.root, "user-data/save.json").apply { parentFile.mkdirs(); writeText("preserve me") }
        sources.enable(id, false)
        assertThrows(IllegalArgumentException::class.java) { sources.packageEntry(id, "demo.counter") }
        assertEquals(1, sources.cached(id).getJSONArray("packages").length())
        assertTrue(sources.remove(id).getBoolean("installedKept"))
        assertEquals(0, sources.list().length()); assertEquals("preserve me", installed.readText())
    }

    @Test fun injectedTransportCannotBypassResponseByteOrSchemeLimits() {
        val sources = sources()
        transport.put(StoreServiceFixtures.INDEX, ByteArray(StoreSources.MAX_METADATA_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { sources.add(StoreServiceFixtures.INDEX) }
        transport.responses[StoreServiceFixtures.INDEX] = StoreFetch(StoreServiceFixtures.index(), 200, null, null, "http://downgrade.example/index.json")
        assertThrows(IllegalArgumentException::class.java) { sources.add(StoreServiceFixtures.INDEX) }
        assertEquals(0, sources.list().length())
    }

    @Test fun httpTransportRejectsCleartextAndCredentialsBeforeMakingAnyRequest() {
        val http = StoreHttpTransport()
        for (url in listOf("http://example.org/index.json", "https://user:pass@example.org/index.json", "https://example.org/index.json#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { http.get(url, 1024, null, null) }
        }
    }

    @Test fun realHttpBodyAndRedirectChecksStayBoundedWithMockedLogicalHttpsEndpoints() {
        val server = MockWebServer(); server.start()
        try {
            // The injected client maps logical HTTPS endpoints to the isolated mock server.
            // Production Store uses OkHttp's normal TLS client; the transport's HTTPS guard is unchanged.
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
                    .newBuilder().request(original).build()
            }.build()
            val http = StoreHttpTransport(client)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://cdn.example/index.json"))
            server.enqueue(MockResponse().setBody("{}"))
            assertEquals("https://cdn.example/index.json", http.get("https://origin.example/index.json", 32, "\"tag\"", null).finalUrl)
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!; val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("\"tag\"", first.getHeader("If-None-Match")); assertNull(second.getHeader("If-None-Match"))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(100), 7))
            assertThrows(IllegalArgumentException::class.java) { http.get("https://origin.example/huge", 40, null, null) }
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://plain.example/index.json"))
            assertThrows(IllegalArgumentException::class.java) { http.get("https://origin.example/down", 40, null, null) }
            repeat(5) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://origin.example/redirect-$it")) }
            assertThrows(IllegalArgumentException::class.java) { http.get("https://origin.example/start", 40, null, null) }
        } finally { server.shutdown() }
    }
}
