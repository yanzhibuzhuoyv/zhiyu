package com.zhiyu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.data.NetworkChatRepository
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.ui.ChatViewModel
import com.zhiyu.app.ui.ZhiYuApp
import com.zhiyu.app.ui.theme.ZhiYuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                NetworkChatRepository(SecureCredentialStore(applicationContext), BuildConfig.DEBUG),
                DocumentReader(applicationContext),
            ) as T
        }
        val viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        setContent { ZhiYuTheme { ZhiYuApp(viewModel) } }
    }
}
