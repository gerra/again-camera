package sh.gerra.again.domain

/**
 * The old photograph being recreated: the app's own copy of the one chosen from the gallery, kept
 * at [path] on the device so it outlives the picker's temporary permission to read the original.
 * Each choice gets a new path, so a path always names the same picture.
 */
data class ReferencePhoto(val path: String)
