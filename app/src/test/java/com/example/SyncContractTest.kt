package com.example

import com.example.data.AttendanceEntity
import com.example.data.GoogleSheetsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncContractTest {
    private val record = AttendanceEntity(namaLengkap = "Pegawai", nip = "198001012000011001", jabatan = "Pelaksana",
        jenisAbsensi = "ABSENSI MASUK", timestamp = 1000, dateFormatted = "tanggal", timeFormatted = "07:00:00",
        photoBase64 = "aGVsbG8=", encryptedHash = "abc123")

    @Test fun storageRequiresMatchingPositiveAcknowledgement() {
        assertTrue(GoogleSheetsManager.isAcknowledged(200, """{"success":true,"recordId":"abc123"}""", "abc123"))
        assertFalse(GoogleSheetsManager.isAcknowledged(200, "<html>Login</html>", "abc123"))
        assertFalse(GoogleSheetsManager.isAcknowledged(200, """{"success":false,"recordId":"abc123"}""", "abc123"))
        assertFalse(GoogleSheetsManager.isAcknowledged(500, """{"success":true,"recordId":"abc123"}""", "abc123"))
        assertFalse(GoogleSheetsManager.isAcknowledged(302, "", "abc123"))
        assertFalse(GoogleSheetsManager.isAcknowledged(200, """{"success":true,"recordId":"other"}""", "abc123"))
    }
    @Test fun payloadContainsActualPhotoAndNoFaceVerificationClaim() {
        val payload = GoogleSheetsManager.payload(record)
        assertEquals(record.photoBase64, payload.getString("foto"))
        assertFalse(payload.has("faceVerified"))
    }
    @Test fun fetchAllRecordsRefusesWithoutValidConfig() = runTest {
        val originalUrl = GoogleSheetsManager.webhookUrl
        val originalToken = GoogleSheetsManager.syncToken
        try {
            GoogleSheetsManager.webhookUrl = ""
            GoogleSheetsManager.syncToken = ""
            assertNull(GoogleSheetsManager.fetchAllRecords())
            GoogleSheetsManager.webhookUrl = "https://script.google.com/macros/s/abc/exec"
            GoogleSheetsManager.syncToken = ""
            assertNull(GoogleSheetsManager.fetchAllRecords())
        } finally { GoogleSheetsManager.webhookUrl = originalUrl; GoogleSheetsManager.syncToken = originalToken }
    }
    @Test fun invalidEndpointRemainsUnsynced() = runTest {
        val originalUrl = GoogleSheetsManager.webhookUrl
        try {
            GoogleSheetsManager.webhookUrl = ""
            assertFalse(GoogleSheetsManager.syncAttendanceRecord(record))
        } finally { GoogleSheetsManager.webhookUrl = originalUrl }
        assertFalse(GoogleSheetsManager.isValidWebhook("http://script.google.com/macros/s/abc/exec"))
        assertFalse(GoogleSheetsManager.isValidWebhook("https://evil.example/macros/s/abc/exec"))
        assertTrue(GoogleSheetsManager.isValidWebhook("https://script.google.com/macros/s/abc/exec"))
    }
}
