package com.aubynsamuel.clipsync.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons the Material set does not have.
 *
 * The folder is drawn the way Windows 11 draws one — a narrow back plate with the tab, and a
 * wider front plate below it — instead of Material's single flat silhouette. It is two paths
 * with different alpha rather than two colours, because both clients tint it: `Icon` replaces
 * the colour but keeps the alpha of each path, so the layering survives any accent colour.
 */
object CbbIcons {

    val Win11Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "CbbWin11Folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Back plate: the tab sits on the left, the rest is hidden behind the front plate.
            path(fill = SolidColor(Color.Black), fillAlpha = 0.55f) {
                moveTo(3.4f, 5.6f)
                curveTo(3.4f, 4.8f, 4.1f, 4.2f, 4.9f, 4.2f)
                horizontalLineTo(9.4f)
                curveTo(9.9f, 4.2f, 10.4f, 4.4f, 10.8f, 4.8f)
                lineTo(11.9f, 6.0f)
                horizontalLineTo(19.1f)
                curveTo(19.9f, 6.0f, 20.6f, 6.6f, 20.6f, 7.4f)
                verticalLineTo(12.0f)
                horizontalLineTo(3.4f)
                close()
            }

            // Front plate: slightly wider than the back, with generous bottom corners.
            path(fill = SolidColor(Color.Black)) {
                moveTo(2.3f, 10.9f)
                curveTo(2.3f, 10.0f, 3.0f, 9.3f, 3.9f, 9.3f)
                horizontalLineTo(20.1f)
                curveTo(21.0f, 9.3f, 21.7f, 10.0f, 21.7f, 10.9f)
                verticalLineTo(17.1f)
                curveTo(21.7f, 18.5f, 20.6f, 19.7f, 19.2f, 19.7f)
                horizontalLineTo(4.8f)
                curveTo(3.4f, 19.7f, 2.3f, 18.5f, 2.3f, 17.1f)
                close()
            }
        }.build()
    }
}
