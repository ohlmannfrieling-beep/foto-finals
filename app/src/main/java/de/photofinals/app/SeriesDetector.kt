package de.photofinals.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

data class SeriesAnalysis(
    val groups: List<List<String>>,
    val singles: List<String>
)

private data class PhotoFingerprint(
    val uri: String,
    val originalIndex: Int,
    val takenAt: Long?,
    val hash: Long?
)

suspend fun detectPhotoSeries(
    context: Context,
    uriStrings: List<String>
): SeriesAnalysis = withContext(Dispatchers.IO) {
    if (uriStrings.size < 2) {
        return@withContext SeriesAnalysis(emptyList(), uriStrings)
    }

    val fingerprints = uriStrings.mapIndexed { index, value ->
        val uri = Uri.parse(value)
        PhotoFingerprint(
            uri = value,
            originalIndex = index,
            takenAt = readTakenAt(context, uri),
            hash = averageHash(context, uri)
        )
    }

    val timedCount = fingerprints.count { it.takenAt != null }
    val ordered = if (timedCount >= fingerprints.size / 2) {
        fingerprints.sortedWith(
            compareBy<PhotoFingerprint> { it.takenAt ?: Long.MAX_VALUE }
                .thenBy { it.originalIndex }
        )
    } else {
        fingerprints
    }

    val rawGroups = mutableListOf<List<PhotoFingerprint>>()
    var current = mutableListOf(ordered.first())

    for (i in 1 until ordered.size) {
        val previous = ordered[i - 1]
        val next = ordered[i]

        if (looksLikeSameSeries(previous, next)) {
            current += next
        } else {
            rawGroups += current.toList()
            current = mutableListOf(next)
        }
    }
    rawGroups += current.toList()

    val groups = rawGroups
        .filter { it.size >= 2 }
        .sortedBy { group -> group.minOf { it.originalIndex } }
        .map { group ->
            group.sortedBy { it.originalIndex }.map { it.uri }
        }

    val groupedUris = groups.flatten().toSet()
    val singles = uriStrings.filterNot { it in groupedUris }

    SeriesAnalysis(groups = groups, singles = singles)
}

private fun looksLikeSameSeries(
    first: PhotoFingerprint,
    second: PhotoFingerprint
): Boolean {
    val firstHash = first.hash ?: return false
    val secondHash = second.hash ?: return false
    val distance = java.lang.Long.bitCount(firstHash xor secondHash)

    val firstTime = first.takenAt
    val secondTime = second.takenAt

    return if (firstTime != null && secondTime != null) {
        val timeGap = abs(secondTime - firstTime)
        timeGap <= 10_000L && distance <= 20
    } else {
        abs(second.originalIndex - first.originalIndex) == 1 && distance <= 12
    }
}

private fun readTakenAt(context: Context, uri: Uri): Long? {
    val resolver = context.contentResolver

    runCatching {
        resolver.query(
            uri,
            arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                if (column >= 0 && !cursor.isNull(column)) {
                    val value = cursor.getLong(column)
                    if (value > 0L) return value
                }
            }
        }
    }

    return runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

            raw?.let {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .parse(it)
                    ?.time
            }
        }
    }.getOrNull()
}

private fun averageHash(context: Context, uri: Uri): Long? {
    val resolver = context.contentResolver

    val bitmap = runCatching {
        resolver.loadThumbnail(uri, Size(96, 96), null)
    }.getOrNull() ?: decodeSmallBitmap(context, uri) ?: return null

    return try {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        try {
            val luminance = IntArray(64)
            var total = 0L

            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val color = scaled.getPixel(x, y)
                    val red = color shr 16 and 0xFF
                    val green = color shr 8 and 0xFF
                    val blue = color and 0xFF
                    val value = (red * 30 + green * 59 + blue * 11) / 100
                    val index = y * 8 + x
                    luminance[index] = value
                    total += value
                }
            }

            val average = total / 64
            var hash = 0L
            luminance.forEachIndexed { index, value ->
                if (value >= average) {
                    hash = hash or (1L shl index)
                }
            }
            hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}

private fun decodeSmallBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    runCatching {
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }.getOrNull()

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > 160 ||
        bounds.outHeight / sampleSize > 160
    ) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return runCatching {
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
}
