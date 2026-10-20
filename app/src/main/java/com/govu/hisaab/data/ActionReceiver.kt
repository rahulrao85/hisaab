package com.govu.hisaab.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.govu.hisaab.core.Verdict

/** "It was me" / "Not me" straight from the notification, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val verdict = runCatching { Verdict.valueOf(intent.getStringExtra("verdict") ?: "") }.getOrNull() ?: return
        if (id < 0) return
        Store.get(ctx).setVerdict(id, verdict)
        NotificationManagerCompat.from(ctx).cancel(id.toInt())
    }
}
