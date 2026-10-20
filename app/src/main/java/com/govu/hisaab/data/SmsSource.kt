package com.govu.hisaab.data

import android.content.Context
import android.provider.Telephony
import com.govu.hisaab.core.RawSms
import com.govu.hisaab.core.isBusinessSender
import com.govu.hisaab.core.stableSmsId

/** Reads bank SMS already in the inbox. Only business senders; personal chats are skipped unread. */
object SmsSource {
    fun inbox(ctx: Context, sinceTs: Long): List<RawSms> {
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        val out = mutableListOf<RawSms>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, cols, "${Telephony.Sms.DATE} >= ?", arrayOf(sinceTs.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0)
                if (!isBusinessSender(addr)) continue
                val body = c.getString(1) ?: continue
                val date = c.getLong(2)
                val sent = c.getLong(3).takeIf { it > 0 } ?: date
                out += RawSms(stableSmsId(addr, body, sent), sent, addr, body)
            }
        }
        return out
    }
}
