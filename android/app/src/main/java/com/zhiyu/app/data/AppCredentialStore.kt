package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.CredentialStore

fun appCredentialStore(context: Context): CredentialStore = SecureCredentialStore(context)
