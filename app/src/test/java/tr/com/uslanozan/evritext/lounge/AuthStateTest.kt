package tr.com.uslanozan.evritext.lounge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing survives exactly one thing: this file being readable. If it is not, the
 * household has to fetch a 12-digit code off the TV again.
 */
class AuthStateTest {

    @Test
    fun `reads the versioned key names`() {
        val json = """
            {"version": 0, "screenId": "abc", "loungeIdToken": "tok", "refreshToken": null}
        """.trimIndent()

        val auth = AuthState.decode(json)

        assertEquals("abc", auth.screenId)
        assertEquals("tok", auth.loungeIdToken)
        assertTrue(auth.linked)
    }

    @Test
    fun `reads the older key names Phase 0 wrote`() {
        // pyytlounge 3.3.0's own store_auth_state() emits these, and its own loader
        // then cannot read them. The laptop's file has to keep working here.
        val json = """
            {"screenId": "abc", "lounge_id_token": "tok", "refresh_token": null}
        """.trimIndent()

        val auth = AuthState.decode(json)

        assertEquals("abc", auth.screenId)
        assertEquals("tok", auth.loungeIdToken)
        assertTrue(auth.linked)
    }

    @Test
    fun `a screen id without a token is paired but not linked`() {
        val auth = AuthState.decode("""{"screenId": "abc"}""")

        assertTrue(auth.paired)
        assertFalse("must refresh before it can connect", auth.linked)
    }

    @Test
    fun `survives a round trip through its own encoder`() {
        val original = AuthState(screenId = "abc", loungeIdToken = "tok", refreshToken = "ref")
        assertEquals(original, AuthState.decode(original.encode()))
    }

    @Test
    fun `garbage does not throw, it just is not linked`() {
        assertFalse(AuthState.decode("""{"unexpected": true}""").linked)
        assertFalse(AuthState.decode("[]").linked)
    }
}
