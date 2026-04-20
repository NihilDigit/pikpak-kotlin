package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.formatHttpDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HttpDateTest {

    @Test
    fun `epoch zero renders as Thu 01 Jan 1970`() {
        val formatted = Instant.fromEpochSeconds(0).formatHttpDate()
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", formatted)
    }

    @Test
    fun `rfc1123 example from spec`() {
        // Sun, 06 Nov 1994 08:49:37 GMT == 784111777 seconds since epoch
        val formatted = Instant.fromEpochSeconds(784_111_777).formatHttpDate()
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", formatted)
    }

    @Test
    fun `day of week covers weekend and weekday transitions`() {
        // 2024-03-04 is a Monday
        val mon = Instant.fromEpochSeconds(1_709_510_400).formatHttpDate()
        assertEquals("Mon, 04 Mar 2024 00:00:00 GMT", mon)
        // 2024-03-09 is a Saturday
        val sat = Instant.fromEpochSeconds(1_709_942_400).formatHttpDate()
        assertEquals("Sat, 09 Mar 2024 00:00:00 GMT", sat)
        // 2024-03-10 is a Sunday
        val sun = Instant.fromEpochSeconds(1_710_028_800).formatHttpDate()
        assertEquals("Sun, 10 Mar 2024 00:00:00 GMT", sun)
    }

    @Test
    fun `single digit fields are zero padded`() {
        // 2001-02-03 04:05:06 UTC
        val formatted = Instant.fromEpochSeconds(981_173_106).formatHttpDate()
        assertEquals("Sat, 03 Feb 2001 04:05:06 GMT", formatted)
    }

    @Test
    fun `all twelve months render distinct abbreviations`() {
        val months = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        for (m in 1..12) {
            // Day 15 of month m of 2023, 00:00:00 UTC
            val seconds = monthStart2023(m) + 14L * 86_400
            val formatted = Instant.fromEpochSeconds(seconds).formatHttpDate()
            val expectedMonth = months[m - 1]
            val expectedDomPart = "15 $expectedMonth 2023"
            assertEquals(
                true, expectedDomPart in formatted,
                "month $m formatted as \"$formatted\", expected to contain \"$expectedDomPart\"",
            )
        }
    }

    private fun monthStart2023(month: Int): Long {
        // epoch seconds at 2023-MM-01 00:00:00 UTC
        val firstOfMonth = longArrayOf(
            1_672_531_200, // Jan
            1_675_209_600, // Feb
            1_677_628_800, // Mar
            1_680_307_200, // Apr
            1_682_899_200, // May
            1_685_577_600, // Jun
            1_688_169_600, // Jul
            1_690_848_000, // Aug
            1_693_526_400, // Sep
            1_696_118_400, // Oct
            1_698_796_800, // Nov
            1_701_388_800, // Dec
        )
        return firstOfMonth[month - 1]
    }
}
