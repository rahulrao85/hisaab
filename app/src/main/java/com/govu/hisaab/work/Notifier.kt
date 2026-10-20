package com.govu.hisaab.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.govu.hisaab.MainActivity
import com.govu.hisaab.R
import com.govu.hisaab.core.Txn
import com.govu.hisaab.core.Verdict
import com.govu.hisaab.core.rupees
import com.govu.hisaab.data.ActionReceiver

object Notifier {
    private const val ALERTS = "alerts"
    private const val SUMMARY = "summary"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Was this you?", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(SUMMARY, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(ctx: Context, t: Txn, v: Verdict) = PendingIntent.getBroadcast(
        ctx, (t.id * 2 + v.ordinal).toInt(),
        Intent(ctx, ActionReceiver::class.java).putExtra("id", t.id).putExtra("verdict", v.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun alert(ctx: Context, t: Txn) {
        if (!allowed(ctx)) return
        val amount = if (t.currency == "INR") rupees(t.amount) else "${t.currency} ${"%.2f".format(t.amount)}"
        val n = NotificationCompat.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$amount · ${t.displayName}: was this you?")
            .setContentText(t.alerts.first().type.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.alerts.joinToString("\n") { "• ${it.reason}" }))
            .setContentIntent(openApp(ctx))
            .addAction(0, "It was me", action(ctx, t, Verdict.MINE))
            .addAction(0, "Not me", action(ctx, t, Verdict.NOT_MINE))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(t.id.toInt(), n)
    }

    fun summary(ctx: Context, title: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, SUMMARY)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(1, n)
    }
}
