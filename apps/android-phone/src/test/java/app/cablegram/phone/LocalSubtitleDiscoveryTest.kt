package app.cablegram.phone

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class LocalSubtitleDiscoveryTest {
    private val context = SubtitleSourceContext("source", "version", "Film.2020.1080p.mkv", "Film", 2020, 123, duration = 3600.0)
    private val text = "1\n00:00:10,000 --> 00:00:12,000\nHello\n"
    private fun client(handler: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").header("Retry-After", "2").body(body.toResponseBody()).build()
    }.build()
    private fun subdl(key: String = "secret-canary", handler: (Request) -> Pair<Int, String>) = LocalSubtitleDiscovery(mapOf("subdl" to PersonalSubtitleCredentials(key)), client = client(handler))
    private fun search(url: String = "/subtitle/123-456.zip?api_key=secret-canary") = """{"status":true,"results":[{"tmdb_id":123}],"subtitles":[{"url":"$url","language":"English","name":"Film.srt","release_name":"Film.2020.1080p"}]}"""
    @Test fun noKeysNeverCallsProvider() = runBlocking {
        val engine = LocalSubtitleDiscovery(emptyMap(), client = client { error("unexpected provider call") })
        assertEquals("providers_not_configured", engine.discover(context, SubtitlePreferences(), null, null).status); engine.close()
    }
    @Test fun phoneRequestsStripSecretsAndUploadOnlyChosenTrack() = runBlocking {
        val requests = mutableListOf<Request>()
        val engine = subdl { r -> requests += r; 200 to if (r.url.host == "api.subdl.com") search() else text }
        val found = engine.discover(context, SubtitlePreferences(listOf("en")), null, null)
        assertEquals(2, requests.size); assertEquals("secret-canary", requests[0].url.queryParameter("api_key"))
        assertNull(requests[1].url.query); assertEquals("secret-canary", requests[1].header("x-api-key"))
        val match = found.candidates.single(); assertFalse(match.alignment!!.verified)
        val selected = Json.encodeToString(SelectedSubtitleTrack.serializer(), engine.selected(match))
        for (private in listOf("secret-canary", "fingerprint", "technical", "download", "api_key")) assertFalse(selected.contains(private))
        assertEquals("123-456", engine.selected(match).providerRef)
        engine.close()
        try { engine.selected(match); fail("closed credentials retained") } catch (_: CancellationException) {}
    }
    @Test fun invalidQuotaMalformedAndUnsafeProviderFailuresAreBounded() = runBlocking {
        for ((code, expected) in listOf(401 to "invalid_credentials", 402 to "quota_exhausted", 429 to "provider_rate_limited", 500 to "provider_unavailable")) {
            val engine = subdl { code to "credential-bearing-provider-error" }
            val found = engine.discover(context, SubtitlePreferences(), null, null)
            assertEquals(expected, found.errors.single().code); engine.close()
        }
        for (payload in listOf("not json", "x".repeat(2_000_001), search("https://evil.example/subtitle/1.zip"))) {
            val engine = subdl { 200 to payload }
            val found = engine.discover(context, SubtitlePreferences(), null, null)
            assertTrue(found.candidates.isEmpty()); assertEquals(1, found.errors.size); engine.close()
        }
    }
    @Test fun remainingLanguagesSearchOnceAndUncheckedCandidateDownloadsOnOpen() = runBlocking {
        var searches = 0; var downloads = 0; val languages = mutableListOf<String?>()
        val engine = subdl { r ->
            if (r.url.host == "api.subdl.com") {
                searches++; languages += r.url.queryParameter("languages")
                200 to """{"status":true,"subtitles":[${(1..8).joinToString(",") { """{"url":"/subtitle/$it.zip?api_key=secret-canary","language":"English","name":"Film.srt"}""" }}]}"""
            } else { downloads++; 200 to text }
        }
        val prefs = SubtitlePreferences(listOf("en")); val found = engine.discover(context, prefs, null, null)
        assertEquals(1, searches); assertEquals(5, downloads)
        val unchecked = found.candidates.first { it.cues == null }; val prepared = engine.prepare(unchecked)
        assertEquals(6, downloads); assertNotNull(prepared.cues); engine.prepare(unchecked); assertEquals(6, downloads)
        engine.discover(context, prefs.copy(languages = listOf("fr", "de")), null, null)
        assertEquals(listOf("EN", "FR,DE"), languages)
        try { engine.discover(context.copy(identity = "replaced"), prefs, null, null); fail("accepted changed context") } catch (e: LocalSubtitleError) { assertEquals("source_identity_changed", e.code) }
        engine.close()
    }
    @Test fun opensubtitlesRequiresPersonalLoginAndUsesReturnedAllowlistedHost() = runBlocking {
        val seen = mutableListOf<Request>()
        val engine = LocalSubtitleDiscovery(mapOf("opensubtitles" to PersonalSubtitleCredentials("os-key", "me", "my-password")), client = client { r ->
            seen += r
            200 to when (r.url.encodedPath) {
                "/api/v1/login" -> """{"token":"personal-token","base_url":"vip-api.opensubtitles.com"}"""
                "/api/v1/subtitles" -> """{"data":[{"attributes":{"language":"en","files":[{"file_id":123,"file_name":"f.srt"}]}}]}"""
                "/api/v1/download" -> """{"link":"https://dl.opensubtitles.com/download/fixture?token=download-secret"}"""
                else -> text
            }
        })
        val found = engine.discover(context, SubtitlePreferences(listOf("en")), null, null)
        assertEquals(4, seen.size); assertEquals("api.opensubtitles.com", seen[0].url.host)
        assertEquals("vip-api.opensubtitles.com", seen[1].url.host); assertEquals("Bearer personal-token", seen[2].header("Authorization"))
        assertNull(seen[3].header("Authorization")); assertNull(seen[3].header("Api-Key"))
        assertFalse(Json.encodeToString(SelectedSubtitleTrack.serializer(), engine.selected(found.candidates.single())).contains("token")); engine.close()
    }
    @Test fun providerCancellationPropagatesAndCloseCancelsWork() = runBlocking {
        val began = java.util.concurrent.CountDownLatch(1)
        val engine = subdl { began.countDown(); Thread.sleep(100); throw java.io.IOException("timeout-secret") }
        val task = async { engine.discover(context, SubtitlePreferences(), null, null) }
        withContext(Dispatchers.IO) { assertTrue(began.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        engine.close()
        try { task.await(); fail("close failed to cancel") } catch (_: CancellationException) {}
    }
    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream(); ZipOutputStream(out).use { zip -> for ((name, body) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() } }; return out.toByteArray()
    }
    @Test fun archiveSafetyExactEpisodeAssAndAmbiguity() {
        val ass = "[Script Info]\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,{\\i1}Hello\\Nthere"
        val bytes = archive("S01E01.srt" to text, "S01E06.ass" to ass)
        val decoded = LocalSubtitleTimeline.decode(bytes, "en", 1, 6)
        assertEquals("Hello\nthere", LocalSubtitleTimeline.parse(decoded).single().text)
        for (bad in listOf(archive("S01E06.a.srt" to text, "S01E06.b.srt" to text), archive("../S01E06.srt" to text), archive("S01E06.srt" to "x".repeat(2_000_001)), byteArrayOf(0x50, 0x4b))) {
            try { LocalSubtitleTimeline.decode(bad, "en", 1, 6); fail("unsafe archive accepted") } catch (_: LocalSubtitleError) {}
        }
        assertNull(LocalSubtitleTimeline.pickEpisode(listOf("S02E06.srt", "S01E01.srt"), 1, 6))
    }
    private fun dense(): List<SubtitleCue> {
        var seed = 41L; var at = 5.0; val cues = mutableListOf<SubtitleCue>()
        while (at < 3500) { seed = seed * 16807 % 2147483647; val length = 1 + seed % 35 / 10.0; cues += SubtitleCue(at, at + length, "line"); seed = seed * 16807 % 2147483647; at += length + 1 + seed % 70 / 10.0 }
        return cues
    }
    private fun fingerprint(cues: List<SubtitleCue>, starts: List<Double> = listOf(100.0, 900.0, 1800.0, 2700.0, 3200.0)) = SubtitleFingerprint(windows = starts.map { start -> SubtitleActivityWindow(start, .1, (0 until 300).joinToString("") { i -> if (cues.any { start + i * .1 >= it.start && start + i * .1 < it.end }) "1" else "0" }) })
    @Test fun offsetDriftAndConservativeInconclusiveLabels() {
        val truth = dense(); val fingerprint = fingerprint(truth)
        val offset = LocalSubtitleSync.synchronize(truth.map { it.copy(start = it.start + 2.35, end = it.end + 2.35) }, fingerprint, 3600.0)
        assertTrue(offset.verified); assertTrue(abs(offset.offset + 2.35) < .2); assertEquals(1.0, offset.scale, .0001)
        val drift = LocalSubtitleSync.synchronize(truth.map { it.copy(start = it.start / 1.002 - 1, end = it.end / 1.002 - 1) }, fingerprint, 3600.0)
        assertTrue(drift.verified); assertEquals(1.002, drift.scale, .0005)
        assertFalse(LocalSubtitleSync.synchronize(truth, null, 3600.0).verified)
        val sparse = LocalSubtitleSync.synchronize(truth, fingerprint(truth, listOf(100.0, 2700.0)), 3600.0)
        assertFalse(sparse.verified); assertEquals("partial_match", sparse.reason)
        val cut = LocalSubtitleSync.synchronize(truth.map { it.copy(start = it.start + if (it.start > 1700) 20 else 0, end = it.end + if (it.start > 1700) 20 else 0) }, fingerprint, 3600.0)
        assertFalse(cut.verified)
    }
}
