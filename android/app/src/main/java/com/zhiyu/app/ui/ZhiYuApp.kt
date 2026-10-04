package com.zhiyu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiyu.app.domain.Conversation
import com.zhiyu.app.ui.screens.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ZhiYuApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = state.connected,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(24.dp)) {
                    Text("知语", style = MaterialTheme.typography.headlineMedium)
                    Text("每个想法，都有回响", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.createConversation(); scope.launch { drawer.close() } }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("新对话")
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        NavigationDrawerItem(
                            label = { Text(conversation.title, maxLines = 2) },
                            selected = state.selectedId == conversation.id,
                            onClick = { if (!state.isBusy) { viewModel.selectConversation(conversation.id); scope.launch { drawer.close() } } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                            badge = { IconButton(onClick = { deleting = conversation }, enabled = !state.isBusy) { Icon(Icons.Outlined.DeleteOutline, "删除对话 ${conversation.title}") } },
                        )
                    }
                    if (state.conversations.isEmpty()) item { Text("还没有对话", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (state.connected) TopAppBar(
                    title = { Text(if (state.tab == AppTab.CHAT) state.selected?.title ?: "知语" else state.tab.label, maxLines = 1) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "会话列表") } },
                    actions = {
                        IconButton(onClick = viewModel::createConversation, enabled = !state.isBusy) { Icon(Icons.Outlined.Add, "新对话") }
                        IconButton(onClick = viewModel::refresh, enabled = !state.isBusy) { Icon(Icons.Outlined.Refresh, "刷新") }
                    },
                )
            },
            bottomBar = {
                if (state.connected) NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab, onClick = { viewModel.tab(tab) }, label = { Text(tab.label) },
                            icon = { Icon(when (tab) { AppTab.CHAT -> Icons.Outlined.ChatBubbleOutline; AppTab.LIBRARY -> Icons.Outlined.FolderOpen; AppTab.SETTINGS -> Icons.Outlined.Settings }, null) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Feedback(it, true, viewModel::dismissError) }
                state.notice?.let { Feedback(it, false, viewModel::dismissError) }
                if (state.restoring) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("正在恢复本机连接…") }
                } else if (!state.connected) {
                    ConnectionScreen(state, viewModel)
                } else when (state.tab) {
                    AppTab.CHAT -> ChatScreen(state, viewModel)
                    AppTab.LIBRARY -> LibraryScreen(state, viewModel)
                    AppTab.SETTINGS -> SettingsScreen(state, viewModel)
                }
            }
        }
    }
    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除这段对话？") },
            text = { Text("「${conversation.title}」及其所有消息将被永久删除。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteConversation(conversation.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable private fun Feedback(text: String, isError: Boolean, dismiss: () -> Unit) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭提示") }
        }
    }
}
