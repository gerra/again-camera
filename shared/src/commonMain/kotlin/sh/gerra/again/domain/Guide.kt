package sh.gerra.again.domain

/**
 * What is drawn over the camera to line the new photograph up with the old one: the [reference]
 * photo, how see-through it is, and where it has been moved to. Everything the camera screen lets
 * the user change, and all of it kept when they go back to retake.
 */
data class Guide(
    val reference: ReferencePhoto,
    val opacity: Float = DEFAULT_OPACITY,
    val transform: OverlayTransform = OverlayTransform(),
) {
    /** Kept between [MIN_OPACITY] and [MAX_OPACITY], so neither the old photo nor the camera ever disappears. */
    fun withOpacity(value: Float): Guide = copy(opacity = if (value.isNaN()) opacity else value.coerceIn(MIN_OPACITY, MAX_OPACITY))

    /** One step of a gesture on the old photo; see [OverlayTransform.gestured]. */
    fun gestured(panX: Float, panY: Float, zoom: Float, rotation: Float): Guide =
        copy(transform = transform.gestured(panX, panY, zoom, rotation))

    /** Back where it started: filling the frame, centred and level. The opacity stays as chosen. */
    fun resetAlignment(): Guide = copy(transform = OverlayTransform())

    /** True once the photo has been moved, zoomed or turned, so there is something to reset. */
    val isMoved: Boolean get() = transform != OverlayTransform()

    companion object {
        const val MIN_OPACITY = 0.1f
        const val MAX_OPACITY = 0.9f
        const val DEFAULT_OPACITY = 0.5f
    }
}
