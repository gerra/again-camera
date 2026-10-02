package sh.gerra.again.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few camera glyphs the core Material icons lack, drawn from the Material icon paths (Apache
 * 2.0) rather than pulling in the whole extended set for six of them.
 */
internal object AgainIcons {
    val Photo: ImageVector = icon("Photo", "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z")
    val Camera: ImageVector = icon(
        "Camera",
        "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8.2a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0-6.4z",
    )
    val FlashOn: ImageVector = icon("FlashOn", "M7 2v11h3v9l7-12h-4l4-8z")
    val FlashOff: ImageVector = icon("FlashOff", "M3.27 3L2 4.27l5 5V13h3v9l3.58-6.14L17.73 20 19 18.73 3.27 3zM17 10h-4l4-8H7v2.18l8.46 8.46L17 10z")
    /** Material's flip_camera_ios: a camera with two arrows chasing each other round its lens. */
    val SwitchCamera: ImageVector = icon(
        "SwitchCamera",
        "M20 5h-3.17L15 3H9L7.17 5H4C2.9 5 2 5.9 2 7v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zM12 18c-2.76 0-5-2.24-5-5H5l2.5-2.5L10 13H8c0 2.21 1.79 4 4 4 .58 0 1.13-.13 1.62-.35l.74.74c-.71.37-1.5.61-2.36.61zm4.5-2.5L14 13h2c0-2.21-1.79-4-4-4-.58 0-1.13.13-1.62.35L9.64 8.62C10.35 8.24 11.14 8 12 8c2.76 0 5 2.24 5 5h2l-2.5 2.5z",
    )
    val Save: ImageVector = icon("Save", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
