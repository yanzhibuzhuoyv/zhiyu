package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zhiyu.app.data.planar.*
import com.zhiyu.app.domain.OcIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class CallVisual(val document: PlanarDocument?, val still: Bitmap?)

/** The saved package wins. A reliable photo can show bounded local motion without a generation request. */
@Composable fun CallCharacterPreview(identity: OcIdentity?, published: PlanarDocument?, mouth: Float,
    loadSource: suspend (OcIdentity) -> Bitmap?, onEdit: () -> Unit, modifier: Modifier = Modifier,
    prepare: (suspend (Bitmap) -> PlanarDocument?)? = null, onFrame: (PlanarFrame) -> Unit = {}) {
    val context = LocalContext.current
    val builder = remember(context) {
        AutomaticCallPlanarBuilder(PlanarPackageReader(context.assets.open("planar/contract.json").bufferedReader().use { it.readText() }).contract)
    }
    val currentLoad by rememberUpdatedState(loadSource)
    val currentPrepare by rememberUpdatedState(prepare)
    var visual by remember(identity, published) { mutableStateOf<CallVisual?>(null) }
    var failed by remember(identity, published) { mutableStateOf(false) }
    LaunchedEffect(identity, published) {
        if (published != null || identity == null || !identity.confirmed || identity.route != "photo_subject") return@LaunchedEffect
        try {
            val loaded = prepareVisual(identity, currentLoad, currentPrepare, builder)
            currentCoroutineContext().ensureActive()
            visual = loaded
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
    }
    CharacterContent(identity, published, visual, failed, mouth, onEdit, modifier, onFrame)
}

private suspend fun prepareVisual(identity: OcIdentity, load: suspend (OcIdentity) -> Bitmap?,
    prepare: (suspend (Bitmap) -> PlanarDocument?)?, builder: AutomaticCallPlanarBuilder): CallVisual {
    var original: Bitmap? = null
    var working: Bitmap? = null
    try {
        original = checkNotNull(load(identity))
        return withContext(Dispatchers.Default) {
            val source = checkNotNull(original)
            val scale = minOf(1.0, 768.0 / maxOf(source.width, source.height))
            val bounded = if (scale == 1.0) source else Bitmap.createScaledBitmap(source,
                (source.width * scale).roundToInt().coerceAtLeast(1), (source.height * scale).roundToInt().coerceAtLeast(1), true)
            working = bounded
            currentCoroutineContext().ensureActive()
            val candidate = if (minOf(bounded.width, bounded.height) < 32) null
                else prepare?.invoke(bounded) ?: if (prepare == null) builder.build(bounded) else null
            // Display lists may outlive composition, so their bounded copy remains GC owned.
            val still = if (candidate == null) checkNotNull(bounded.copy(Bitmap.Config.ARGB_8888, false)) else null
            CallVisual(candidate, still)
        }
    } finally {
        if (working !== original) working?.recycle()
        original?.recycle()
    }
}

@Composable private fun CharacterContent(identity: OcIdentity?, published: PlanarDocument?, visual: CallVisual?,
    failed: Boolean, mouth: Float, onEdit: () -> Unit, modifier: Modifier, onFrame: (PlanarFrame) -> Unit) {
    val document = published ?: visual?.document
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                document != null -> PlanarPreview(document, mouth.coerceIn(0f, 1f), true,
                    Modifier.fillMaxSize().testTag("call_character_animated"), onFrame = onFrame)
                visual?.still != null -> Image(checkNotNull(visual?.still).asImageBitmap(), "恋人的原图",
                    Modifier.fillMaxSize().testTag("call_character_still"), contentScale = ContentScale.Fit)
                identity == null -> Text("先设定恋人的形象")
                failed -> Text("形象暂时没能显示，请重新打开", style = MaterialTheme.typography.bodyMedium)
                else -> Text("正在准备人物动作…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (identity != null) TextButton(onClick = onEdit, modifier = Modifier.testTag("call_character_edit")) {
            Text(if (document == null && visual != null) "标注人物，让 TA 动起来" else "调整人物动作")
        }
    }
}
