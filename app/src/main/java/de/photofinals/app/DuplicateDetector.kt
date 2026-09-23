package de.photofinals.app

import android.content.Context
import android.net.Uri
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

private data class IndexedFeature(
    val feature: PhotoFeature,
    val originalIndex: Int
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
): DuplicateAnalysis = withContext(Dispatchers.Default) {
    if (uriStrings.isEmpty()) {
        return@withContext DuplicateAnalysis(
            groups = emptyList(),
            singles = emptyList(),
            likelyWhatsAppUris = emptyList(),
            possibleWhatsAppUris = emptyList(),
            lowerQualityCopies = emptyList()
        )
    }

    val features = analyzePhotoFeatures(context, uriStrings)
        .mapIndexed { index, feature -> IndexedFeature(feature, index) }

    if (features.size < 2) {
        val likely = features
            .filter { isStrongWhatsAppName(it.feature.displayName) }
            .map { it.feature.uri }

        return@withContext DuplicateAnalysis(
            groups = emptyList(),
            singles = uriStrings,
            likelyWhatsAppUris = likely,
            possibleWhatsAppUris = emptyList(),
            lowerQualityCopies = emptyList()
        )
    }

    val parent = IntArray(features.size) { it }

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

    for (i in 0 until features.lastIndex) {
        val first = features[i].feature
        val firstAverage = first.averageHash ?: continue
        val firstDifference = first.differenceHash ?: continue

        for (j in i + 1 until features.size) {
            val second = features[j].feature
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

    val groupedIndices = features.indices
        .groupBy { find(it) }
        .values
        .filter { it.size >= 2 }
        .sortedBy { indices -> indices.minOf { features[it].originalIndex } }

    val groups = mutableListOf<List<String>>()
    val lowerQualityCopies = linkedSetOf<String>()
    val possibleWhatsAppUris = linkedSetOf<String>()
    val detailsCache = mutableMapOf<String, PhotoDetails>()

    fun details(uri: String): PhotoDetails =
        detailsCache.getOrPut(uri) { loadPhotoDetailsBlocking(context, uri) }

    for (indices in groupedIndices) {
        val photoDetails = indices.map { index ->
            details(features[index].feature.uri)
        }

        val sorted = photoDetails.sortedWith(photoQualityComparator())
        val uris = sorted.map { it.uri }
        groups += uris

        sorted.drop(1).forEach { lowerQualityCopies += it.uri }
        sorted.filter { it.whatsAppHint == WhatsAppHint.POSSIBLE }
            .forEach { possibleWhatsAppUris += it.uri }
    }

    val groupedUris = groups.flatten().toSet()
    val singles = uriStrings.filterNot { it in groupedUris }

    val likelyWhatsAppUris = linkedSetOf<String>()
    features
        .filter { isStrongWhatsAppName(it.feature.displayName) }
        .forEach { likelyWhatsAppUris += it.feature.uri }

    groupedIndices.flatten().forEach { index ->
        val uri = features[index].feature.uri
        when (details(uri).whatsAppHint) {
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

private fun loadPhotoDetailsBlocking(
    context: Context,
    uriString: String
): PhotoDetails {
    val uri = Uri.parse(uriString)
    val feature = getOrAnalyzePhotoFeatureBlocking(context, uriString)
    val hasCameraMetadata = readHasCameraMetadata(context, uri)
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()

    val whatsAppHint = when {
        isStrongWhatsAppName(feature.displayName) -> WhatsAppHint.LIKELY

        mimeType == "image/jpeg" &&
            !hasCameraMetadata &&
            feature.sizeBytes != null &&
            feature.sizeBytes in 1..2_000_000L &&
            feature.width != null &&
            feature.height != null &&
            max(feature.width, feature.height) in 1..2048 ->
            WhatsAppHint.POSSIBLE

        else -> WhatsAppHint.NONE
    }

    return PhotoDetails(
        uri = uriString,
        displayName = feature.displayName,
        width = feature.width,
        height = feature.height,
        sizeBytes = feature.sizeBytes,
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
