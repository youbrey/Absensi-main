package com.example.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Full-resolution camera capture or gallery attachment, with bounded decoding and EXIF rotation. */
@Composable
fun PhotoAttachment(capturedBitmap: Bitmap?, onCaptured: (Bitmap, String) -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    fun load(uri: Uri, temporaryFile: File? = null) {
        loading = true
        error = null
        scope.launch {
            try {
                val (bitmap, base64) = withContext(Dispatchers.IO) {
                    val bitmap = decodePhoto(context, uri)
                    val bytes = ByteArrayOutputStream().use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out))
                        out.toByteArray().also { require(it.size <= 1_000_000) { "Foto terlalu besar" } }
                    }
                    bitmap to Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                onCaptured(bitmap, base64)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Foto tidak dapat dibuka. Pilih gambar lain atau ambil ulang." }
            finally { loading = false; temporaryFile?.delete() }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { load(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let(::File)
        if (success && file != null) load(Uri.fromFile(file), file) else file?.delete()
        cameraPath = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        capturedBitmap?.let {
            Image(it.asImageBitmap(), "Foto dokumentasi absensi", Modifier.fillMaxWidth().height(220.dp))
        }
        Text("Foto digunakan sebagai dokumentasi absensi.")
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading, onClick = {
                try {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val file = File.createTempFile("absensi_", ".jpg", dir)
                    cameraPath = file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                } catch (_: Exception) {
                    cameraPath?.let { File(it).delete() }; cameraPath = null
                    error = "Aplikasi kamera tidak tersedia. Gunakan unggah foto."
                }
            }) { Text("Ambil selfie") }
            OutlinedButton(enabled = !loading, onClick = {
                try { gallery.launch("image/*") }
                catch (_: Exception) { error = "Pemilih foto tidak tersedia" }
            }) { Text("Unggah foto") }
        }
        if (capturedBitmap != null) TextButton(enabled = !loading, onClick = onReset) { Text("Hapus foto") }
    }
}

internal fun decodePhoto(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Bukan gambar" }
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
    val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Gagal membaca foto")
    val orientation = runCatching { context.contentResolver.openInputStream(uri).use {
        if (it == null) ExifInterface.ORIENTATION_NORMAL else
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}
