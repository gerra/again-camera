package sh.gerra.again.domain

/**
 * Where the old photograph sits over the camera: how much it is scaled, how far its centre is moved
 * from the frame's centre and how far it is turned. The offsets are fractions of the frame's width
 * and height rather than pixels, so one transform places the photo the same way in a frame of any
 * size: the viewfinder, the viewfinder turned sideways, and the comparison afterwards.
 *
 * It only ever moves the guide on screen; the camera's photograph is taken as the lens sees it.
 */
data class OverlayTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
) {
    /**
     * This transform after one step of a pinch, drag or twist: [zoom] multiplies the scale, [panX]
     * and [panY] move the centre by those fractions of the frame, [rotation] turns it by degrees.
     */
    fun gestured(panX: Float, panY: Float, zoom: Float, rotation: Float): OverlayTransform = OverlayTransform(
        scale = scale * zoom.finiteOr(1f),
        offsetX = offsetX + panX.finiteOr(0f),
        offsetY = offsetY + panY.finiteOr(0f),
        rotationDegrees = rotationDegrees + rotation.finiteOr(0f),
    ).constrained()

    /**
     * The same transform held within bounds: a scale between [MIN_SCALE] and [MAX_SCALE] and the
     * photo's centre inside the frame, so the guide can never be shrunk to nothing or lost off
     * screen; the angle within -180..180 degrees.
     */
    fun constrained(): OverlayTransform = OverlayTransform(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        offsetX = offsetX.coerceIn(-MAX_OFFSET, MAX_OFFSET),
        offsetY = offsetY.coerceIn(-MAX_OFFSET, MAX_OFFSET),
        rotationDegrees = normalizedDegrees(rotationDegrees),
    )

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 6f
        /** Half the frame: the photo's centre stays on screen. */
        const val MAX_OFFSET = 0.5f

        private fun normalizedDegrees(degrees: Float): Float {
            val turned = degrees % 360f
            return when {
                turned > 180f -> turned - 360f
                turned <= -180f -> turned + 360f
                else -> turned
            }
        }

        private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
    }
}
