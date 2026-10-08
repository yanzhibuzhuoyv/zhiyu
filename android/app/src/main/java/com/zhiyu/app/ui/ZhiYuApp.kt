package com.zhiyu.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.domain.AppearanceTarget
import com.zhiyu.app.notifications.ProactiveScheduler
import com.zhiyu.app.ui.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ZhiYuApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    var menuOpen by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    var revalidate by remember { mutableStateOf(false) }
    var developerSettings by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var visualOpen by remember { mutableStateOf(false) }
    var proactiveOpen by remember { mutableStateOf(false) }
    val media = rememberMediaController(state.selectedId, state.connected)
    val proactive = rememberProactiveController(state.selectedId, state.connected)
    val proactiveToggle = rememberProactiveToggle(proactive)
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
        if (!state.connected) { menuOpen = false; libraryOpen = false; revalidate = false; styleOpen = false; appearanceOpen = false; visualOpen = false; proactiveOpen = false }
        if (!state.restoring && !state.connected) ProactiveScheduler.cancel(appContext)
    }
    LaunchedEffect(media.suggestSetup) { if (media.suggestSetup && state.connected) visualOpen = true }
    Scaffold(
        topBar = {
            if (state.connected) TopAppBar(
                title = {
                    Column {
                        Text(companionName, maxLines = 1, style = MaterialTheme.typography.titleLarge)
                        Text("你的 AI 恋人", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
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
                            DropdownMenuItem(text = { Text("主动联系") }, leadingIcon = { Icon(Icons.Outlined.NotificationsActive, null) },
                                onClick = { menuOpen = false; proactiveOpen = true }, modifier = Modifier.testTag("proactive_menu"))
                            DropdownMenuItem(text = { Text("恋人设定") }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) },
                                onClick = { menuOpen = false; styleOpen = true })
                            DropdownMenuItem(text = { Text("重新验证 API Key") }, leadingIcon = { Icon(Icons.Outlined.Key, null) },
                                onClick = { menuOpen = false; revalidate = true })
                            if (BuildConfig.DEBUG) DropdownMenuItem(text = { Text("开发设置") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menuOpen = false; developerSettings = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Feedback(it, true, viewModel::dismissError) }
            state.notice?.let { Feedback(it, false, viewModel::dismissError) }
            appearance.error?.let { Feedback(it, true, appearance::dismissError) }
            media.error?.let { Feedback(it, true, media::dismissError) }
            when {
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在准备你的 AI…") }
                !state.connected -> ConnectionScreen(state, viewModel)
                else -> CompositionLocalProvider(LocalAppearanceImages provides appearance.images, LocalMediaController provides media) { ChatScreen(state, viewModel) }
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
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Feedback(it, true, viewModel::dismissError) }
                state.notice?.let { Feedback(it, false, viewModel::dismissError) }
                Box(Modifier.weight(1f)) { LibraryScreen(state, viewModel) }
            }
        }
    }
    if (revalidate) AlertDialog(
        onDismissRequest = { revalidate = false },
        title = { Text("重新验证 API Key？") },
        text = { Text("将清除本机 Key 并返回验证页。已绑定的 $providerName 和模型 ${state.binding?.model ?: state.model} 仍会保留，不能更换。使用原 Key 重新验证可继续这段聊天。") },
        confirmButton = { TextButton(onClick = { revalidate = false; viewModel.disconnect() }, modifier = Modifier.testTag("confirm_revalidate")) { Text("继续验证") } },
        dismissButton = { TextButton(onClick = { revalidate = false }) { Text("取消") } },
    )
    if (developerSettings && BuildConfig.DEBUG) DevelopmentServerDialog(state, viewModel) { developerSettings = false }
    if (styleOpen && state.connected) CompanionProfileDialog(state, viewModel) { styleOpen = false }
    if (appearanceOpen && state.connected) AppearanceDialog(
        selection = appearance.selection, images = appearance.images, busy = appearance.busy || !appearance.ready,
        choose = { target -> pickerTarget = target.name; imagePicker.launch(arrayOf("image/*")) },
        reset = appearance::reset, close = { appearanceOpen = false }, error = appearance.error,
    )
    if (visualOpen && state.connected) CompositionLocalProvider(LocalMediaController provides media) {
        VisualSetupDialog(media) { visualOpen = false; media.acknowledgeSetup() }
    }
    if (proactiveOpen && state.connected) ProactiveContactDialog(proactive, proactiveToggle) { proactiveOpen = false }
}

@Composable private fun Feedback(text: String, isError: Boolean, dismiss: () -> Unit) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭提示") }
        }
    }
}
