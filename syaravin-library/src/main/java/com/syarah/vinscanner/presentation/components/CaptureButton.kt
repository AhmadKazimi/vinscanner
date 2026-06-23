package com.syarah.vinscanner.presentation.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun CaptureButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    capturing: Boolean,
    onTap: () -> Unit,
) {
    val outer = 76.dp
    val ring = 3.dp
    val inner = 60.dp

    val alpha by animateFloatAsState(
        targetValue = if (enabled || capturing) 1f else 0.35f,
        label = "alpha",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !capturing) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )

    // Samsung-style capture punch: ring collapses inward then springs back,
    // inner fill dips at the same time. Driven off a counter so repeated
    // captures retrigger the animation.
    var captureImpulse by remember { mutableIntStateOf(0) }
    val ringPunch = remember { Animatable(1f) }
    val innerPunch = remember { Animatable(1f) }

    LaunchedEffect(capturing) {
        if (capturing) captureImpulse++
    }

    LaunchedEffect(captureImpulse) {
        if (captureImpulse == 0) return@LaunchedEffect
        ringPunch.snapTo(0.82f)
        innerPunch.snapTo(0.86f)
        ringPunch.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
    }
    LaunchedEffect(captureImpulse) {
        if (captureImpulse == 0) return@LaunchedEffect
        innerPunch.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        )
    }

    val view = LocalView.current
    val tapEnabled = enabled && !capturing

    Box(
        modifier = modifier
            .size(outer)
            .semantics {
                contentDescription = "vin_scanner_capture_button"
            }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                this.alpha = alpha
            }
            .clickable(
                enabled = tapEnabled,
                interactionSource = interaction,
                indication = null,
                onClick = {
                    if (enabled && !capturing) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onTap()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Outer ring - collapses on capture.
        Box(
            modifier = Modifier
                .size(outer)
                .graphicsLayer {
                    scaleX = ringPunch.value
                    scaleY = ringPunch.value
                }
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(ring, Color.White, CircleShape),
        )

        // Inner fill - dips on capture.
        Box(
            modifier = Modifier
                .size(inner)
                .graphicsLayer {
                    scaleX = innerPunch.value
                    scaleY = innerPunch.value
                }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
