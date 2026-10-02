package app.cablegram.phone

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageProvidersTest {
    private val drive = StorageProviderInfo("google_drive", "Google Drive", "oauth", true)
    private val r2 = StorageProviderInfo("cloudflare_r2", "Cloudflare R2", "keys", true)

    private fun status(
        providers: List<StorageProviderInfo> = listOf(drive, r2),
        connection: StorageConnection? = null,
        connections: List<StorageConnection> = listOfNotNull(connection),
    ) = StorageStatusResponse(configured = true, providers = providers, connection = connection, connections = connections)

    private val driveActive = StorageConnection(
        id = "c1", provider = "google_drive", status = "active", accountLabel = "me@gmail.com", locationLabel = "Cablegram",
        displayLabel = "Google Drive · me@gmail.com", quota = StorageQuota(2_684_354_560, 16_106_127_360),
    )

    // ---- the provider list ----

    @Test fun `with nothing connected both configured providers are offered, in the server's order`() {
        val rows = providerRows(status())
        assertEquals(listOf("google_drive", "cloudflare_r2"), rows.map { it.id })
        assertTrue(rows.all { it.state == ProviderState.Available && it.note == null })
        assertEquals("Sign in with Google", rows[0].summary)
        assertEquals("about 1 minute", rows[0].duration)
        assertEquals("Use your own bucket and keys", rows[1].summary)
        assertTrue(rows[1].duration.contains("advanced users"))
        assertEquals(listOf("cloudflare_r2", "google_drive"), providerRows(status(providers = listOf(r2, drive))).map { it.id })
    }

    @Test fun `a provider the server is not set up for is not listed`() {
        assertEquals(listOf("cloudflare_r2"), providerRows(status(providers = listOf(drive.copy(configured = false), r2))).map { it.id })
        assertTrue(providerRows(status(providers = listOf(drive.copy(configured = false), r2.copy(configured = false)))).isEmpty())
        assertFalse(hasOwnStorageProvider(status(providers = emptyList())))
        assertFalse(hasOwnStorageProvider(null))
        assertTrue(hasOwnStorageProvider(status()))
    }

    @Test fun `a provider this build does not know is left out, not shown broken`() {
        val dropbox = StorageProviderInfo("dropbox", "Dropbox", "oauth", true)
        assertEquals(listOf("google_drive"), providerRows(status(providers = listOf(drive, dropbox))).map { it.id })
    }

    @Test fun `while one is connected it is marked and the others are turned off with a reason`() {
        val rows = providerRows(status(connection = driveActive))
        assertEquals(ProviderState.Connected, rows.first { it.id == "google_drive" }.state)
        val r2Row = rows.first { it.id == "cloudflare_r2" }
        assertEquals(ProviderState.Locked, r2Row.state)
        assertEquals("Disconnect Google Drive to switch", r2Row.note)
        val other = providerRows(status(connection = StorageConnection(provider = "cloudflare_r2", status = "active")))
        assertEquals("Disconnect Cloudflare R2 to switch", other.first { it.id == "google_drive" }.note)
    }

    @Test fun `a connection that needs signing in again is marked on its own row and nothing is locked`() {
        val broken = driveActive.copy(status = "error", lastError = "reauthorization_required", quota = null)
        val rows = providerRows(status(connection = null, connections = listOf(broken)))
        assertEquals(ProviderState.NeedsSignIn, rows.first { it.id == "google_drive" }.state)
        assertEquals("Sign in again to keep using it", rows.first { it.id == "google_drive" }.note)
        assertEquals(ProviderState.Available, rows.first { it.id == "cloudflare_r2" }.state)
    }

    @Test fun `no status yet lists nothing`() {
        assertTrue(providerRows(null).isEmpty())
    }

    // ---- the connected card ----

    @Test fun `the connected card shows the account and the free space`() {
        assertEquals("Google Drive · me@gmail.com · 12.5 GB free", connectedSummary(driveActive))
        assertEquals(13_421_772_800L, freeSpace(driveActive.quota))
    }

    @Test fun `a provider with no reported limit shows no free space`() {
        val r2Active = StorageConnection(provider = "cloudflare_r2", status = "active", displayLabel = "R2 · my-movies", quota = StorageQuota(5, null))
        assertEquals("R2 · my-movies", connectedSummary(r2Active))
        assertNull(freeSpace(null))
        assertNull(freeSpace(StorageQuota(10, null)))
        assertEquals(0L, freeSpace(StorageQuota(20, 10)))
        assertEquals("Google Drive", connectedSummary(StorageConnection(provider = "google_drive", status = "active"), status()))
    }

    // ---- the words on the save sheets ----

    @Test fun `save sheets name the destination`() {
        assertEquals("Cablegram Cloud", saveDestination(status()))
        assertEquals("Cablegram Cloud", saveDestination(null))
        assertEquals("Google Drive", saveDestination(status(connection = driveActive)))
        assertEquals("Cloudflare R2", saveDestination(status(connection = StorageConnection(provider = "cloudflare_r2", status = "active"))))
        assertEquals("Cablegram Cloud", saveDestination(status(connection = null, connections = listOf(driveActive.copy(status = "error")))))
    }

    @Test fun `the space line is the provider's free space, not an absurd cloud figure`() {
        assertEquals("12.5 GB free", destinationSpaceLine(status(connection = driveActive), cablegramAvailable = Long.MAX_VALUE / 4))
        assertEquals("In your own storage", destinationSpaceLine(status(connection = StorageConnection(provider = "cloudflare_r2", status = "active")), Long.MAX_VALUE / 4))
        assertEquals("5.0 GB available", destinationSpaceLine(status(), 5L * 1024 * 1024 * 1024))
    }

    // ---- the link back from the sign-in ----

    @Test fun `the link back is read, and anything else is refused`() {
        assertEquals(StorageReturn("google_drive", "connected"), parseStorageReturn("cablegram://storage?provider=google_drive&result=connected"))
        assertEquals(StorageReturn("google_drive", "scope_missing"), parseStorageReturn(" cablegram://storage?provider=google_drive&result=scope_missing "))
        assertNull(parseStorageReturn("cablegram://pair?token=abc"))
        assertNull(parseStorageReturn("https://storage?provider=google_drive&result=connected"))
        assertNull(parseStorageReturn("cablegram://storage/extra?provider=google_drive&result=connected"))
        assertNull(parseStorageReturn("cablegram://storage?provider=google_drive"))
        assertNull(parseStorageReturn("cablegram://storage?result=connected"))
        assertNull(parseStorageReturn("cablegram://storage?provider=google_drive&result=Connected%20now"))
        assertNull(parseStorageReturn("cablegram://storage?provider=google_drive&result=connected#frag"))
        assertNull(parseStorageReturn("not a uri at all"))
    }

    @Test fun `every result has its own message and none repeats what the link said`() {
        fun msg(result: String) = storageReturnMessage(StorageReturn("google_drive", result), status())
        assertEquals("Connected to Google Drive.", msg("connected"))
        assertTrue(msg("denied").startsWith("Not connected"))
        assertTrue(msg("scope_missing").contains("access wasn't granted"))
        assertTrue(msg("expired").contains("expired"))
        assertTrue(msg("already_connected").contains("already connected"))
        assertTrue(msg("failed").contains("Try again"))
        assertEquals("the same as failed, without echoing the code", msg("failed").replace("failed", ""), msg("<script>").replace("<script>", ""))
        assertFalse(msg("<script>").contains("script"))
        assertEquals(6, listOf("connected", "denied", "scope_missing", "expired", "already_connected", "failed").map(::msg).toSet().size)
    }

    @Test fun `a sign-in that could not start says why in words`() {
        assertEquals("Google Drive isn't set up on this server.", googleStartMessage("provider_not_configured"))
        assertTrue(googleStartMessage("storage_already_connected").contains("already connected"))
        assertTrue(googleStartMessage("offline").contains("connection"))
        assertEquals("Couldn't start the Google sign-in.", googleStartMessage("http_500"))
    }

    @Test fun `disconnecting says where the files stay`() {
        assertEquals("Google Drive disconnected. Your files stay in your Drive.", disconnectMessage("google_drive"))
        assertTrue(disconnectMessage("cloudflare_r2").contains("bucket"))
        assertTrue(disconnectMessage("cloudflare_r2").contains("Cloudflare"))
        assertFalse(disconnectMessage("google_drive").contains("Cloudflare"))
    }

    // ---- what the server sends ----

    @Test fun `the status the server sends is read, including an older server that sends no providers`() {
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<StorageStatusResponse>(
            """{"configured":true,"defaultBackend":"none","providers":[{"id":"google_drive","name":"Google Drive","connectMethod":"oauth","configured":true}],
               "connection":{"id":"x","provider":"google_drive","status":"active","bucketName":null,"accountLabel":"me@gmail.com","locationLabel":"Cablegram",
               "displayLabel":"Google Drive · me@gmail.com","connectedAt":"2026-10-01T19:23:03.986Z","lastError":null,"quota":{"usedBytes":264444,"limitBytes":16106127360}},
               "connections":[]}""",
        )
        assertEquals("me@gmail.com", parsed.connection?.accountLabel)
        assertEquals(16_106_127_360L, parsed.connection?.quota?.limitBytes)
        assertEquals(listOf("google_drive"), providerRows(parsed.copy(connection = null)).map { it.id })
        val old = json.decodeFromString<StorageStatusResponse>("""{"configured":true,"connection":null,"connections":[]}""")
        assertTrue(providerRows(old).isEmpty())
    }

    // ---- reading a copy back ----

    @Test fun `the read-back answer carries the Drive header and none for R2`() {
        val drive = parseReadUrl("""{"url":"https://www.googleapis.com/drive/v3/files/f1?alt=media","expires_at":"2026-10-02T12:50:00Z","headers":{"Authorization":"Bearer ya29.x"}}""")!!
        assertEquals("https://www.googleapis.com/drive/v3/files/f1?alt=media", drive.url)
        assertEquals(mapOf("Authorization" to "Bearer ya29.x"), drive.headers)
        val r2 = parseReadUrl("""{"url":"https://acct.r2.cloudflarestorage.com/a?X-Amz-Signature=s","expires_at":"x","headers":{}}""")!!
        assertTrue(r2.headers.isEmpty())
        assertTrue("an older server sends no headers", parseReadUrl("""{"url":"https://acct.r2.cloudflarestorage.com/a"}""")!!.headers.isEmpty())
    }

    @Test fun `a read-back answer that is not a plain HTTPS URL, or has odd headers, is not trusted`() {
        assertNull(parseReadUrl("""{"url":"http://insecure/a"}"""))
        assertNull(parseReadUrl("""{"url":"file:///etc/passwd"}"""))
        assertNull(parseReadUrl("""{}"""))
        assertNull(parseReadUrl("not json"))
        val odd = parseReadUrl("""{"url":"https://x.example/a","headers":{"Good-Name":"v","bad name":"v","Inject":"a\r\nX: y"}}""")!!
        assertEquals(mapOf("Good-Name" to "v"), odd.headers)
    }

    @Test fun `the read-back URL and token never reach a log line`() {
        val text = ReadUrl("https://www.googleapis.com/drive/v3/files/f1?alt=media", mapOf("Authorization" to "Bearer SECRET")).toString()
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("googleapis"))
    }

    @Test fun `a failed download is explained in words about the cloud, not Cloudflare`() {
        assertTrue(cloudDownloadMessage(401).contains("sign in again"))
        assertTrue(cloudDownloadMessage(403).contains("limiting downloads"))
        assertTrue(cloudDownloadMessage(429).contains("limiting downloads"))
        assertTrue(cloudDownloadMessage(404).contains("no longer in your cloud storage"))
        assertEquals("Your cloud storage didn't send the video (500).", cloudDownloadMessage(500))
        assertFalse(listOf(401, 403, 404, 500).any { cloudDownloadMessage(it).contains("Cloudflare") })
    }

    // ---- removing just the cloud copy ----

    private fun title(copied: Boolean = true, cloudCopy: Boolean = true, sourceId: String? = "src-1", status: String = TRANSFER_IDLE) = LibraryItem(
        id = "t1", title = "Dune", filename = "Dune.mkv", importedAt = "2026-01-01T00:00:00Z", copied = copied,
        ownCloudCopy = cloudCopy, ownCloudSourceId = sourceId, transferStatus = status,
    )

    @Test fun `the remove-copy button is offered only for a copy the phone still has a video behind`() {
        assertTrue(canRemoveOwnCloudCopy(title()))
        assertFalse("the only copy is not removed from here", canRemoveOwnCloudCopy(title(copied = false)))
        assertFalse("no cloud copy", canRemoveOwnCloudCopy(title(cloudCopy = false)))
        assertFalse("the server never named it", canRemoveOwnCloudCopy(title(sourceId = null)))
        assertFalse("not while it is being saved", canRemoveOwnCloudCopy(title(status = TRANSFER_SAVING)))
    }

    @Test fun `the copy to remove is the title's usable own-storage source`() {
        fun src(id: String?, kind: String = "own_cloud", availability: String? = "available", archive: String? = null) =
            RemoteCatalogSource(id = id, kind = kind, availability = availability, archiveState = archive)
        assertEquals("c1", ownCloudSourceIdOf(listOf(src("p1", kind = "phone_local"), src("c1"))))
        assertNull(ownCloudSourceIdOf(listOf(src("p1", kind = "phone_local"))))
        assertNull("a copy that is unavailable cannot be removed from here", ownCloudSourceIdOf(listOf(src("c1", availability = "unavailable"))))
        assertNull(ownCloudSourceIdOf(listOf(src("c1", archive = "archived"))))
        assertNull("an older server sends no id", ownCloudSourceIdOf(listOf(src(null))))
    }

    @Test fun `the confirmation says what is deleted and what stays`() {
        val text = removeCopyQuestion("Dune", "Google Drive")
        assertTrue(text.contains("\"Dune\""))
        assertTrue(text.contains("from Google Drive"))
        assertTrue(text.contains("stays on this phone"))
    }

    @Test fun `every ending of a remove has words, and none repeats provider text`() {
        fun msg(error: String?) = removeCopyMessage(RemoveCopyResult(error), "Google Drive")
        assertEquals("Removed the copy from Google Drive. The video stays on this phone.", msg(null))
        assertEquals("a copy already gone counts as removed", msg(null), msg("not_found"))
        assertTrue(msg("reauthorization_required").contains("sign in again"))
        assertTrue(msg("storage_unavailable").contains("Try again later"))
        assertTrue(msg("provider_rate_limited").contains("limiting requests"))
        assertTrue(msg("offline").contains("connection"))
        assertEquals("Couldn't remove the copy from Google Drive.", msg("http_500"))
        assertFalse(msg("<script>alert(1)</script>").contains("script"))
    }
}
