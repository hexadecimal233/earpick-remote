package moe.hexzii.earpick.capture

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves JPEG snapshots through MediaStore; no storage permission is required on API 29+. */
class SnapshotStore(private val contentResolver: ContentResolver) {
    suspend fun save(jpeg: ByteArray): Uri = withContext(Dispatchers.IO) {
        val timestamp = FILE_NAME_FORMAT.format(Instant.now())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "earpick_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Earpick",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore rejected the snapshot")

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jpeg)
            } ?: throw IOException("No output stream for $uri")

            val publish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, publish, null, null)
            uri
        } catch (error: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw error
        }
    }

    private companion object {
        val FILE_NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
                .withZone(ZoneId.systemDefault())
    }
}
