package com.pranshulgg.recordmaster.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pranshulgg.recordmaster.R
import com.pranshulgg.recordmaster.helpers.Haptics

// Same size as the Record pill on the recorder screen: 96dp tall, and 60% of the screen width
// minus that screen's 14dp side padding, so the two buttons match on every phone.
private val ButtonHeight = 96.dp
private val RestingCorner = ButtonHeight / 2 // full pill
private val PressedCorner = 14.dp            // squared-off while held

/**
 * Big red record button. It's a pill at rest and squishes into a rounded rectangle while the
 * finger is held down (with a small springy scale), and gives a click when pressed and another
 * when released, like a physical button.
 */
@Composable
fun RecordButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val buttonWidth = (LocalConfiguration.current.screenWidthDp - 28).dp * 0.6f
    val pressed by interactionSource.collectIsPressedAsState()

    // Haptics fire on touch-down / touch-up, not on "click", so they feel like a real key.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> Haptics.buttonDown(context)
                is PressInteraction.Release, is PressInteraction.Cancel -> Haptics.buttonUp(context)
            }
        }
    }

    val corner: Dp by animateDpAsState(
        targetValue = if (pressed) PressedCorner else RestingCorner,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "recordButtonCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "recordButtonScale"
    )
    val elevation: Dp by animateDpAsState(
        targetValue = if (pressed) 2.dp else 6.dp,
        label = "recordButtonElevation"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(ButtonHeight)
            .width(buttonWidth)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics { contentDescription = "Record" },
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = elevation
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Symbol(
                R.drawable.fiber_manual_record_24px,
                size = 29.4.dp, // 5% bigger than the 28dp icon on the recorder screen
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "Record",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp // 5% bigger than the 20sp label on the recorder screen
            )
        }
    }
}
