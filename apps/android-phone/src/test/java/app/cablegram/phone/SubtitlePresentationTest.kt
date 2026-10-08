package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePresentationTest {
    private fun match(confidence: String, alignment: SubtitleAlignment?) = SubtitleMatch("1", "fa", confidence, alignment)

    @Test fun verifiedOffsetIsDescribedWithoutTechnicalDetail() {
        val h = headline(match("Verified Match", SubtitleAlignment(verified = true, offset = 1.25)))
        assertEquals("Persian", h.language); assertEquals("Local activity match · heuristic", h.trust)
        assertEquals("Automatically synchronized · +1.25 sec", h.timing); assertTrue(h.verified)
    }

    @Test fun neverClaimsVerificationWithoutAVerifiedAlignment() {
        val h = headline(match("Verified Match", SubtitleAlignment(verified = false, reason = "audio_unavailable")))
        assertFalse(h.verified); assertEquals("Not verified", h.trust)
        assertEquals("Timing couldn't be checked against your video", h.timing)
        assertEquals("Likely match · not verified", headline(match("Likely Match", null)).trust)
    }

    @Test fun differentCutIsExplained() {
        assertTrue(headline(match("Poor Match", SubtitleAlignment(reason = "different_cut"))).timing.contains("another cut"))
    }

    @Test fun cuesFollowOffsetAndScale() {
        val cues = listOf(SubtitleCue(10.0, 12.0, "a"), SubtitleCue(100.0, 102.0, "b"))
        assertEquals("a", visibleCue(cues, 12.5, 2.5, 1.0)?.text); assertNull(visibleCue(cues, 11.0, 2.5, 1.0))
        assertEquals("b", visibleCue(cues, 100.0 * 1.01 + 1, 1.0, 1.01)?.text)
    }

    @Test fun twoPointNeedsOrderedPointsAMinuteApart() {
        val a = SubtitleCue(100.0, 102.0, "a") to 102.0; val b = SubtitleCue(1100.0, 1102.0, "b") to 1112.0
        assertTrue(canApplyTwoPoint(a, b)); assertFalse(canApplyTwoPoint(a, null))
        assertFalse(canApplyTwoPoint(a, SubtitleCue(130.0, 131.0, "c") to 140.0)); assertFalse(canApplyTwoPoint(b, a))
    }

    @Test fun rateLimitMessageHasRetryHint() {
        val d = SubtitleDiscovery("i", "s", status = "provider_unavailable", errors = listOf(SubtitleProviderFailure("os", "provider_rate_limited", 300)))
        assertEquals("Subtitle sources are busy. Try again in about 5 min.", discoveryNotice(d))
    }

    @Test fun partlyCheckedMatchIsLabelledHonestlyAndKeepsItsShift() {
        val m = match("Likely Match", SubtitleAlignment(verified = false, offset = -1.5, reason = "partial_match"))
        val h = headline(m)
        assertFalse(h.verified); assertEquals("Likely match · partly checked", h.trust)
        assertEquals("Adjusted from the little dialogue we could hear · -1.50 sec", h.timing)
        assertEquals(-1.5 to 1.0, initialShift(m)); assertEquals(0.0 to 1.0, initialShift(match("Likely Match", null)))
    }
}

class SubtitleUncheckedTest {
    @org.junit.Test fun aCandidateNotCheckedYetSaysSoInsteadOfClaimingAnything() {
        val h = headline(SubtitleMatch("1", "de", "Likely Match", null, cues = null))
        org.junit.Assert.assertFalse(h.verified); org.junit.Assert.assertTrue(h.timing.startsWith("Not checked yet"))
    }
}

class SubtitleSplitTest {
    private fun m(id: String) = SubtitleMatch(id, "fa", "Likely Match")
    @org.junit.Test fun aCheckedOptionIsFeaturedFirst() {
        val (best, rest) = splitOptions(listOf(m("a")), listOf(m("b"), m("c")))
        org.junit.Assert.assertEquals("a", best?.id); org.junit.Assert.assertEquals(listOf("b", "c"), rest.map { it.id })
    }
    @org.junit.Test fun withNothingCheckedTheTopRankedOneIsStillFeatured() {
        val (best, rest) = splitOptions(emptyList(), listOf(m("b"), m("c"), m("d")))
        org.junit.Assert.assertEquals("b", best?.id); org.junit.Assert.assertEquals(listOf("c", "d"), rest.map { it.id })
    }
    @org.junit.Test fun noCandidatesFeaturesNothing() {
        val (best, rest) = splitOptions(emptyList(), emptyList())
        org.junit.Assert.assertNull(best); org.junit.Assert.assertTrue(rest.isEmpty())
    }
}

class SubtitleCannotCheckTest {
    @org.junit.Test fun saysSoWhenTheVideoCannotBeChecked() {
        val m = SubtitleMatch("1", "fa", "Likely Match", null, cues = null)
        org.junit.Assert.assertTrue(headline(m, canCheck = false).timing.startsWith("Can't be checked"))
        org.junit.Assert.assertTrue(headline(m, canCheck = true).timing.startsWith("Not checked yet"))
    }
}
