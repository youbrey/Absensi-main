package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attendance_records")
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val namaLengkap: String,
    val nip: String,
    val jabatan: String,
    val jenisAbsensi: String, // "MASUK" or "PULANG"
    val timestamp: Long,
    val dateFormatted: String,
    val timeFormatted: String,
    val jamMasuk: String = "-",
    val jamPulang: String = "-",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val locationAddress: String = "Lokasi tidak tersedia",
    // Legacy compatibility only; photos are documentation and are never face-verified.
    val faceVerified: Boolean = false,
    val faceConfidence: Float = 0f,
    val photoBase64: String = "",
    val isSyncedToSheets: Boolean = false,
    val encryptedHash: String = ""
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val namaLengkap: String,
    val nip: String,
    val jabatan: String,
    val tipePegawai: String, // "PNS" or "PPPK"
    val role: String, // "USER" or "ADMIN"
    val pinCode: String = "",
    val isActive: Boolean = true,
    val allowTimeOverride: Boolean = false
)

/** Lightweight list/report projection: never load every photo into a Compose state list. */
data class AttendanceSummary(
    val id: Long,
    val namaLengkap: String,
    val nip: String,
    val jabatan: String,
    val jenisAbsensi: String,
    val timestamp: Long,
    val dateFormatted: String,
    val timeFormatted: String,
    val jamMasuk: String,
    val jamPulang: String,
    val locationAddress: String,
    val hasPhoto: Boolean,
    val isSyncedToSheets: Boolean
)
