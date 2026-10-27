package com.zigorminsk.timemanager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Несколько иконок, которых нет в material-icons-core. */
object AppIcons {

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()

    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28" +
                "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        )
    }

    val Speaker: ImageVector by lazy {
        icon(
            "Speaker",
            "M3,9v6h4l5,5L12,4L7,9L3,9zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    val Archive: ImageVector by lazy {
        icon(
            "Archive",
            "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.16,0.55L3.46,5.23" +
                "C3.17,5.57 3,6.02 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.48 -0.17,-0.93 -0.46,-1.27z" +
                "M12,17.75L6.5,12H10v-2h4v2h3.5L12,17.75zM5.12,5l0.81,-1h12l0.94,1H5.12z",
        )
    }

    val List: ImageVector by lazy {
        icon(
            "List",
            "M3,5h18v2.2H3zM3,10.9h18v2.2H3zM3,16.8h18v2.2H3z",
        )
    }

    val Stop: ImageVector by lazy {
        icon(
            "Stop",
            "M7,7h10v10H7z",
        )
    }

    val Snooze: ImageVector by lazy {
        icon(
            "Snooze",
            "M12,4c-4.42,0 -8,3.58 -8,8s3.58,8 8,8 8,-3.58 8,-8 -3.58,-8 -8,-8zM10,16H7v-1.5l3.3,-4H7V9h5v1.5L8.8,14.5H12V16z" +
                "M17,12.5l-1.4,-1.4 0.4,-0.4H13v-2h5v1.5l-2.2,2.2 0.9,0.9L17,12.5z",
        )
    }
}
