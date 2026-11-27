package pos.screens

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val reportZone:ZoneId=ZoneId.of("Africa/Lagos")

/** Date boundaries are inclusive WAT dates, represented as a half-open Instant interval. */
data class ReportDateRange(val from: LocalDate, val through: LocalDate) {
    init { require(!through.isBefore(from)) { "End date must be on or after the start date." } }
    fun contains(iso: String): Boolean {
        val instant = Instant.parse(iso)
        return !instant.isBefore(from.atStartOfDay(reportZone).toInstant()) && instant.isBefore(through.plusDays(1).atStartOfDay(reportZone).toInstant())
    }
}

