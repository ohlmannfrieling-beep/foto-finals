package de.photofinals.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

enum class WhatsAppHint {
    NONE,
    POSSIBLE,
    LIKELY
}

data class PhotoDetails(
    val uri: String,
    val displayName: String?,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val hasCameraMetadata: Boolean,
    val mimeType: String?,
    val whatsAppHint: WhatsAppHint
) {
    val pixelCount: Long?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            width.toLong() * height.toLong()
        } else {
            null
        }
}

data class DuplicateAnalysis(
    val groups: List<List<String>>,
    val singles: List<String>,
    val likelyWhatsAppUris: List<String>,
    val possibleWhatsAppUris: List<String>,
    val lowerQualityCopies: List<String>
)

private data class OpenableInfo(
    val displayName: String?,
    val sizeBytes: Long?
)

private data class Dimensions(
    val width: Int,
    val height: Int
)

private data class QuickFingerprint(
    val uri: String,
    val originalIndex: Int,
    val displayName: String?,
    val sizeBytes: Long?,
    val averageHash: Long?,
    val differenceHash: Long?,
    val aspectRatio: Float?
)

internal fun isProbableDuplicateFingerprint(
    averageDistance: Int,
    differenceDistance: Int,
    aspectDelta: Float
): Boolean {
    if (aspectDelta > 0.035f) return false

    return (averageDistance <= 3 && differenceDistance <= 7) ||
        (averageDistance <= 5 && differenceDistance <= 4) ||
        (averageDistance <= 2 && differenceDistance <= 10)
}

suspend fun detectDuplicatePhotos(
    context: Context,
    uriStrings: List<String>
): DuplicateAnalysis = withContext(Dispatchers.IO) {
    if (uriStrings.size < 2) {
        val likely = uriStrings.filter { value ->
            val info = queryOpenableInfo(context, Uri.parse(value))
            isStrongWhatsAppName(info.displayName)
        }
        return@withContext DuplicateAnalysis(
            groups = emptyList(),
            singles = uriStrings,
            likelyWhatsAppUris = likely,
            possibleWhatsAppUris = emptyList(),
            lowerQualityCopies = emptyList()
        )
    }

    val fingerprints = uriStrings.mapIndexed { index, value ->
        quickFingerprint(context, value, index)
    }

    val parent = IntArray(fingerprints.size) { it }

    fun find(value: Int): Int {
        var current = value
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    fun union(first: Int, second: Int) {
        val firstRoot = find(first)
        val secondRoot = find(second)
        if (firstRoot != secondRoot) {
            parent[secondRoot] = firstRoot
        }
    }

    for (i in 0 until fingerprints.lastIndex) {
        val first = fingerprints[i]
        val firstAverage = first.averageHash ?: continue
        val firstDifference = first.differenceHash ?: continue

        for (j in i + 1 until fingerprints.size) {
            val second = fingerprints[j]
            val secondAverage = second.averageHash ?: continue
            val secondDifference = second.differenceHash ?: continue

            val firstAspect = first.aspectRatio
            val secondAspect = second.aspectRatio
            val aspectDelta = if (firstAspect != null && secondAspect != null) {
                abs(firstAspect - secondAspect) / max(firstAspect, secondAspect)
            } else {
                0f
            }

            val averageDistance = java.lang.Long.bitCount(firstAverage xor secondAverage)
            val differenceDistance = java.lang.Long.bitCount(firstDifference xor secondDifference)

            if (
                isProbableDuplicateFingerprint(
                    averageDistance = averageDistance,
                    differenceDistance = differenceDistance,
                    aspectDelta = aspectDelta
                )
            ) {
                union(i, j)
            }
        }
    }

    val groupedIndices = fingerprints.indices
        .groupBy { find(it) }
        .values
        .filter { it.size >= 2 }
        .sortedBy { indices -> indices.minOf { fingerprints[it].originalIndex } }

    val groups = mutableListOf<List<String>>()
    val lowerQualityCopies = linkedSetOf<String>()
    val possibleWhatsAppUris = linkedSetOf<String>()

    for (indices in groupedIndices) {
        val details = indices.map { index ->
            loadPhotoDetailsBlocking(context, fingerprints[index].uri)
        }

        val sorted = details.sortedWith(photoQualityComparator())
        val uris = sorted.map { it.uri }
        groups += uris

        sorted.drop(1).forEach { lowerQualityCopies += it.uri }
        sorted.filter { it.whatsAppHint == WhatsAppHint.POSSIBLE }
            .forEach { possibleWhatsAppUris += it.uri }
    }

    val groupedUris = groups.flatten().toSet()
    val singles = uriStrings.filterNot { it in groupedUris }

    val likelyWhatsAppUris = linkedSetOf<String>()
    fingerprints
        .filter { isStrongWhatsAppName(it.displayName) }
        .forEach { likelyWhatsAppUris += it.uri }

    groupedIndices.flatten().forEach { index ->
        val uri = fingerprints[index].uri
        val details = loadPhotoDetailsBlocking(context, uri)
        when (details.whatsAppHint) {
            WhatsAppHint.LIKELY -> likelyWhatsAppUris += uri
            WhatsAppHint.POSSIBLE -> possibleWhatsAppUris += uri
            WhatsAppHint.NONE -> Unit
        }
    }

    DuplicateAnalysis(
        groups = groups,
        singles = singles,
        likelyWhatsAppUris = likelyWhatsAppUris.toList(),
        possibleWhatsAppUris = possibleWhatsAppUris
            .filterNot { it in likelyWhatsAppUris }
            .toList(),
        lowerQualityCopies = lowerQualityCopies.toList()
    )
}

suspend fun loadPhotoDetails(
    context: Context,
    uriString: String
): PhotoDetails = withContext(Dispatchers.IO) {
    loadPhotoDetailsBlocking(context, uriString)
}

private fun quickFingerprint(
    context: Context,
    uriString: String,
    index: Int
): QuickFingerprint {
    val uri = Uri.parse(uriString)
    val openable = queryOpenableInfo(context, uri)
    val providerDimensions = queryProviderDimensions(context, uri)

    val bitmap = loadAnalysisBitmap(context, uri)

    val averageHash = bitmap?.let(::averageHash)
    val differenceHash = bitmap?.let(::differenceHash)

    val ratio = when {
        providerDimensions != null && providerDimensions.height > 0 ->
            providerDimensions.width.toFloat() / providerDimensions.height.toFloat()
        bitmap != null && bitmap.height > 0 ->
            bitmap.width.toFloat() / bitmap.height.toFloat()
        else -> null
    }

    bitmap?.recycle()

    return QuickFingerprint(
        uri = uriString,
        originalIndex = index,
        displayName = openable.displayName,
        sizeBytes = openable.sizeBytes,
        averageHash = averageHash,
        differenceHash = differenceHash,
        aspectRatio = ratio
    )
}

private fun loadPhotoDetailsBlocking(
    context: Context,
    uriString: String
): PhotoDetails {
    val uri = Uri.parse(uriString)
    val openable = queryOpenableInfo(context, uri)
    val dimensions = queryProviderDimensions(context, uri)
        ?: readImageBounds(context, uri)

    val hasCameraMetadata = readHasCameraMetadata(context, uri)
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()

    val whatsAppHint = when {
        isStrongWhatsAppName(openable.displayName) -> WhatsAppHint.LIKELY

        mimeType == "image/jpeg" &&
            !hasCameraMetadata &&
            openable.sizeBytes != null &&
            openable.sizeBytes in 1..2_000_000L &&
            dimensions != null &&
            max(dimensions.width, dimensions.height) in 1..2048 ->
            WhatsAppHint.POSSIBLE

        else -> WhatsAppHint.NONE
    }

    return PhotoDetails(
        uri = uriString,
        displayName = openable.displayName,
        width = dimensions?.width,
        height = dimensions?.height,
        sizeBytes = openable.sizeBytes,
        hasCameraMetadata = hasCameraMetadata,
        mimeType = mimeType,
        whatsAppHint = whatsAppHint
    )
}

private fun photoQualityComparator(): Comparator<PhotoDetails> =
    Comparator { first, second ->
        val firstPixels = first.pixelCount ?: -1L
        val secondPixels = second.pixelCount ?: -1L

        when {
            firstPixels != secondPixels -> secondPixels.compareTo(firstPixels)
            first.hasCameraMetadata != second.hasCameraMetadata ->
                second.hasCameraMetadata.compareTo(first.hasCameraMetadata)
            (first.sizeBytes ?: -1L) != (second.sizeBytes ?: -1L) ->
                (second.sizeBytes ?: -1L).compareTo(first.sizeBytes ?: -1L)
            else -> 0
        }
    }

private fun queryOpenableInfo(context: Context, uri: Uri): OpenableInfo {
    var displayName: String? = null
    var sizeBytes: Long? = null

    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    displayName = cursor.getString(nameColumn)
                }

                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    val value = cursor.getLong(sizeColumn)
                    if (value > 0L) sizeBytes = value
                }
            }
        }
    }

    return OpenableInfo(displayName = displayName, sizeBytes = sizeBytes)
}

