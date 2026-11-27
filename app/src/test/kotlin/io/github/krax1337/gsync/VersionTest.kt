package io.github.krax1337.gsync

import io.github.krax1337.gsync.update.isNewer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun `a later release is newer, whatever its tag looks like`() {
        assertTrue(isNewer("0.2.4", "0.2.3"))
        assertTrue(isNewer("v0.3.0", "0.2.9"))
        assertTrue(isNewer("1.0.0", "0.9.9"))
        // Numbers, not text: 10 comes after 9.
        assertTrue(isNewer("0.10.0", "0.9.0"))
    }

    @Test
    fun `the same or an earlier release is not`() {
        assertFalse(isNewer("0.2.3", "0.2.3"))
        assertFalse(isNewer("v0.2.3", "0.2.3"))
        assertFalse(isNewer("0.2.2", "0.2.3"))
        assertFalse(isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `a version that cannot be read is never an update`() {
        assertFalse(isNewer("latest", "0.2.3"))
        assertFalse(isNewer("1.0", "0.2.3"))
        assertFalse(isNewer("", "0.2.3"))
        assertFalse(isNewer("0.2.4", ""))
    }
}
