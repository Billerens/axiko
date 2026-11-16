package app.assi

import app.assi.store.StoreFetch
import app.assi.store.StoreHttpTransport
import app.assi.store.StoreSources
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
