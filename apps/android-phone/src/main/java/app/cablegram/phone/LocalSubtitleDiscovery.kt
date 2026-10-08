package app.cablegram.phone

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.*

@Serializable data class SubtitleSourceContext(val sourceId: String, val identity: String, val filename: String, val title: String, val year: Int? = null, val tmdbId: Int? = null, val season: Int? = null, val episode: Int? = null, val duration: Double? = null)
@Serializable data class SelectedSubtitleTrack(val sourceId: String, val identity: String, val language: String, val provider: String, val providerRef: String, val cues: List<SubtitleCue>, val offset: Double, val scale: Double, val forced: Boolean, val confidence: String, val automatic: Boolean = false)
internal data class LocalSubtitleCandidate(val id: String, val provider: String, val ref: String, val download: String, val language: String, val release: String, val file: String, val fps: Double? = null, val hearing: Boolean = false, val forced: Boolean = false, val hash: Boolean = false, val tmdbId: Int? = null, val season: Int? = null, val episode: Int? = null, val pack: Boolean = false, val popularity: Double = 0.0, val rating: Double = 0.0)
private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonObject.num(key: String) = str(key).toDoubleOrNull()
private fun JsonObject.flag(key: String) = str(key) in setOf("true", "1")
private fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.list(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun providerHttpClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

/** No interceptors, disk cache, HTTP logging or persisted search results. Cancellation closes every call. */
class LocalSubtitleDiscovery internal constructor(private var credentials: Map<String, PersonalSubtitleCredentials>, private val onClose: () -> Unit = {}, private val client: OkHttpClient = providerHttpClient()) : AutoCloseable {

    private val job = SupervisorJob()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val candidates = linkedMapOf<String, LocalSubtitleCandidate>()
    private val prepared = linkedMapOf<String, SubtitleMatch>()
    private val cooldown = mutableMapOf<String, Long>()
    private var osToken: String? = null
    private var osHost = "api.opensubtitles.com"
    private var fingerprint: SubtitleFingerprint? = null
    private var technical: SubtitleTechnical? = null
    private var source: SubtitleSourceContext? = null
    private var expires = 0L
    @Volatile private var closed = false
    val configured get() = credentials.isNotEmpty()
    override fun close() { closed = true; job.cancel(); client.dispatcher.cancelAll(); credentials = emptyMap(); osToken = null; fingerprint = null; technical = null; source = null; candidates.clear(); prepared.clear(); onClose() }
    private fun check() { if (closed) throw CancellationException("Subtitle session closed") }
    private suspend fun <T> work(block: suspend () -> T): T = coroutineScope {
        check(); val task = async(Dispatchers.IO) { block() }; val hook = job.invokeOnCompletion { task.cancel() }
        try { task.await() } finally { hook.dispose() }
    }
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun bytes(url: HttpUrl, hosts: Set<String>, provider: String, headers: Map<String, String> = emptyMap(), body: String? = null): ByteArray {
        check(); currentCoroutineContext().ensureActive()
        if (url.scheme != "https" || url.host !in hosts || url.username.isNotEmpty() || url.password.isNotEmpty()) throw LocalSubtitleError("unsafe_provider_url")
        val now = System.currentTimeMillis(); val wait = (cooldown[provider] ?: 0) - now
        if (wait > 0) throw LocalSubtitleError("provider_rate_limited", (wait / 1000 + 1).toInt())
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        val hook = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { call.cancel() }
        try {
            call.execute().use { response ->
                if (response.code in setOf(429, 406)) {
                    val retry = response.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 86400) ?: 60
                    cooldown[provider] = now + retry * 1000L; throw LocalSubtitleError("provider_rate_limited", retry)
                }
                if (response.code in setOf(401, 403)) throw LocalSubtitleError("invalid_credentials")
                if (response.code == 402) throw LocalSubtitleError("quota_exhausted")
                if (!response.isSuccessful) throw LocalSubtitleError("provider_unavailable")
                val b = response.body ?: throw LocalSubtitleError("invalid_subtitle")
                if (b.contentLength() > 2_000_000) throw LocalSubtitleError("provider_response_too_large")
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                b.byteStream().use { stream -> while (true) {
                    check(); currentCoroutineContext().ensureActive(); val n = stream.read(buffer); if (n < 0) break
                    if (out.size() + n > 2_000_000) throw LocalSubtitleError("provider_response_too_large")
                    out.write(buffer, 0, n)
                } }
                return out.toByteArray()
            }
        } catch (e: CancellationException) { throw e } catch (e: LocalSubtitleError) { throw e }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); check(); throw LocalSubtitleError("network_unavailable") }
        finally { hook.dispose() }
    }
    private suspend fun payload(url: HttpUrl, hosts: Set<String>, provider: String, headers: Map<String, String> = emptyMap(), body: String? = null): JsonObject =
        try { json.parseToJsonElement(bytes(url, hosts, provider, headers, body).toString(Charsets.UTF_8)).jsonObject }
        catch (e: CancellationException) { throw e } catch (e: LocalSubtitleError) { throw e } catch (_: Exception) { throw LocalSubtitleError("provider_unavailable") }
    private fun osHeaders() = mapOf("Api-Key" to credentials.getValue("opensubtitles").key, "User-Agent" to "Cablegram v1.0") + (osToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
    private suspend fun login() {
        if (osToken != null) return
        val c = credentials.getValue("opensubtitles")
        val p = payload("https://api.opensubtitles.com/api/v1/login".toHttpUrl(), setOf("api.opensubtitles.com"), "opensubtitles", osHeaders(), buildJsonObject { put("username", c.username); put("password", c.password) }.toString())
        val host = p.str("base_url").removePrefix("https://").trimEnd('/')
        if (host !in setOf("api.opensubtitles.com", "vip-api.opensubtitles.com") || p.str("token").isEmpty()) throw LocalSubtitleError("invalid_credentials")
        osHost = host; osToken = p.str("token")
    }
    private fun language(v: String) = mapOf("persian" to "fa", "farsi" to "fa", "english" to "en", "german" to "de", "french" to "fr", "spanish" to "es")[v.lowercase()] ?: v.lowercase()
    internal fun normalizeSubdl(p: JsonObject): List<LocalSubtitleCandidate> {
        if (!p.flag("status")) { if (Regex("(?i)can.?t find|not found").containsMatchIn(p.str("error"))) return emptyList(); throw LocalSubtitleError("provider_unavailable") }
        return p.list("subtitles").take(100).mapNotNull { a ->
            val url = ("https://dl.subdl.com".toHttpUrl().resolve(a.str("url"))) ?: return@mapNotNull null
            // Ignore every returned query parameter (SubDL echoes credentials), retaining only a numeric stable path.
            if (url.scheme != "https" || url.host != "dl.subdl.com" || url.username.isNotEmpty() || url.password.isNotEmpty()) throw LocalSubtitleError("unsafe_provider_url")
            val m = Regex("^/subtitle/([0-9]+(?:-[0-9]+)?)(?:\\.zip)?$").matchEntire(url.encodedPath) ?: return@mapNotNull null
            val ref = m.groupValues[1]; val lang = language(a.str("language")); if (!Regex("^[a-z]{2}$").matches(lang)) return@mapNotNull null
            LocalSubtitleCandidate("subdl:$ref", "subdl", ref, url.newBuilder().query(null).fragment(null).build().toString(), lang, a.str("release_name").take(300), a.str("name").take(300), a.num("fps"), a.flag("hi"), tmdbId = p.list("results").firstOrNull()?.num("tmdb_id")?.toInt(), season = a.num("season")?.toInt(), episode = if (a.flag("full_season")) null else a.num("episode")?.toInt(), pack = a.flag("full_season"))
        }
    }
    private fun normalizeOs(p: JsonObject): List<LocalSubtitleCandidate> = p.list("data").take(100).flatMap { d ->
        val a = d.obj("attributes"); val f = a.obj("feature_details")
        a.list("files").take(4).mapNotNull { file ->
            val ref = file.num("file_id")?.toLong()?.takeIf { it > 0 }?.toString() ?: return@mapNotNull null
            val lang = language(a.str("language")); if (!Regex("^[a-z]{2}$").matches(lang)) return@mapNotNull null
            LocalSubtitleCandidate("opensubtitles:$ref", "opensubtitles", ref, ref, lang, a.str("release").take(300), file.str("file_name").take(300), a.num("fps"), a.flag("hearing_impaired"), a.flag("foreign_parts_only"), a.flag("moviehash_match"), f.num("tmdb_id")?.toInt(), f.num("season_number")?.toInt(), f.num("episode_number")?.toInt(), popularity = a.num("download_count") ?: 0.0, rating = a.num("ratings") ?: 0.0)
        }
    }
    private suspend fun searchProvider(provider: String, m: SubtitleSourceContext, languages: List<String>, tech: SubtitleTechnical?): List<LocalSubtitleCandidate> {
        val params = sortedMapOf<String, String>()
        if (provider == "subdl") {
            params.putAll(mapOf("api_key" to credentials.getValue(provider).key, "film_name" to m.title, "file_name" to m.filename, "type" to if (m.episode != null) "tv" else "movie", "languages" to languages.joinToString(",") { it.uppercase() }, "subs_per_page" to "30", "releases" to "1"))
            m.tmdbId?.let { params["tmdb_id"] = "$it" }; m.year?.let { params["year"] = "$it" }; m.season?.let { params["season_number"] = "$it" }; m.episode?.let { params["episode_number"] = "$it" }
            val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            return normalizeSubdl(payload(url, setOf("api.subdl.com"), provider)).filter { m.episode != null || !it.pack }
        }
        login()
        params["languages"] = languages.joinToString(","); tech?.hash?.let { params["moviehash"] = it }
        if (m.tmdbId != null) params[if (m.episode != null) "parent_tmdb_id" else "tmdb_id"] = "${m.tmdbId}" else params["query"] = m.title.lowercase()
        m.season?.let { params["season_number"] = "$it" }; m.episode?.let { params["episode_number"] = "$it" }; if (m.episode == null) m.year?.let { params["year"] = "$it" }
        val url = "https://$osHost/api/v1/subtitles".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return normalizeOs(payload(url, setOf(osHost), provider, osHeaders()))
    }
    internal fun rank(c: LocalSubtitleCandidate, m: SubtitleSourceContext, p: SubtitlePreferences, tech: SubtitleTechnical?): Pair<Double, String> {
        fun tokens(v: String) = v.lowercase().replace(Regex("\\.(mkv|mp4|srt|vtt|zip)$"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        val wrong = m.tmdbId != null && c.tmdbId != null && m.tmdbId != c.tmdbId || m.season != null && c.season != null && m.season != c.season || m.episode != null && c.episode != null && m.episode != c.episode
        val a = tokens(m.filename); val b = tokens(c.release.ifEmpty { c.file })
        var score = if (wrong) -200.0 else 0.0
        if (c.hash && tech?.hash != null) score += 100
        if (m.tmdbId != null && m.tmdbId == c.tmdbId) score += 35
        if (m.episode != null && c.episode == m.episode && c.season == m.season) score += 35 else if (m.episode != null && c.pack && c.season == m.season) score += 25
        score += 35 * a.intersect(b).size / maxOf(1.0, a.union(b).size.toDouble()) + 30 - p.languages.indexOf(c.language) * 10
        if (tech?.fps != null && c.fps != null) score += if (abs(tech.fps - c.fps) < .05) 8 else -8
        if (p.sdh != "any") score += if (c.hearing == (p.sdh == "prefer")) 6 else -6
        if (c.forced) score -= 10
        score += min(5.0, log10(1 + max(0.0, c.popularity))) + (c.rating / 2).coerceIn(0.0, 5.0)
        return score to if (wrong) "Poor Match" else if (score >= 55) "Likely Match" else "Unverified"
    }
    suspend fun discover(context: SubtitleSourceContext, p: SubtitlePreferences, tech: SubtitleTechnical?, print: SubtitleFingerprint?): SubtitleDiscovery = work {
        if (source != null && (source!!.sourceId != context.sourceId || source!!.identity != context.identity)) throw LocalSubtitleError("source_identity_changed")
        source = context; technical = tech; fingerprint = print; if (expires == 0L) expires = System.currentTimeMillis() + 3600_000
        val errors = mutableListOf<SubtitleProviderFailure>(); val all = mutableListOf<LocalSubtitleCandidate>()
        for (provider in credentials.keys.toList()) {
            try { all += searchProvider(provider, context, p.languages, tech) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { errors += SubtitleProviderFailure(provider, (e as? LocalSubtitleError)?.code ?: "provider_unavailable", (e as? LocalSubtitleError)?.retryAfter) }
        }
        currentCoroutineContext().ensureActive(); check()
        val perLanguage = mutableMapOf<String, Int>()
        val ranked = all.filter { it.language in p.languages && (p.includeForced || !it.forced) }.map { it to rank(it, context, p, tech) }.sortedByDescending { it.second.first }.filter { (c, _) -> perLanguage[c.language] = (perLanguage[c.language] ?: 0) + 1; perLanguage.getValue(c.language) <= 12 }
        val results = ranked.map { (c, r) -> candidates[c.id] = c; SubtitleMatch(c.id, c.language, r.second, provider = c.provider, releaseName = c.release, forced = c.forced, score = r.first) }.toMutableList()
        for (i in 0 until minOf(5, results.size)) if (results[i].confidence != "Poor Match") results[i] = prepareInternal(results[i])
        results.sortWith(compareByDescending<SubtitleMatch> { it.alignment?.verified == true }.thenByDescending { it.score })
        val best = results.firstOrNull { it.cues != null && it.error == null && it.confidence != "Poor Match" }
        SubtitleDiscovery(UUID.randomUUID().toString(), context.sourceId, results, if (!configured) "providers_not_configured" else if (results.isEmpty()) if (errors.isEmpty()) "no_subtitles" else "provider_unavailable" else if (best?.alignment?.verified == true) "verified" else "no_confident_match", errors, existingLanguages = tech?.embeddedLanguages.orEmpty())
    }
    private suspend fun prepareInternal(match: SubtitleMatch): SubtitleMatch {
        prepared[match.id]?.let { return it }
        if (System.currentTimeMillis() > expires) throw LocalSubtitleError("discovery_expired")
        val c = candidates[match.id] ?: throw LocalSubtitleError("discovery_expired"); val m = source ?: throw LocalSubtitleError("discovery_expired")
        return try {
            val downloaded = if (c.provider == "subdl") bytes(c.download.toHttpUrl(), setOf("dl.subdl.com"), c.provider, mapOf("x-api-key" to credentials.getValue(c.provider).key)) else {
                login(); val p = payload("https://$osHost/api/v1/download".toHttpUrl(), setOf(osHost), c.provider, osHeaders(), buildJsonObject { put("file_id", c.ref.toLong()); put("sub_format", "srt") }.toString())
                val url = p.str("link").toHttpUrl(); bytes(url, setOf("dl.opensubtitles.com", "www.opensubtitles.com", "vip.opensubtitles.com"), c.provider)
            }
            val cues = LocalSubtitleTimeline.parse(LocalSubtitleTimeline.decode(downloaded, c.language, m.season, m.episode))
            val coroutine = currentCoroutineContext()
            if (c.language == "fa" && cues.none { Regex("[\\u0600-\\u06ff]").containsMatchIn(it.text) }) throw LocalSubtitleError("wrong_language_metadata")
            val alignment = LocalSubtitleSync.synchronize(cues, if (c.forced) null else fingerprint, technical?.duration ?: m.duration) { coroutine.ensureActive(); check() }
            val confidence = when { alignment.reason == "different_cut" -> "Poor Match"; alignment.verified -> "Local Activity Match"; alignment.reason == "partial_match" -> "Likely Match"; else -> match.confidence }
            match.copy(cues = cues, alignment = alignment, confidence = confidence).also { check(); if (prepared.size >= 24) prepared.remove(prepared.keys.first()); prepared[c.id] = it }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { match.copy(error = (e as? LocalSubtitleError)?.code ?: "invalid_subtitle") }
    }
    suspend fun prepare(match: SubtitleMatch): SubtitleMatch = work { prepareInternal(match) }
    fun selected(match: SubtitleMatch, automatic: Boolean = false): SelectedSubtitleTrack {
        check(); val c = candidates[match.id] ?: throw LocalSubtitleError("discovery_expired"); val m = source ?: throw LocalSubtitleError("discovery_expired")
        if (match.error != null || match.confidence == "Poor Match" || match.cues.isNullOrEmpty()) throw LocalSubtitleError(match.error ?: "invalid_subtitle")
        val (offset, scale) = initialShift(match)
        return SelectedSubtitleTrack(m.sourceId, m.identity, c.language, c.provider, c.ref, match.cues, offset, scale, c.forced, match.confidence, automatic)
    }
}
