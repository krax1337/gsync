package io.github.krax1337.gsync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material "watch", which is not part of material-icons-core. */
internal val WatchIcon: ImageVector by lazy {
    materialIcon(
        name = "Watch",
        pathData = "M20,12c0,-2.54 -1.19,-4.81 -3.04,-6.27L16,0H8l-0.95,5.73C5.19,7.19 4,9.45 4,12" +
            "s1.19,4.81 3.05,6.27L8,24h8l0.96,-5.73C18.81,16.81 20,14.54 20,12z" +
            "M6,12c0,-3.31 2.69,-6 6,-6s6,2.69 6,6 -2.69,6 -6,6 -6,-2.69 -6,-6z",
    )
}

/** Material "alarm", which is not part of material-icons-core. */
internal val AlarmIcon: ImageVector by lazy {
    materialIcon(
        name = "Alarm",
        pathData = "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72z" +
            "M7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85z" +
            "M12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8z" +
            "M12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z" +
            "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
    )
}

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        // The fill is replaced by the tint of the Icon that draws it.
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
