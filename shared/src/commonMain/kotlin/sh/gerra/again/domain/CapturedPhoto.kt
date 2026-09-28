package sh.gerra.again.domain

/**
 * The new photograph: the JPEG the camera wrote to [path], exactly as the lens saw it. Nothing is
 * drawn into it — the old photo over the viewfinder is only ever a guide on screen.
 */
data class CapturedPhoto(val path: String)
