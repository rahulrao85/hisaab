package com.govu.hisaab.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class RawSms(val id: Long, val ts: Long, val sender: String, val body: String)

/** What happens to the store when one SMS arrives. Pure, so the tests run on a laptop. */
data class IngestResult(val txn: Txn, val paidBill: Txn?)

object Ingest {
    /** Alerts on SMS older than this during a first inbox scan are assumed fine, not raised. */
    const val BACKFILL_QUIET_MS = 7L * 24 * 3600 * 1000

    fun ingest(
        sms: RawSms,
        existing: List<Txn>,
        trusted: Set<String>,
        now: Long = System.currentTimeMillis(),
        backfill: Boolean = false,
        cfg: Rules.Config = Rules.Config(),
    ): IngestResult? {
        val t = SmsParser.parse(sms.id, sms.ts, sms.sender, sms.body) ?: return null
        if (existing.any { it.smsId == t.smsId }) return null
        // Banks sometimes send the same debit twice; same ref + amount is the same money.
        if (t.ref != null && existing.any { it.ref == t.ref && it.kind == t.kind && it.amount == t.amount }) return null

        val alerts = Rules.evaluate(t, existing, trusted, cfg)
        val quiet = backfill && now - t.ts > BACKFILL_QUIET_MS && alerts.isNotEmpty()
        val txn = t.copy(alerts = alerts, verdict = if (quiet) Verdict.MINE else Verdict.NONE)

        val paid = if (t.kind == Kind.DEBIT) Rules.matchBill(t, existing, cfg)?.copy(billPaidBy = t.smsId) else null
        return IngestResult(txn, paid)
    }

    /** Runs a whole batch in time order, the way the store would, and returns the final list. */
    fun ingestAll(batch: List<RawSms>, trusted: Set<String> = emptySet(), now: Long = System.currentTimeMillis(), backfill: Boolean = false): List<Txn> {
        val out = mutableListOf<Txn>()
        for (s in batch.sortedBy { it.ts }) {
            val r = ingest(s, out, trusted, now, backfill) ?: continue
            r.paidBill?.let { pb -> out.replaceAll { if (it.smsId == pb.smsId) pb else it } }
            out += r.txn
        }
        return out
    }

    /** Parses assets/demo_sms.tsv into SMS dated relative to [today]. Ids are negative so they never clash with real ones. */
    fun demo(tsv: String, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))): List<RawSms> =
        tsv.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexedNotNull { i, line ->
                val p = line.split('\t')
                if (p.size < 4) return@mapIndexedNotNull null
                val ts = today.minusDays(p[0].trim().toLong())
                    .atTime(LocalTime.parse(p[1].trim()))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
                RawSms(id = -(i + 1).toLong(), ts = ts, sender = p[2].trim(), body = p[3].trim())
            }.toList()
}

/**
 * Same id whether the SMS arrives live (broadcast) or is read back from the inbox later,
 * so a message is never counted twice. Minute precision absorbs the small gap between the
 * two timestamps Android reports.
 */
fun stableSmsId(sender: String, body: String, sentTs: Long): Long {
    val s = "$sender|${body.trim()}|${sentTs / 60_000}"
    var h = 1125899906842597L
    for (ch in s) h = 31 * h + ch.code
    return h and Long.MAX_VALUE
}

/** Indian commercial senders look like "VM-HDFCBK" or "AX-ICICIT-S". Personal numbers are never read. */
fun isBusinessSender(address: String?): Boolean =
    address != null && Regex("""^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$""", RegexOption.IGNORE_CASE).matches(address.trim())

/** Scripted "live" SMS for demo mode, since a real SMS can't be faked on a real phone. */
object DemoLive {
    private val script = listOf(
        "AX-HDFCBK-S" to "Spent Rs.1,249.00 On HDFC Bank Card 5508 At PYU*BUNDL TECHNOLOGIES On {d} Not You? To Block+Reissue Call 18002586161",
        "AX-HDFCBK-S" to "Sent Rs.412.00 From HDFC Bank A/C *4821 To UBER INDIA SYSTEMS On {d} Ref {ref} Not You? Call 18002586161/SMS BLOCK UPI to 7308080808",
        "AX-HDFCBK-S" to "Sent Rs.412.00 From HDFC Bank A/C *4821 To UBER INDIA SYSTEMS On {d} Ref {ref} Not You? Call 18002586161/SMS BLOCK UPI to 7308080808",
    )

    fun next(step: Int, now: Long = System.currentTimeMillis()): RawSms {
        val (sender, tpl) = script[step % script.size]
        val d = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Kolkata"))
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
        val body = tpl.replace("{d}", d).replace("{ref}", "6293" + (now % 100_000_000).toString().padStart(8, '0'))
        return RawSms(stableSmsId(sender, body, now), now, sender, body)
    }
}
