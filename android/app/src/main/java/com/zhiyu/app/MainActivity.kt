package com.zhiyu.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.data.NetworkChatRepository
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.UsbDebugCredentialStore
import com.zhiyu.app.notifications.ProactiveForeground
import com.zhiyu.app.notifications.ProactiveNotifications
import com.zhiyu.app.ui.ChatViewModel
import com.zhiyu.app.ui.ZhiYuApp
import com.zhiyu.app.ui.theme.ZhiYuTheme

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
                NetworkChatRepository(UsbDebugCredentialStore(SecureCredentialStore(applicationContext),
                    BuildConfig.DEBUG && BuildConfig.USE_USB_BACKEND), BuildConfig.DEBUG),
                DocumentReader(applicationContext),
            ) as T
        }
        chatViewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        setContent { ZhiYuTheme { ZhiYuApp(chatViewModel) } }
    }

    override fun onStart() { super.onStart(); ProactiveForeground.setVisible(true) }
    override fun onResume() {
        super.onResume()
        val state = chatViewModel.state.value
        if (state.connected && !state.isBusy && !state.restoring) chatViewModel.refresh()
    }
    override fun onStop() { if (!isChangingConfigurations) ProactiveForeground.setVisible(false); super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(ProactiveNotifications.CONVERSATION_EXTRA) && chatViewModel.state.value.connected) chatViewModel.refresh()
    }
}
