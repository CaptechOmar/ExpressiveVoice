package com.pranshulgg.recordmaster.ui.components.tiles

import com.pranshulgg.recordmaster.ui.components.SettingsStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TextTile(
    headline: String,
    description: String? = null,
    leading: @Composable (() -> Unit)? = null,
    shapes: RoundedCornerShape
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shapes,
    ) {
        ListItem(

            leadingContent = leading,
            colors = ListItemDefaults.colors(
                containerColor = SettingsStyle.containerColor
            ),
            headlineContent = { Text(headline) },
            supportingContent = {
                if (description != null) {
                    Text(description)
                }
            }
        )
    }
}
