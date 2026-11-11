package com.pranshulgg.recordmaster.ui.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.pranshulgg.recordmaster.helpers.Haptics

/**
 * Gives a button the same physical-key feel as the record button: a firm click when the finger
 * touches it and a lighter click when it lifts. Pass the same [interactionSource] to the button.
 */
@Composable
fun HapticOnPress(interactionSource: InteractionSource) {
    val context = LocalContext.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> Haptics.buttonDown(context)
                is PressInteraction.Release, is PressInteraction.Cancel -> Haptics.buttonUp(context)
            }
        }
    }
}
