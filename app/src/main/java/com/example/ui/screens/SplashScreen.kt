package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay

private val SplashNavy = Color(0xFF0F172A)
private val SplashGold = Color(0xFFF59E0B)

/**
 * Animated splash shown for a fixed duration right after the app icon is tapped, before
 * MainAppStructure (the real bottom-nav UI) appears. Pure Compose, no system SplashScreen
 * API dependency, so it needs no new Gradle dependency and no theme/manifest changes.
 *
 * Sequence: icon springs in with a soft pulsing glow behind it, then the title/subtitle
 * fade+slide up, then a small loading indicator appears -- after [totalDurationMs] total,
 * [onFinished] is invoked once so the caller can swap this out for the real UI.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    totalDurationMs: Long = 2000L
) {
    val iconScale = remember { Animatable(0.5f) }
    val iconAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(16f) }
    val loadingAlpha = remember { Animatable(0f) }

    // Continuous soft "breathing" glow behind the icon -- runs for as long as the splash
    // is on screen, giving it motion beyond the one-shot entrance animation.
    val infiniteTransition = rememberInfiniteTransition(label = "splash_glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    LaunchedEffect(Unit) {
        // Step 1: icon springs in with a slight bounce.
        iconAlpha.animateTo(1f, tween(300))
        iconScale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
        // Step 2: title/subtitle fade and slide up shortly after.
        delay(150)
        textAlpha.animateTo(1f, tween(450))
        textOffset.animateTo(0f, tween(450, easing = LinearOutSlowInEasing))
        // Step 3: loading indicator appears last.
        delay(150)
        loadingAlpha.animateTo(1f, tween(300))
        // Step 4: hold, then hand off to the real UI.
        val elapsedSoFar = 300L + 150L + 450L + 150L + 300L
        val remaining = (totalDurationMs - elapsedSoFar).coerceAtLeast(300L)
        delay(remaining)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashNavy),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Pulsing glow ring behind the icon.
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(glowScale)
                        .alpha(glowAlpha)
                        .clip(CircleShape)
                        .background(SplashGold)
                )
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_round),
                    contentDescription = "Absensi WFH DPRD Bitung",
                    modifier = Modifier
                        .size(112.dp)
                        .scale(iconScale.value)
                        .alpha(iconAlpha.value)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(textAlpha.value)
                    .padding(top = textOffset.value.dp)
            ) {
                Text(
                    text = "ABSENSI WFH",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SEKRETARIAT DPRD KOTA BITUNG",
                    color = SplashGold,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            CircularProgressIndicator(
                modifier = Modifier
                    .size(22.dp)
                    .alpha(loadingAlpha.value),
                color = SplashGold,
                strokeWidth = 2.dp
            )
        }
    }
}
