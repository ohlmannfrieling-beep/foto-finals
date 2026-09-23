package de.photofinals.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class PhotoFeature(
    val uri: String,
    val displayName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val takenAt: Long?,
    val averageHash: Long?,
    val differenceHash: Long?
) {
    val aspectRatio: Float?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            null
        }
}

private data class ProviderInfo(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val takenAt: Long? = null
)

private object PhotoFeatureCache {
    private val features = ConcurrentHashMap<String, PhotoFeature>()

    fun get(uri: String): PhotoFeature? = features[uri]

    fun put(feature: PhotoFeature) {
        features[feature.uri] = feature
    }

    fun clear() = features.clear()
}

internal fun clearPhotoAnalysisCache() {
    PhotoFeatureCache.clear()
}

internal fun getOrAnalyzePhotoFeatureBlocking(
    context: Context,
    uriString: String
): PhotoFeature {
    PhotoFeatureCache.get(uriString)?.let { return it }

    return analyzePhotoFeature(context, uriString).also {
        PhotoFeatureCache.put(it)
    }
}

internal suspend fun analyzePhotoFeatures(
    context: Context,
    uriStrings: List<String>,
    onProgress: (suspend (done: Int, total: Int) -> Unit)? = null
): List<PhotoFeature> = coroutineScope {
    if (uriStrings.isEmpty()) return@coroutineScope emptyList()

    val semaphore = Semaphore(4)
    val completed = AtomicInteger(0)
    val total = uriStrings.size

    uriStrings.map { value ->
        async(Dispatchers.IO) {
            val feature = PhotoFeatureCache.get(value) ?: semaphore.withPermit {
                getOrAnalyzePhotoFeatureBlocking(context, value)
            }

            val done = completed.incrementAndGet()
            if (onProgress != null) {
                withContext(Dispatchers.Main.immediate) {
                    onProgress(done, total)
                }
            }

            feature
        }
    }.awaitAll()
}

private fun analyzePhotoFeature(
    context: Context,
    uriString: String
): PhotoFeature {
    val uri = Uri.parse(uriString)
    val providerInfo = queryProviderInfo(context, uri)

    val bitmap = loadAnalysisBitmap(context, uri)
    val fallbackDimensions = if (
        providerInfo.width == null ||
        providerInfo.height == null ||
        providerInfo.width <= 0 ||
        providerInfo.height <= 0
    ) {
        readImageBounds(context, uri)
    } else {
        null
    }

    val averageHash = bitmap?.let(::averageHash)
    val differenceHash = bitmap?.let(::differenceHash)

    bitmap?.recycle()

    return PhotoFeature(
        uri = uriString,
        displayName = providerInfo.displayName,
        sizeBytes = providerInfo.sizeBytes,
        width = providerInfo.width?.takeIf { it > 0 } ?: fallbackDimensions?.first,
        height = providerInfo.height?.takeIf { it > 0 } ?: fallbackDimensions?.second,
        takenAt = providerInfo.takenAt?.takeIf { it > 0L },
        averageHash = averageHash,
        differenceHash = differenceHash
    )
}

private fun queryProviderInfo(context: Context, uri: Uri): ProviderInfo {
    val resolver = context.contentResolver
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.Images.ImageColumns.DATE_TAKEN
    )

    return runCatching {
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ProviderInfo()

            fun stringValue(name: String): String? {
                val column = cursor.getColumnIndex(name)
                return if (column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
            }

            fun longValue(name: String): Long? {
                val column = cursor.getColumnIndex(name)
                return if (column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
            }

            fun intValue(name: String): Int? {
                val column = cursor.getColumnIndex(name)
                return if (column >= 0 && !cursor.isNull(column)) cursor.getInt(column) else null
            }

            ProviderInfo(
                displayName = stringValue(OpenableColumns.DISPLAY_NAME),
                sizeBytes = longValue(OpenableColumns.SIZE)?.takeIf { it > 0L },
                width = intValue(MediaStore.MediaColumns.WIDTH)?.takeIf { it > 0 },
                height = intValue(MediaStore.MediaColumns.HEIGHT)?.takeIf { it > 0 },
                takenAt = longValue(MediaStore.Images.ImageColumns.DATE_TAKEN)?.takeIf { it > 0L }
            )
        } ?: ProviderInfo()
    }.getOrElse {
        queryProviderInfoFallback(context, uri)
    }
}

private fun queryProviderInfoFallback(context: Context, uri: Uri): ProviderInfo {
    var displayName: String? = null
    var sizeBytes: Long? = null
    var width: Int? = null
    var height: Int? = null
    var takenAt: Long? = null

    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it) }

                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { sizeBytes = cursor.getLong(it).takeIf { value -> value > 0L } }
            }
        }
    }

    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.Images.ImageColumns.DATE_TAKEN
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { width = cursor.getInt(it).takeIf { value -> value > 0 } }

                cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { height = cursor.getInt(it).takeIf { value -> value > 0 } }

                cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { takenAt = cursor.getLong(it).takeIf { value -> value > 0L } }
            }
        }
    }

    return ProviderInfo(
        displayName = displayName,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        takenAt = takenAt
    )
}

private fun loadAnalysisBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver

    return runCatching {
        resolver.loadThumbnail(uri, Size(96, 96), null)
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

private fun readImageBounds(context: Context, uri: Uri): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }

    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        bounds.outWidth to bounds.outHeight
    } else {
        null
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
    }

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
