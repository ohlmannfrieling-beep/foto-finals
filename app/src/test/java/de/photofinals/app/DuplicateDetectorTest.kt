package de.photofinals.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {

    @Test
    fun acceptsSmallHashDifferencesForSameFraming() {
        assertTrue(
            isProbableDuplicateFingerprint(
                averageDistance = 2,
                differenceDistance = 5,
                aspectDelta = 0.01f
            )
        )
    }

    @Test
    fun rejectsVisuallyDifferentFrames() {
        assertFalse(
            isProbableDuplicateFingerprint(
                averageDistance = 8,
                differenceDistance = 11,
                aspectDelta = 0.01f
            )
        )
    }

    @Test
    fun rejectsDifferentAspectRatios() {
        assertFalse(
            isProbableDuplicateFingerprint(
                averageDistance = 1,
                differenceDistance = 2,
                aspectDelta = 0.08f
            )
        )
    }
}
