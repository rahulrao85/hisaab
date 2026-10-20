package com.govu.hisaab.core

/** What a bank SMS turned out to be. */
enum class Kind { DEBIT, CREDIT, BILL, UPCOMING }

enum class Channel { UPI, CARD, ACCOUNT }

/** The user's answer to "Was this you?". */
enum class Verdict { NONE, MINE, NOT_MINE }

/**
 * One parsed SMS. Everything up to [ref] comes from the parser (pure regex, no model);
 * [merchant] and [category] are filled in later by Gemma.
 */
data class Txn(
    val id: Long = 0,
    val smsId: Long,
    val ts: Long,
    val sender: String,
    val body: String,
    val kind: Kind,
    val amount: Double,
    val currency: String = "INR",
    val merchantRaw: String,
    val merchantKey: String,
    val account: String? = null,
    val channel: Channel = Channel.ACCOUNT,
    val bank: String? = null,
    val ref: String? = null,
    val dueTs: Long? = null,
    val merchant: String? = null,
    val category: String? = null,
    val alerts: List<Alert> = emptyList(),
    val verdict: Verdict = Verdict.NONE,
    val billPaidBy: Long? = null,
) {
    val displayName: String get() = merchant ?: merchantRaw.ifBlank { "Unknown" }
    val isSpend: Boolean get() = kind == Kind.DEBIT
}

enum class AlertType(val title: String) {
    NEW_MERCHANT("First payment to this merchant"),
    SPIKE("Much higher than usual"),
    DUPLICATE("Possible double charge"),
    FOREIGN("Foreign-currency charge"),
    ODD_HOUR("Charge at an odd hour"),
    LARGE("Large debit"),
    BILL_MISMATCH("Doesn't match the bill"),
    BILL_JUMP("Bill jumped from last time"),
    NEW_AUTOPAY("New autopay or subscription"),
}

data class Alert(val type: AlertType, val reason: String) {
    fun encode() = "${type.name}|$reason"

    companion object {
        fun decode(s: String): Alert? {
            val i = s.indexOf('|')
            if (i < 0) return null
            val t = runCatching { AlertType.valueOf(s.substring(0, i)) }.getOrNull() ?: return null
            return Alert(t, s.substring(i + 1))
        }
    }
}

fun rupees(v: Double): String {
    // Indian grouping: 1,23,45,678
    val whole = v.toLong()
    val paise = Math.round((v - whole) * 100)
    val s = whole.toString()
    val grouped = if (s.length <= 3) s else {
        val head = s.dropLast(3)
        val tail = s.takeLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
    }
    return if (paise == 0L) "₹$grouped" else "₹$grouped.${paise.toString().padStart(2, '0')}"
}
