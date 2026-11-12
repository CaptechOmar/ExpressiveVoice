package com.pranshulgg.recordmaster.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The look of the settings containers (modelled on the Wiki Reader app): lighter tonal blocks,
 * big rounded corners on the outside of a group and small ones between neighbouring tiles.
 * Change these in one place to restyle every settings tile.
 */
object SettingsStyle {
    val OuterCorner = 28.dp
    val InnerCorner = 4.dp
    val ItemGap = 2.dp
    val SideMargin = 16.dp

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
}
