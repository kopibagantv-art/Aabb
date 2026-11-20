package com.example.core.storage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.LocalFileItem
import com.example.core.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.pdfSuperDataStore by preferencesDataStore(name = "pdf_super_prefs")

class LocalFileRepository(private val context: Context) {

    private val aiConsentRememberedKey = booleanPreferencesKey("ai_consent_acknowledged")
    private val pickerNoticeAcknowledgedKey = booleanPreferencesKey("picker_notice_acknowledged")
    private val cameraRequestedOnceKey = booleanPreferencesKey("camera_requested_once")

    val rootDir: File by lazy {
        File(context.filesDir, "pdf_super").apply { mkdirs() }
    }

    val pdfDir: File by lazy {
        File(rootDir, "pdfs").apply { mkdirs() }
    }

    val imageDir: File by lazy {
        File(rootDir, "images").apply { mkdirs() }
    }

    val scanTempDir: File by lazy {
        File(context.cacheDir, "scans").apply { mkdirs() }
    }

    val aiConsentRemembered: Flow<Boolean> =
        context.pdfSuperDataStore.data.map { prefs -> prefs[aiConsentRememberedKey] ?: false }

    val pickerNoticeAcknowledged: Flow<Boolean> =
        context.pdfSuperDataStore.data.map { prefs -> prefs[pickerNoticeAcknowledgedKey] ?: false }

    val cameraRequestedOnce: Flow<Boolean> =
        context.pdfSuperDataStore.data.map { prefs -> prefs[cameraRequestedOnceKey] ?: false }

    suspend fun setAiConsentRemembered(acknowledged: Boolean) {
        context.pdfSuperDataStore.edit { prefs ->
            prefs[aiConsentRememberedKey] = acknowledged
        }
    }

    suspend fun setPickerNoticeAcknowledged(acknowledged: Boolean) {
        context.pdfSuperDataStore.edit { prefs ->
            prefs[pickerNoticeAcknowledgedKey] = acknowledged
        }
    }

    suspend fun markCameraRequestedOnce() {
        context.pdfSuperDataStore.edit { prefs ->
            prefs[cameraRequestedOnceKey] = true
        }
    }

    fun uniquePdfFile(desiredName: String): File {
        val clean = sanitizeFileName(desiredName).ifBlank { "PDF_SUPER_${timestamp()}" }
        val base = clean.removeSuffix(".pdf").removeSuffix(".PDF")
        var candidate = File(pdfDir, "$base.pdf")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(pdfDir, "${base}_$counter.pdf")
            counter++
        }
        return candidate
    }

    fun uniqueImageFile(desiredName: String, extension: String = "jpg"): File {
        val clean = sanitizeFileName(desiredName).ifBlank { "IMG_SUPER_${timestamp()}" }
        val base = clean.substringBeforeLast(".")
        var candidate = File(imageDir, "$base.$extension")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(imageDir, "${base}_$counter.$extension")
            counter++
        }
        return candidate
    }

    suspend fun saveBitmapToImageFile(bitmap: Bitmap, desiredName: String, isPng: Boolean = false): File =
        withContext(Dispatchers.IO) {
            val ext = if (isPng) "png" else "jpg"
            val file = uniqueImageFile(desiredName, ext)
            FileOutputStream(file).use { fos ->
                bitmap.compress(
                    if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    92,
                    fos
                )
            }
            file
        }

    suspend fun listAllFiles(): List<LocalFileItem> = withContext(Dispatchers.IO) {
        val pdfs = pdfDir.listFiles()?.filter { it.isFile && it.extension.equals("pdf", true) }.orEmpty()
        val images = imageDir.listFiles()?.filter {
            it.isFile && it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg", "webp")
        }.orEmpty()

        val items = mutableListOf<LocalFileItem>()
        for (f in pdfs) {
            val info = PdfEngine.inspectPdf(context, f)
            items.add(
                LocalFileItem(
                    id = f.absolutePath,
                    name = f.name,
                    file = f,
                    isPdf = true,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                    pageCount = info.pageCount,
                    isEncrypted = info.isEncrypted
                )
            )
        }
        for (f in images) {
            items.add(
                LocalFileItem(
                    id = f.absolutePath,
                    name = f.name,
                    file = f,
                    isPdf = false,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                    pageCount = 1,
                    isEncrypted = false
                )
            )
        }
        items.sortedByDescending { it.lastModified }
    }

    suspend fun importExternalPdf(uri: Uri): LocalFileItem? = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "Imported_${timestamp()}.pdf"
        val dest = uniquePdfFile(name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext null

        val info = PdfEngine.inspectPdf(context, dest)
        LocalFileItem(
            id = dest.absolutePath,
            name = dest.name,
            file = dest,
            isPdf = true,
            sizeBytes = dest.length(),
            lastModified = dest.lastModified(),
            pageCount = info.pageCount,
            isEncrypted = info.isEncrypted
        )
    }

    suspend fun decodeBitmapFromUri(uri: Uri, maxDim: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    suspend fun exportFileToUri(sourceFile: File, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            true
        }.getOrDefault(false)
    }

    fun renameFile(item: LocalFileItem, newName: String): LocalFileItem? {
        val ext = item.file.extension
        val cleanBase = sanitizeFileName(newName).substringBeforeLast(".")
        if (cleanBase.isBlank()) return null
        val target = File(item.file.parentFile, "$cleanBase.$ext")
        if (target.exists()) return null
        return if (item.file.renameTo(target)) {
            item.copy(id = target.absolutePath, name = target.name, file = target)
        } else null
    }

    fun deleteFile(file: File): Boolean = file.delete()

    fun createShareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = when (file.extension.lowercase(Locale.US)) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${file.name}")
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}
