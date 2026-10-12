package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCredentialCheckTest {
    @Test fun subdlKeyIsAcceptedWhenItSearchesOrFindsNothing() {
        assertEquals(CredentialCheck.Accepted, classifyCredentialResponse("subdl", 200, """{"status":true,"results":[],"subtitles":[]}"""))
        assertEquals(CredentialCheck.Accepted, classifyCredentialResponse("subdl", 200, """{"status":false,"error":"can't find movie or tv"}"""))
    }

    @Test fun subdlRefusesAWrongKey() {
        // Recorded from api.subdl.com with a made-up key.
        assertTrue(classifyCredentialResponse("subdl", 403, """{"status":false,"statusCode":403,"error":"not_authorized","message":"Not Authorized"}""") is CredentialCheck.Refused)
        assertTrue(classifyCredentialResponse("subdl", 200, """{"status":false,"error":"invalid api key"}""") is CredentialCheck.Refused)
    }

    @Test fun openSubtitlesSeparatesKeyFromLogin() {
        assertEquals(CredentialCheck.Accepted, classifyCredentialResponse("opensubtitles", 200, """{"token":"abc","base_url":"api.opensubtitles.com"}"""))
        // Recorded from api.opensubtitles.com with a made-up key.
        val badKey = classifyCredentialResponse("opensubtitles", 403, """{"message":"You cannot consume this service"}""")
        assertTrue(badKey is CredentialCheck.Refused && "API key" in badKey.message)
        val badLogin = classifyCredentialResponse("opensubtitles", 401, """{"message":"Invalid username/password"}""")
        assertTrue(badLogin is CredentialCheck.Refused && "username or password" in badLogin.message)
    }

    @Test fun busyOrUnknownAnswersSaveUnchecked() {
        assertTrue(classifyCredentialResponse("subdl", 429, "") is CredentialCheck.Unchecked)
        assertTrue(classifyCredentialResponse("subdl", 502, "<html>") is CredentialCheck.Unchecked)
        assertTrue(classifyCredentialResponse("opensubtitles", 503, "") is CredentialCheck.Unchecked)
    }
}
