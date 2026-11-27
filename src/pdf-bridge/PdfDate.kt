// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Sricharan Suresh (github.com/verycareful)

package dev.kern.pdfbridge

import java.time.ZonedDateTime
import java.util.Locale

/** PDF date strings (ISO 32000-1, 7.9.4), written by the app because the engine reads no clock. */
object PdfDate {

    /** `D:YYYYMMDDHHmmSS` and the offset from universal time as `+HH'mm'`, or `-HH'mm'`. */
    fun format(time: ZonedDateTime): String {
        val offset = time.offset.totalSeconds / SECONDS_PER_MINUTE
        val sign = if (offset < 0) '-' else '+'
        val absolute = kotlin.math.abs(offset)
        return String.format(
            Locale.ROOT,
            "D:%04d%02d%02d%02d%02d%02d%c%02d'%02d'",
            time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second,
            sign, absolute / MINUTES_PER_HOUR, absolute % MINUTES_PER_HOUR,
        )
    }

    fun now(): String = format(ZonedDateTime.now())

    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
}
