package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CatalogClientMetadataTest {
    private val response = """{"id":"shared","title":"1917","year":null,"overview":null,"media_type":"movie","metadata_revision":1,"user_metadata_fields":["year","overview"]}"""
    private fun edited() = commitManualMetadata(
        LibraryItem("local", "1917", "1917.mkv", year = 2019, overview = "Old", importedAt = "2026-10-03", catalogItemId = "shared"),
        "1917", null, "movie", "",
    )

    @Test fun `metadata sync sends only edited fields with explicit null and the server id`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(response))
            val item = edited()
            val result = runBlocking { CatalogClient(server.url("/").toString()).patchMetadata("token", item) }
            assertTrue(result is MetadataSyncResult.Saved)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/catalog/items/shared", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("year", "overview", "expected_revision", "edit_id"), body.keys)
            assertEquals(JsonNull, body["year"])
            assertEquals(JsonNull, body["overview"])
            assertEquals(item.metadataEditId, body["edit_id"]?.jsonPrimitive?.content)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `conflicts and older servers preserve pending work`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"metadata_conflict","current":$response}"""))
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setBody("""{"id":"shared","title":"1917","origin_filename":"1917.mkv"}"""))
            val client = CatalogClient(server.url("/").toString())
            runBlocking {
                assertTrue(client.patchMetadata("token", edited()) is MetadataSyncResult.Conflict)
                assertEquals(MetadataSyncResult.Failed, client.patchMetadata("token", edited()))
                assertEquals(MetadataSyncResult.Failed, client.patchMetadata("token", edited()))
            }
        }
    }

    @Test fun `source registration does not send unacknowledged manual fields before revision checking`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"id":"shared"}"""))
            val item = commitManualMetadata(edited(), "My title", null, "tv", null).copy(posterUrl = "https://example.com/p.jpg")
            var importedId: String? = null
            assertTrue(runBlocking { CatalogClient(server.url("/").toString()).pushLibraryItem("token", item, onImported = { importedId = it }) })
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(body.containsKey("title"))
            assertFalse(body.containsKey("media_type"))
            assertEquals("shared", importedId)
        }
    }
}
