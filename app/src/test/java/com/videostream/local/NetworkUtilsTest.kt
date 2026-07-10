package com.videostream.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkUtils] only uses plain `java.net` APIs, so it runs as a normal JVM test with no
 * Android dependency. The actual network interfaces available differ between machines (and
 * CI runners), so these assertions only check the *shape* of the result rather than a
 * specific address — [NetworkUtils.getLocalIpAddress] should never throw, and whatever it
 * returns (if anything) must be a real, non-loopback, non-link-local IPv4 address.
 */
class NetworkUtilsTest {

    private val ipv4Pattern = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    @Test
    fun `getLocalIpAddress does not throw and returns a plausible address or null`() {
        val address = NetworkUtils.getLocalIpAddress()

        if (address == null) return

        assertTrue("'$address' should look like an IPv4 address", ipv4Pattern.matches(address))
        assertFalse("'$address' should not be a loopback address", address.startsWith("127."))
        assertFalse("'$address' should not be a link-local address", address.startsWith("169.254."))
    }
}
