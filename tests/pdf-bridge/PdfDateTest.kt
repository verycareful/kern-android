package dev.kern.pdfbridge

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** PDF dates as 7.9.4 writes them. */
class PdfDateTest {

    private fun at(zone: ZoneId) = ZonedDateTime.of(2026, 3, 7, 4, 5, 9, 0, zone)

    @Test
    fun a_positive_offset_has_hours_and_minutes() {
        assertEquals("D:20260307040509+05'30'", PdfDate.format(at(ZoneOffset.ofHoursMinutes(5, 30))))
    }

    @Test
    fun a_negative_offset_keeps_its_sign() {
        assertEquals("D:20260307040509-08'00'", PdfDate.format(at(ZoneOffset.ofHours(-8))))
    }

    @Test
    fun a_negative_offset_with_minutes_is_not_mangled_by_the_sign() {
        assertEquals("D:20260307040509-03'30'", PdfDate.format(at(ZoneOffset.ofHoursMinutes(-3, -30))))
    }

    @Test
    fun universal_time_is_a_zero_offset() {
        assertEquals("D:20260307040509+00'00'", PdfDate.format(at(ZoneOffset.UTC)))
    }

    @Test
    fun every_field_is_padded() {
        val time = ZonedDateTime.of(987, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)
        assertEquals("D:09870102030405+00'00'", PdfDate.format(time))
    }
}
