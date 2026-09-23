package de.photofinals.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
): SeriesAnalysis = withContext(Dispatchers.Default) {
    if (uriStrings.size < 2) {
        return@withContext SeriesAnalysis(emptyList(), uriStrings)
    }

    val fingerprints = analyzePhotoFeatures(context, uriStrings)
        .mapIndexed { index, feature ->
            PhotoFingerprint(
                uri = feature.uri,
                originalIndex = index,
                takenAt = feature.takenAt,
                hash = feature.averageHash
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
