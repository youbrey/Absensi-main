package com.example

import com.example.domain.AttendancePolicy
import com.example.util.CsvUtils
import com.example.util.PasswordHasher
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class AttendancePolicyTest {
    private fun time(value: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
        timeZone = AttendancePolicy.zone
    }.parse(value)!!.time

    @Test fun timeWindowsUseWitaAndRespectBoundaries() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val cases = mapOf("06:59:59" to "CLOSED", "07:00:00" to "MASUK", "09:00:00" to "MASUK",
                "09:00:01" to "CLOSED", "11:59:59" to "CLOSED", "12:00:00" to "PULANG",
                "14:00:00" to "PULANG", "14:00:01" to "CLOSED")
            cases.forEach { (clock, expected) -> assertEquals(clock, expected, AttendancePolicy.window(time("2026-09-11 $clock"))) }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun dayAndMonthBoundariesAreStable() {
        val value = time("2026-10-01 00:00:00")
        val (start, end) = AttendancePolicy.dayBounds(value)
        assertEquals(value, start)
        assertEquals(86400000L, end - start)
        assertEquals("Oktober 2026", AttendancePolicy.monthLabel(value))
        assertEquals("September 2026", AttendancePolicy.monthLabel(value - 1))
    }

    @Test fun passwordHashesHaveUniqueSaltAndRejectWrongCredentials() {
        val a = PasswordHasher.hash("a-secure-password")
        val b = PasswordHasher.hash("a-secure-password")
        assertNotEquals(a, b)
        assertTrue(PasswordHasher.verify("a-secure-password", a))
        assertFalse(PasswordHasher.verify("incorrect", a))
        assertFalse(PasswordHasher.verify("123456", "123456"))
        assertFalse(PasswordHasher.verify("admin123", "admin123"))
        assertFalse(PasswordHasher.verify("abc", "pbkdf2:invalid:data"))
    }

    @Test(expected = IllegalArgumentException::class) fun weakNewPasswordIsRejected() { PasswordHasher.hash("123456") }

    @Test fun csvEscapesQuotesNewlinesFormulaAndLongNip() {
        assertEquals("\"A, \"\"B\"\"\nC\"", CsvUtils.cell("A, \"B\"\nC"))
        assertEquals("\"'=1+1\"", CsvUtils.cell("=1+1"))
        assertEquals("\"'198001012000011001\"", CsvUtils.cell("198001012000011001"))
        assertTrue(CsvUtils.row(listOf("normal", "value")).endsWith("\r\n"))
    }
}
