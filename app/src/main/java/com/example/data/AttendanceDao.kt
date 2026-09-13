package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    @Query("SELECT id, namaLengkap, nip, jabatan, jenisAbsensi, timestamp, dateFormatted, timeFormatted, jamMasuk, jamPulang, locationAddress, (length(photoBase64) > 0) AS hasPhoto, isSyncedToSheets FROM attendance_records ORDER BY timestamp DESC")
    fun getAllAttendanceFlow(): Flow<List<AttendanceSummary>>

    @Query("SELECT * FROM attendance_records WHERE nip = :nip ORDER BY timestamp DESC")
    fun getAttendanceForUserFlow(nip: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance_records WHERE dateFormatted = :dateFormatted ORDER BY timestamp DESC")
    fun getAttendanceByDateFlow(dateFormatted: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance_records WHERE nip = :nip AND dateFormatted = :dateFormatted AND jenisAbsensi = :jenis LIMIT 1")
    suspend fun getTodayRecord(nip: String, dateFormatted: String, jenis: String): AttendanceEntity?

    @Query("SELECT id FROM attendance_records WHERE isSyncedToSheets = 0 ORDER BY timestamp")
    suspend fun getUnsyncedIds(): List<Long>

    @Query("SELECT * FROM attendance_records WHERE id = :id")
    suspend fun getAttendanceById(id: Long): AttendanceEntity?

    @Query("SELECT * FROM attendance_records WHERE nip = :nip AND timestamp >= :start AND timestamp < :end AND jenisAbsensi = :jenis LIMIT 1")
    suspend fun getRecordInDay(nip: String, start: Long, end: Long, jenis: String): AttendanceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttendance(record: AttendanceEntity): Long

    @Update
    suspend fun updateAttendance(record: AttendanceEntity)

    @Query("UPDATE attendance_records SET isSyncedToSheets = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Delete
    suspend fun deleteAttendance(record: AttendanceEntity)

    @Query("DELETE FROM attendance_records")
    suspend fun clearAll()
}

@Dao
interface UserDao {

    @Query("SELECT * FROM users ORDER BY namaLengkap ASC")
    fun getAllUsersFlow(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE nip = :nip LIMIT 1")
    suspend fun getUserByNip(nip: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserEntity): Long

    @Update
    suspend fun updateUser(user: UserEntity)

    @Delete
    suspend fun deleteUser(user: UserEntity)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int
}
