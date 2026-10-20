package com.govu.hisaab.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.govu.hisaab.core.RawSms
import com.govu.hisaab.core.isBusinessSender
import com.govu.hisaab.core.stableSmsId
import com.govu.hisaab.work.Notifier
import com.govu.hisaab.work.Jobs

/** Live path: a bank SMS lands, the rules run at once, and an alert can fire within a second. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        Thread {
            try {
                parts.groupBy { it.originatingAddress }.forEach { (addr, msgs) ->
                    if (!isBusinessSender(addr)) return@forEach
                    val body = msgs.joinToString("") { it.messageBody ?: "" }
                    val ts = msgs.first().timestampMillis
                    val store = Store.real(ctx)
                    val txn = store.ingest(RawSms(stableSmsId(addr!!, body, ts), ts, addr, body)) ?: return@forEach
                    if (txn.alerts.isNotEmpty()) Notifier.alert(ctx, txn)
                }
                Jobs.enrichSoon(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
