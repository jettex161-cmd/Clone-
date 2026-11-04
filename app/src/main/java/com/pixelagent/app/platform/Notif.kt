package com.pixelagent.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notif {
    const val CHANNEL_RUN = "agent_run"
    const val CHANNEL_MSG = "agent_messages"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, "Agent is working", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MSG, "Messages from the agent", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
