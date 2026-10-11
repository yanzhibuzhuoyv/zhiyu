package com.zhiyu.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import kotlin.random.Random

internal val LocalWatercolorPainter = staticCompositionLocalOf<Painter> {
    error("Watercolor artwork must be displayed inside ZhiYuTheme")
}

/** One painter loaded by the theme is shared by backgrounds and every message avatar. */
@Composable fun WatercolorBouquet(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(LocalWatercolorPainter.current, contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit, alpha = alpha)
}

@Composable fun WatercolorPaper(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surface
    val pigment = MaterialTheme.colorScheme.onSurface
    val flecks = remember {
        val random = Random(761)
        List(900) { Offset(random.nextFloat(), random.nextFloat()) }
    }
    Canvas(modifier.testTag("watercolor_paper")) {
        drawRect(background)
        flecks.forEachIndexed { index, point ->
            drawCircle(pigment.copy(alpha = if (index % 3 == 0) 0.026f else 0.015f),
                radius = if (index % 5 == 0) 0.8.dp.toPx() else 0.4.dp.toPx(),
                center = Offset(point.x * size.width, point.y * size.height))
        }
    }
}

@Composable fun WatercolorWelcomeHeader() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        WatercolorBouquet(Modifier.fillMaxWidth().height(155.dp).testTag("welcome_watercolor_art"))
        Spacer(Modifier.height(12.dp))
        Text("知语", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("把日常，轻轻说给你。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun WatercolorChatBackground(modifier: Modifier = Modifier) {
    Box(modifier.testTag("default_watercolor_background")) {
        WatercolorPaper(Modifier.matchParentSize())
        WatercolorBouquet(Modifier.align(Alignment.BottomEnd).size(width = 290.dp, height = 200.dp), alpha = 0.14f)
    }
}
