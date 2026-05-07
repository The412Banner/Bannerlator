package com.winlator.cmod.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import androidx.compose.runtime.withFrameMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class SparkleSpec(
    val angleDeg: Float,
    val distFrac: Float,
    val size: Float,
    val periodMs: Long,
    val offsetMs: Long,
    val driftXFrac: Float,
    val riseYFrac: Float,
)

private val SPARKLE_SPECS: List<SparkleSpec> = run {
    val rng = java.util.Random(42L)
    List(18) {
        SparkleSpec(
            angleDeg   = rng.nextFloat() * 360f,
            distFrac   = 0.42f + rng.nextFloat() * 0.42f,
            size       = 2.5f + rng.nextFloat() * 3.5f,
            periodMs   = 1200L + (rng.nextFloat() * 1000f).toLong(),
            offsetMs   = (rng.nextFloat() * 2500f).toLong(),
            driftXFrac = (rng.nextFloat() - 0.5f) * 0.08f,
            riseYFrac  = 0.03f + rng.nextFloat() * 0.06f,
        )
    }
}

private val STATUS_LABELS = listOf(
    "Installing system files",
    "Extracting Wine prefix",
    "Setting up Proton",
    "Configuring runtime",
    "Finalizing setup",
)

@Composable
fun SplashScreen(
    progress: Int,
    showProceed: Boolean = false,
    onProceed: () -> Unit = {},
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    val displayedProgress by animateIntAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "counter",
    )

    val statusText = if (progress >= 100) "Installation complete"
                     else STATUS_LABELS[minOf((progress / 22), STATUS_LABELS.size - 1)]

    val dotPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 3.99f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots",
    )
    val dots = if (progress < 100) ".".repeat(dotPhase.toInt() + 1) else ""

    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.07f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logoScale",
    )

    val shimmerPos by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue  = 1.3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    var frameTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { withFrameMillis { frameTime = it } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                SparkleCanvas(time = frameTime, modifier = Modifier.fillMaxSize())
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .scale(logoScale),
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Star Bionic",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "v1.2-REVAMPED",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFAAAAAA),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "$statusText$dots",
                fontSize = 13.sp,
                color = Color(0xFF888888),
            )

            Spacer(Modifier.height(32.dp))

            GlowingProgressBar(
                progress    = displayedProgress / 100f,
                shimmerPos  = shimmerPos,
                isComplete  = progress >= 100,
                modifier    = Modifier.fillMaxWidth().height(6.dp),
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "$displayedProgress%",
                fontSize = 13.sp,
                color = Color(0xFFAAAAAA),
            )

            AnimatedVisibility(
                visible = showProceed,
                enter   = fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.92f),
            ) {
                Column {
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = onProceed,
                        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B6BE0)),
                        shape   = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Proceed", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SparkleCanvas(time: Long, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx       = size.width / 2f
        val cy       = size.height / 2f
        val halfSize = min(size.width, size.height) / 2f

        SPARKLE_SPECS.forEach { spec ->
            val phase = ((time + spec.offsetMs) % spec.periodMs).toFloat() / spec.periodMs
            val alpha = when {
                phase < 0.25f -> phase / 0.25f
                phase < 0.75f -> 1f
                else          -> 1f - (phase - 0.75f) / 0.25f
            } * 0.8f

            val angleRad = Math.toRadians(spec.angleDeg.toDouble()).toFloat()
            val dist     = spec.distFrac * halfSize
            val x        = cx + cos(angleRad) * dist + spec.driftXFrac * halfSize * phase
            val y        = cy + sin(angleRad) * dist - spec.riseYFrac * halfSize * phase

            drawFourPointStar(x, y, spec.size, alpha)
        }
    }
}

private fun DrawScope.drawFourPointStar(cx: Float, cy: Float, radius: Float, alpha: Float) {
    val path        = Path()
    val innerRadius = radius * 0.35f
    val count       = 4
    for (i in 0 until count) {
        val outerAngle = (i.toFloat() / count) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val innerAngle = outerAngle + PI.toFloat() / count
        val ox = cx + cos(outerAngle) * radius
        val oy = cy + sin(outerAngle) * radius
        val ix = cx + cos(innerAngle) * innerRadius
        val iy = cy + sin(innerAngle) * innerRadius
        if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
        path.lineTo(ix, iy)
    }
    path.close()
    drawPath(path, Color.White.copy(alpha = alpha))
}

@Composable
private fun GlowingProgressBar(
    progress: Float,
    shimmerPos: Float,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    val glowColor = if (isComplete) Color(0xFFD0BBFF) else Color(0xFF8B6BE0)

    Canvas(modifier = modifier) {
        val barH   = size.height
        val barW   = size.width
        val radius = barH / 2f
        val fillW  = (barW * progress).coerceIn(0f, barW)

        // Track
        drawRoundRect(
            color        = Color(0xFF333333),
            size         = Size(barW, barH),
            cornerRadius = CornerRadius(radius),
        )

        if (fillW > 0f) {
            // Fake glow: three progressively smaller, more opaque layers
            listOf(
                6f to 0.12f,
                4f to 0.18f,
                2f to 0.28f,
            ).forEach { (expand, a) ->
                drawRoundRect(
                    color        = glowColor.copy(alpha = a),
                    topLeft      = Offset(-expand / 2f, -expand / 2f),
                    size         = Size(fillW + expand, barH + expand),
                    cornerRadius = CornerRadius(radius + expand / 2f),
                )
            }

            // Fill gradient
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF6A4FC4), Color(0xFF8B6BE0), Color(0xFFB49BF5)),
                    endX   = fillW,
                ),
                size         = Size(fillW, barH),
                cornerRadius = CornerRadius(radius),
            )

            // Shimmer sweep while installing
            if (!isComplete) {
                val shimX     = shimmerPos * fillW
                val shimHalf  = barH * 4f
                clipRect(right = fillW) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.5f),
                                Color.Transparent,
                            ),
                            startX = shimX - shimHalf,
                            endX   = shimX + shimHalf,
                        ),
                        size         = Size(fillW, barH),
                        cornerRadius = CornerRadius(radius),
                    )
                }
            }
        }
    }
}
