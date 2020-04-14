package ai.e_motion.camera_android

import android.util.Size
import java.lang.Long

/**
 * Compare two [Size]s based on their areas.
 */
class CompareSizesByArea : Comparator<Size> {

    // We cast here to ensure the multiplications won't overflow
    override fun compare(lhs: Size, rhs: Size) =
        Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
}