package com.zhiyu.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.zhiyu.app.MainActivity
import com.zhiyu.app.R
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.ProactiveRules
import java.util.concurrent.atomic.AtomicBoolean

object ProactiveForeground {
    private val visible = AtomicBoolean(false)
    fun setVisible(value: Boolean) { visible.set(value) }
    fun isVisible() = visible.get()
}

object ProactiveNotifications {
    const val CHANNEL = "companion_contact"
    const val CONVERSATION_EXTRA = "contact_conversation_id"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "恋人的主动联系", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "你开启主动联系后，恋人偶尔发来的消息"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun post(context: Context, conversationId: String, message: Message, name: String?) {
        createChannel(context)
        if (!allowed(context) || ProactiveForeground.isVisible()) return
        val intent = Intent(context, MainActivity::class.java).putExtra(CONVERSATION_EXTRA, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val preview = ProactiveRules.notificationPreview(message.content)
        val publicVersion = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("知语").setContentText("你收到一条新消息").build()
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(name?.takeIf(String::isNotBlank)?.take(40) ?: "恋人发来消息")
            .setContentText(preview).setStyle(Notification.BigTextStyle().bigText(preview))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).build()
        context.getSystemService(NotificationManager::class.java).notify(message.id, 1, notification)
    }
}
