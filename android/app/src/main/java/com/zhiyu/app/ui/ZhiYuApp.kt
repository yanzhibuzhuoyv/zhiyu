package com.zhiyu.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zhiyu.app.domain.AppearanceTarget
import com.zhiyu.app.domain.OcMotion
import com.zhiyu.app.notifications.ProactiveScheduler
import com.zhiyu.app.ui.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ZhiYuApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    var menuOpen by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    var revalidate by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var visualOpen by remember { mutableStateOf(false) }
    var careOpen by remember { mutableStateOf(false) }
    var typographyOpen by remember { mutableStateOf(false) }
    var mediaLibraryOpen by remember { mutableStateOf(false) }
    var imageServiceOpen by remember { mutableStateOf(false) }
    var scenePhotosOpen by remember { mutableStateOf(false) }
    var expansionOpen by remember { mutableStateOf(false) }
    var voiceSettingsOpen by remember { mutableStateOf(false) }
    var callOpen by remember { mutableStateOf(false) }
    var planarOpen by rememberSaveable { mutableStateOf(false) }
    val voice = rememberVoiceController()
    val photos = rememberChatPhotoController(state.draftImage, viewModel::draftImage)
    val media = rememberMediaController(state.selectedId, state.connected)
    val ocScope = rememberCoroutineScope()
    val scenePhotos=remember(appContext,ocScope){ScenePhotoController(com.zhiyu.app.data.remotescene.ScenePhotoManager(appContext),ocScope)}
    LaunchedEffect(scenePhotosOpen,state.connected,state.selectedId) {
        scenePhotos.connect(if(scenePhotosOpen&&state.connected)state.selectedId else null)
    }
    DisposableEffect(scenePhotos){onDispose{scenePhotos.dispose()}}
    val expansion=remember(appContext,ocScope){OcExpansionController(com.zhiyu.app.data.ocexpansion.PhoneOcExpansionManager(appContext),ocScope)}
    LaunchedEffect(expansionOpen,state.connected,state.selectedId,media.profile?.referenceId,media.profile?.visualVersion) {
        expansion.connect(null)
        expansion.connect(if(expansionOpen&&state.connected)state.selectedId else null)
    }
    DisposableEffect(expansion){onDispose{expansion.dispose()}}
    val ocCreator = remember(appContext, ocScope, media) {
        OcCreatorController(appContext, ocScope, media::publishCharacter,
            loadImage = { media.image(it.id, it.sha256) },refreshReference=media::currentReference,
            onCommitted = { id, snapshot -> com.zhiyu.app.data.LocalSceneStockScheduler.enqueue(appContext, id,
                snapshot.identity.reference?.visualVersion ?: 0) })
    }
    val planar=remember(appContext,ocScope){PlanarWorkshopController(appContext,ocScope)}
    val currentCallIdentity=ocCreator.callIdentity?.takeIf {it.reference?.id==media.profile?.referenceId&&
        it.reference?.visualVersion==media.profile?.visualVersion}
    val originalPhoto=currentCallIdentity?.photoSource
    val planarBinding=if(state.connected&&state.selectedId!=null&&originalPhoto!=null)"${state.selectedId}:${originalPhoto.sha256}"else null
    val planarRecoveryBinding=planarBinding?.let {"$it:${currentCallIdentity?.reference?.id}:${media.profile?.visualVersion}:${currentCallIdentity?.revision}"}
    LaunchedEffect(planarBinding,planarRecoveryBinding) {planar.connect(planarBinding,planarRecoveryBinding)}
    LaunchedEffect(planarOpen,planar.busy,planarBinding) {
        if(planarOpen&&!planar.busy&&planar.source==null&&planar.error==null)planar.open {currentCallIdentity?.let {ocCreator.source(it)}}
    }
    DisposableEffect(planar){onDispose {planar.dispose()}}
    val planarLifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(planar,planarLifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)planar.checkpoint()}
        planarLifecycle.addObserver(observer)
        onDispose{planarLifecycle.removeObserver(observer)}
    }
    LaunchedEffect(state.selectedId, state.connected, media.profile?.referenceId, media.profile?.visualVersion) {
        val reference = try { if (state.connected) media.currentReference() else null }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            media.reportReferenceFailure()
            null
        }
        ocCreator.connect(if (state.connected) state.selectedId else null, reference,media.profile?.visualVersion ?:0)
    }
    val proactive = rememberProactiveController(state.selectedId, state.connected)
    val care = rememberCareController()
    val typography = rememberChatTypographyController()
    AutoCompanionPermissions(active = state.connected && state.selectedId != null, proactive = proactive, care = care)
    val mediaLibrary = rememberMediaLibraryController()
    var pickerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val appearance = rememberAppearanceController()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pickerTarget?.let(AppearanceTarget::valueOf)
        pickerTarget = null
        if (uri != null && target != null) appearance.choose(target, uri)
    }
    val providerName = state.provider?.name ?: state.binding?.providerId ?: "知语"
    val companionName = state.profile?.name?.takeIf { it.isNotBlank() } ?: providerName
    LaunchedEffect(state.connected, state.restoring) {
        if (!state.connected) { menuOpen = false; libraryOpen = false; revalidate = false; styleOpen = false; appearanceOpen = false; visualOpen = false; careOpen = false; typographyOpen = false; mediaLibraryOpen = false }
        if (!state.connected) { callOpen=false; voiceSettingsOpen=false; imageServiceOpen=false; scenePhotosOpen=false; expansionOpen=false; voice.stop() }
        if (!state.connected&&!state.restoring)planarOpen=false
        if (!state.restoring && !state.connected && !state.canRestoreConnection) ProactiveScheduler.cancel(appContext)
    }
    LaunchedEffect(media.suggestSetup) { if (media.suggestSetup && state.connected) visualOpen = true }
    Scaffold(
        topBar = {
            if (state.connected) CompanionTopBar(
                name = companionName, avatar = appearance.images.companionAvatar,
                actions = {
                    IconButton(onClick = { callOpen=true },enabled=!state.isBusy,modifier=Modifier.testTag("call_menu")) { Icon(Icons.Outlined.VideoCall,"和恋人通话") }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !state.isBusy) { Icon(Icons.Outlined.MoreVert, "更多设置") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("刷新聊天") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = { menuOpen = false; viewModel.refresh() })
                            DropdownMenuItem(text = { Text("资料") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                                onClick = { menuOpen = false; libraryOpen = true })
                            DropdownMenuItem(text = { Text("头像与背景") }, leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                                onClick = { menuOpen = false; appearanceOpen = true }, modifier = Modifier.testTag("appearance_menu"))
                            DropdownMenuItem(text = { Text("恋人外观") }, leadingIcon = { Icon(Icons.Outlined.Face, null) },
                                onClick = { menuOpen = false; visualOpen = true }, modifier = Modifier.testTag("visual_menu"))
                            DropdownMenuItem(text={Text("动态形象")},leadingIcon={Icon(Icons.Outlined.FaceRetouchingNatural,null)},
                                onClick={menuOpen=false;planarOpen=true},
                                modifier=Modifier.testTag("planar_menu"))
                            DropdownMenuItem(text = { Text("照片生成服务") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                onClick = { menuOpen = false; imageServiceOpen = true })
                            DropdownMenuItem(text = { Text("候选照片") }, leadingIcon = { Icon(Icons.Outlined.Collections, null) },
                                onClick = { menuOpen=false; scenePhotosOpen=true },modifier=Modifier.testTag("scene_photos_menu"))
                            DropdownMenuItem(text={Text("OC 取景与补全")},leadingIcon={Icon(Icons.Outlined.AutoAwesome,null)},
                                onClick={menuOpen=false;expansionOpen=true},modifier=Modifier.testTag("oc_expansion_menu"))
                            DropdownMenuItem(text = { Text("恋人的声音") }, leadingIcon = { Icon(Icons.Outlined.RecordVoiceOver,null) },
                                onClick = { menuOpen=false; voiceSettingsOpen=true })
                            DropdownMenuItem(text = { Text("图片与表情包") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                                onClick = { menuOpen = false; mediaLibrary.reload(); mediaLibraryOpen = true })
                            DropdownMenuItem(text = { Text("聊天字号") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) },
                                onClick = { menuOpen = false; typographyOpen = true }, modifier = Modifier.testTag("chat_font_menu"))
                            DropdownMenuItem(text = { Text("日历") }, leadingIcon = { Icon(Icons.Outlined.Event, null) },
                                onClick = { menuOpen = false; careOpen = true }, modifier = Modifier.testTag("care_menu"))
                            DropdownMenuItem(text = { Text("恋人设定") }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) },
                                onClick = { menuOpen = false; styleOpen = true })
                            DropdownMenuItem(text = { Text("重新验证 API Key") }, leadingIcon = { Icon(Icons.Outlined.Key, null) },
                                onClick = { menuOpen = false; revalidate = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            state.error?.let { Feedback(it, true, viewModel::dismissError) }
            state.notice?.let { Feedback(it, false, viewModel::dismissError) }
            appearance.error?.let { Feedback(it, true, appearance::dismissError) }
            media.error?.let { Feedback(it, true, media::dismissError) }
            photos.error?.let { Feedback(it, true, photos::dismissError) }
            proactive.error?.let { Feedback(it, true, proactive::dismissError) }
            care.error?.let { Feedback(it, true, care::dismissFeedback) }
            care.notice?.let { Feedback(it, false, care::dismissFeedback) }
            when {
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在准备你的 AI…") }
                !state.connected -> ConnectionScreen(state, viewModel)
                else -> CompositionLocalProvider(LocalAppearanceImages provides appearance.images,
                    LocalMediaController provides media, LocalChatTypography provides typography.settings) {
                        ChatScreen(state.copy(busy = state.busy ?: if (photos.busy) "准备图片" else null), viewModel,
                            composerActions = { ChatPhotoAction(photos, !state.isBusy) },
                            composerPreview = { ChatPhotoPreview(photos, state.draftImage, !state.isBusy) })
                    }
            }
        }
    }
    if (libraryOpen && state.connected) Dialog(onDismissRequest = { libraryOpen = false }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f).testTag("library_dialog")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("我们的资料", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { libraryOpen = false }) { Icon(Icons.Outlined.Close, "关闭资料") }
                }
                if (state.isBusy) Text("正在整理资料…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                state.error?.let { Feedback(it, true, viewModel::dismissError) }
                state.notice?.let { Feedback(it, false, viewModel::dismissError) }
                Box(Modifier.weight(1f)) { LibraryScreen(state, viewModel) }
            }
        }
    }
    if (revalidate) AlertDialog(
        onDismissRequest = { revalidate = false },
        title = { Text("重新验证 API Key？") },
        text = { Text("将清除本机 Key 并返回验证页。已绑定的 $providerName 和模型 ${state.binding?.model ?: state.model} 仍会保留，不能更换。重新验证后可继续本机这段聊天。") },
        confirmButton = { TextButton(onClick = { revalidate = false; viewModel.disconnect() }, modifier = Modifier.testTag("confirm_revalidate")) { Text("继续验证") } },
        dismissButton = { TextButton(onClick = { revalidate = false }) { Text("取消") } },
    )
    if (styleOpen && state.connected) CompanionProfileDialog(state, viewModel) { styleOpen = false }
    if (appearanceOpen && state.connected) AppearanceDialog(
        selection = appearance.selection, images = appearance.images, busy = appearance.busy || !appearance.ready,
        choose = { target -> pickerTarget = target.name; imagePicker.launch(arrayOf("image/*")) },
        reset = appearance::reset, close = { appearanceOpen = false }, error = appearance.error,
    )
    if (visualOpen && state.connected) CompositionLocalProvider(LocalMediaController provides media) {
        VisualSetupDialog(media, ocCreator) { visualOpen = false; media.acknowledgeSetup() }
    }
    if (typographyOpen && state.connected) ChatDisplaySettingsDialog(
        settings = typography.settings, busy = typography.busy || !typography.ready, error = typography.error,
        onConfirm = { value -> typography.save(value) { typographyOpen = false } }, onDismiss = { typographyOpen = false },
    )
    if (careOpen && state.connected) CareSettingsDialog(care) { careOpen = false }
    if (imageServiceOpen && state.connected) Image2ServiceDialog(onSaved = media::reload, onDismiss = { imageServiceOpen = false })
    if(scenePhotosOpen&&state.connected)ScenePhotoDialog(scenePhotos){scenePhotosOpen=false}
    if(expansionOpen&&state.connected)OcExpansionDialog(expansion){expansionOpen=false}
    if (voiceSettingsOpen && state.connected) VoiceSettingsDialog(voice) { voiceSettingsOpen=false }
    if(planarOpen&&state.connected)PlanarWorkshopDialog(planar,companionName){planarOpen=false}
    if (callOpen && state.connected) {
        TurnCallDialog(voice,companionName,state.messages.lastOrNull { it.role=="assistant" },state.isBusy,state.error,
            viewModel::sendVoiceText,onDismiss={callOpen=false},character={ speaking,mouth ->
                val character = currentCallIdentity
                val animatedCharacter=planar.forBinding(planarBinding)
                if(animatedCharacter!=null)PlanarPreview(animatedCharacter,if(speaking)mouth.coerceIn(0f,1f)else 0f,true,Modifier.fillMaxSize())
                else CharacterPreview(character,OcMotion(),
                    ocCreator::source,modifier=Modifier.fillMaxSize())
            })
    }
    if (mediaLibraryOpen && state.connected) CompositionLocalProvider(LocalMediaController provides media) {
        LocalMediaLibraryDialog(mediaLibrary, media) { mediaLibraryOpen = false }
    }
}

@Composable private fun Feedback(text: String, isError: Boolean, dismiss: () -> Unit) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭提示") }
        }
    }
}