private fun queryProviderDimensions(context: Context, uri: Uri): Dimensions? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            val widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            if (
                widthColumn < 0 ||
                heightColumn < 0 ||
                cursor.isNull(widthColumn) ||
                cursor.isNull(heightColumn)
            ) {
                return@use null
            }

            val width = cursor.getInt(widthColumn)
            val height = cursor.getInt(heightColumn)

            if (width > 0 && height > 0) Dimensions(width, height) else null
        }
    }.getOrNull()

private fun readImageBounds(context: Context, uri: Uri): Dimensions? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    return if (options.outWidth > 0 && options.outHeight > 0) {
        Dimensions(options.outWidth, options.outHeight)
    } else {
        null
    }
}

private fun readHasCameraMetadata(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)
            val originalDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)

            !make.isNullOrBlank() || !model.isNullOrBlank() || !originalDate.isNullOrBlank()
        } ?: false
    }.getOrDefault(false)

private fun isStrongWhatsAppName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false

    return Regex("(?i)whatsapp").containsMatchIn(name) ||
        Regex("(?i)(^|[-_ ])WA\\d{3,}").containsMatchIn(name)
}

private fun loadAnalysisBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver

    return runCatching {
        resolver.loadThumbnail(uri, Size(128, 128), null)
    }.getOrNull() ?: decodeSmallBitmap(context, uri)
}

private fun averageHash(bitmap: Bitmap): Long {
    val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)

    return try {
        val luminance = IntArray(64)
        var total = 0L

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val value = luminance(scaled.getPixel(x, y))
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
}

private fun differenceHash(bitmap: Bitmap): Long {
    val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)

    return try {
        var hash = 0L
        var bit = 0

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = luminance(scaled.getPixel(x, y))
                val right = luminance(scaled.getPixel(x + 1, y))
                if (left >= right) {
                    hash = hash or (1L shl bit)
                }
                bit += 1
            }
        }

        hash
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}

private fun luminance(color: Int): Int {
    val red = color shr 16 and 0xFF
    val green = color shr 8 and 0xFF
    val blue = color and 0xFF
    return (red * 30 + green * 59 + blue * 11) / 100
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
    }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > 192 ||
        bounds.outHeight / sampleSize > 192
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
