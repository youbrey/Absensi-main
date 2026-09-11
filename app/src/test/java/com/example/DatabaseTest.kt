package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AttendanceEntity
import com.example.domain.AttendancePolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private val record = AttendanceEntity(namaLengkap = "Test", nip = "123", jabatan = "Pelaksana",
        jenisAbsensi = AttendancePolicy.MASUK, timestamp = 1000000L, dateFormatted = "Hari", timeFormatted = "07:00:00")
    @After fun close() { db.close() }

    @Test fun pendingQueryDoesNotDependOnUiCollectors() = runTest {
        val dao = db.attendanceDao()
        val id = dao.insertAttendance(record)
        assertEquals(listOf(id), dao.getUnsyncedIds())
        dao.markSynced(id)
        assertTrue(dao.getUnsyncedIds().isEmpty())
    }
    @Test fun dayLookupSeparatesTypeUserAndDay() = runTest {
        val dao = db.attendanceDao()
        dao.insertAttendance(record)
        val (start, end) = AttendancePolicy.dayBounds(record.timestamp)
        assertNotNull(dao.getRecordInDay("123", start, end, AttendancePolicy.MASUK))
        assertNull(dao.getRecordInDay("123", start, end, AttendancePolicy.PULANG))
        assertNull(dao.getRecordInDay("456", start, end, AttendancePolicy.MASUK))
        assertNull(dao.getRecordInDay("123", end, end + 86400000L, AttendancePolicy.MASUK))
    }
    @Test fun migrationKeepsRowsAndRemovesUnprovenFlags() = runTest {
        val dao = db.attendanceDao()
        dao.insertAttendance(record.copy(faceVerified = true, faceConfidence = .985f, isSyncedToSheets = true))
        db.withTransaction { AppDatabase.MIGRATION_2_3.migrate(db.openHelper.writableDatabase) }
        val stored = dao.getAttendanceById(dao.getUnsyncedIds().single())!!
        assertEquals("123", stored.nip)
        assertFalse(stored.faceVerified)
        assertEquals(0f, stored.faceConfidence, 0f)
    }
}
