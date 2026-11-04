package com.techteam.vpn.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techteam.vpn.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Launch animation: the logo comes out of the dark, a light sweeps across it, the name rises
 * under it, then the whole screen opens up onto the map. Calls [onFinish] when it is gone.
 */
@Composable
fun SplashOverlay(onFinish: () -> Unit) {
    val logo = remember { Animatable(0f) }
    val shine = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 120f)) }
        launch { glow.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
        delay(450)
        launch { shine.animateTo(1f, tween(900, easing = LinearEasing)) }
        delay(150)
        launch { title.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 150f)) }
        delay(1100)
        leave.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        onFinish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1f + 0.25f * leave.value
                scaleY = 1f + 0.25f * leave.value
                alpha = 1f - leave.value
            }
            .background(Color.Black)
            .drawBehind {
                // A soft halo that breathes once behind the logo.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.18f * glow.value), Color.Transparent),
                        center = center, radius = size.minDimension * (0.3f + 0.25f * glow.value),
                    ),
                    radius = size.minDimension * (0.3f + 0.25f * glow.value),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(
                painterResource(R.drawable.logo_mark), null,
                modifier = Modifier
                    .width(280.dp)
                    .scale(0.72f + 0.28f * logo.value)
                    .alpha(logo.value.coerceIn(0f, 1f))
                    .blur((14 * (1 - logo.value.coerceIn(0f, 1f))).dp)
                    // A diagonal band of light that crosses the logo once.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (shine.value in 0.001f..0.999f) {
                            val x = size.width * (-0.5f + 1.7f * shine.value)
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.8f), Color.Transparent),
                                    start = Offset(x - size.width * 0.18f, 0f),
                                    end = Offset(x + size.width * 0.18f, size.height),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                    },
            )
            Text(
                "EthicVPN",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (8 - 6.5f * title.value).sp,
                modifier = Modifier.alpha(title.value.coerceIn(0f, 1f)).offset(y = (14 * (1 - title.value)).dp),
            )
        }
    }
}
