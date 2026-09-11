package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.data.AttendanceSummary
import com.example.domain.AttendancePolicy
import java.io.File

object ExportUtils {
    private fun target(context: Context, month: String, ext: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(context.filesDir, "reports")
        check(dir.exists() || dir.mkdirs())
        return File(dir, "Rekap_Absensi_${month.replace(Regex("[^\\p{L}0-9_-]"), "_")}_${System.currentTimeMillis()}.$ext")
    }

    fun openOrShareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Laporan absensi", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Simpan / bagikan laporan")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun exportToExcelCsv(context: Context, monthYearLabel: String, list: List<AttendanceSummary>): File {
        val file = target(context, monthYearLabel, "csv")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("\uFEFF")
            out.write(CsvUtils.row(listOf("NO", "NAMA LENGKAP", "NIP", "JABATAN", "JENIS ABSENSI", "JAM MASUK", "JAM PULANG", "TANGGAL", "LOKASI GPS", "FOTO DOKUMENTASI", "STATUS SYNC")))
            list.forEachIndexed { i, item ->
                out.write(CsvUtils.row(listOf((i + 1).toString(), item.namaLengkap, item.nip, item.jabatan,
                    item.jenisAbsensi, item.jamMasuk, item.jamPulang, item.dateFormatted, item.locationAddress,
                    if (item.hasPhoto) "Terlampir" else "Tidak ada", if (item.isSyncedToSheets) "Tersinkron" else "Lokal")))
            }
        }
        return file
    }

    fun exportToPdf(context: Context, monthYearLabel: String, list: List<AttendanceSummary>): File {
        val file = target(context, monthYearLabel, "pdf")
        val doc = PdfDocument()
        try {
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f }
            val bold = Paint(body).apply { isFakeBoldText = true }
            val title = Paint(bold).apply { textSize = 12f; textAlign = Paint.Align.CENTER }
            val columns = listOf(28f, 58f, 153f, 320f, 443f, 628f, 716f)
            val widths = listOf(26f, 91f, 163f, 119f, 181f, 84f, 96f)
            var pageNo = 0
            var y = 0f
            fun startPage(): PdfDocument.Page {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(842, 595, ++pageNo).create())
                val canvas = page.canvas
                canvas.drawText("PEMERINTAH KOTA BITUNG", 421f, 30f, title)
                canvas.drawText("SEKRETARIAT DPRD KOTA BITUNG", 421f, 47f, title)
                canvas.drawText("LAPORAN ABSENSI WFH PNS DAN PPPK • $monthYearLabel", 421f, 65f, title)
                listOf("NO", "TANGGAL", "NAMA LENGKAP", "NIP", "JABATAN", "MASUK", "PULANG").forEachIndexed { i, text ->
                    canvas.drawText(text, columns[i], 93f, bold)
                }
                canvas.drawLine(28f, 100f, 814f, 100f, body)
                canvas.drawText("Halaman $pageNo", 748f, 578f, body)
                y = 115f
                return page
            }
            var page = startPage()
            if (list.isEmpty()) page.canvas.drawText("Tidak ada data pada periode ini.", 28f, y, body)
            list.forEachIndexed { index, record ->
                val cells = listOf((index + 1).toString(), AttendancePolicy.format("dd/MM/yyyy", record.timestamp),
                    record.namaLengkap, record.nip, record.jabatan, record.jamMasuk, record.jamPulang)
                    .mapIndexed { i, value -> wrap(value, body, widths[i]) }
                val lineCount = cells.maxOf { it.size }
                // Split even unusually long legacy rows across pages without dropping text.
                for (line in 0 until lineCount) {
                    if (y > 547f) { doc.finishPage(page); page = startPage() }
                    cells.forEachIndexed { col, lines ->
                        page.canvas.drawText(lines.getOrElse(line) { "" }, columns[col], y, body)
                    }
                    y += 12f
                }
                y += 7f
                page.canvas.drawLine(28f, y - 5f, 814f, y - 5f, body)
            }
            doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        return file
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> = value.split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>()
        var remaining = paragraph
        while (remaining.isNotEmpty()) {
            val fit = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
            lines += remaining.substring(0, fit)
            remaining = remaining.substring(fit)
        }
        lines.ifEmpty { listOf("") }
    }
}
