package com.zhiyu.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.data.LocalChatRepository
import com.zhiyu.app.data.LocalDailyScheduler
import com.zhiyu.app.data.LocalMemoryRecognitionScheduler
import com.zhiyu.app.data.LocalSemanticScheduler
import com.zhiyu.app.data.NativeCareContext
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.notifications.ProactiveForeground
import com.zhiyu.app.notifications.ProactiveNotifications
import com.zhiyu.app.ui.ChatViewModel
import com.zhiyu.app.ui.ZhiYuApp
import com.zhiyu.app.ui.theme.ZhiYuTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var chatViewModel: ChatViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProactiveForeground.setVisible(true)
        enableEdgeToEdge()
        ProactiveNotifications.createChannel(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                LocalChatRepository(applicationContext, appCredentialStore(applicationContext),
                    careContext = NativeCareContext(applicationContext)::prompt),
                DocumentReader(applicationContext),
            ) as T
        }
        chatViewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalDailyScheduler.monitorForeground(applicationContext)
            }
        }
        setContent { ZhiYuTheme { ZhiYuApp(chatViewModel) } }
    }

    override fun onStart() { super.onStart(); ProactiveForeground.setVisible(true) }
    override fun onResume() {
        super.onResume()
        val state = chatViewModel.state.value
        if (state.connected && !state.isBusy && !state.restoring) chatViewModel.refresh()
    }
    override fun onStop() {
        if (!isChangingConfigurations) {
            ProactiveForeground.setVisible(false)
            LocalSemanticScheduler.enqueue(applicationContext)
            LocalMemoryRecognitionScheduler.schedule(applicationContext)
        }
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(ProactiveNotifications.CONVERSATION_EXTRA) && chatViewModel.state.value.connected) chatViewModel.refresh()
    }
}
