package io.github.nihildigit.pikpak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PikPakExceptionTest {

    @Test
    fun `full message contains code error and description`() {
        val e = PikPakException(errorCode = 42, errorMessage = "boom", errorDescription = "something broke")
        val msg = e.message!!
        assertTrue("error_code=42" in msg)
        assertTrue("\"boom\"" in msg)
        assertTrue("something broke" in msg)
    }

    @Test
    fun `http status renders when present`() {
        val e = PikPakException(errorCode = -1, errorMessage = "net", httpStatus = 503)
        assertTrue("http=503" in e.message!!)
    }

    @Test
    fun `blank description is omitted`() {
        val e = PikPakException(errorCode = 1, errorMessage = "x", errorDescription = "   ")
        assertFalse(":    " in e.message!!)
    }

    @Test
    fun `null description is omitted`() {
        val e = PikPakException(errorCode = 1, errorMessage = "x", errorDescription = null)
        assertFalse(":" in e.message!!.substringAfter("\"x\""))
    }

    @Test
    fun `captcha flag maps to code 9`() {
        assertTrue(PikPakException(ErrorCodes.CAPTCHA_REQUIRED, "c").isCaptchaRequired)
        assertFalse(PikPakException(0, "").isCaptchaRequired)
    }

    @Test
    fun `refresh token flag maps to 4126`() {
        assertTrue(PikPakException(ErrorCodes.REFRESH_TOKEN_INVALID, "x").isRefreshTokenInvalid)
        assertFalse(PikPakException(ErrorCodes.CAPTCHA_REQUIRED, "x").isRefreshTokenInvalid)
    }

    @Test
    fun `fields survive through constructor`() {
        val cause = IllegalStateException("upstream")
        val e = PikPakException(9, "captcha_required", "verify needed", httpStatus = 200, cause = cause)
        assertEquals(9, e.errorCode)
        assertEquals("captcha_required", e.errorMessage)
        assertEquals("verify needed", e.errorDescription)
        assertEquals(200, e.httpStatus)
        assertEquals(cause, e.cause)
    }

    @Test
    fun `default http status is null`() {
        assertNull(PikPakException(0, "").httpStatus)
    }
}
