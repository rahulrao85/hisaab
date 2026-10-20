package com.govu.hisaab.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Decides when to raise an alert. Deliberately rule-based: a 1B model on a phone should
 * never be the thing that calls a charge fraud. Gemma only explains and names things.
 */
object Rules {

    data class Config(
        val newMerchantMin: Double = 1_000.0,
        val spikeFactor: Double = 3.0,
        val spikeMin: Double = 500.0,
        val duplicateWindowMs: Long = 10 * 60 * 1000L,
        val largeMin: Double = 25_000.0,
        val oddHourEnd: Int = 5,
        val oddMin: Double = 500.0,
        val billJumpFactor: Double = 1.25,
        val billLookbackMs: Long = 45L * 24 * 3600 * 1000,
    )

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Words that appear in many billers' names and prove nothing on their own. */
    private val generic = setOf(
        "BANK", "CARD", "CREDIT", "DEBIT", "BILL", "PAYMENT", "PAYMENTS", "POSTPAID", "PREPAID", "ONLINE",
        "AUTOPAY", "ELECTRICITY", "MOBILE", "BROADBAND", "SERVICE", "SERVICES", "LTD", "LIMITED", "INDIA",
        "STATEMENT", "TOTAL", "DEAR", "CUSTOMER", "YOUR", "UPI", "BBPS",
    )

    fun evaluate(t: Txn, history: List<Txn>, trusted: Set<String>, cfg: Config = Config()): List<Alert> =
        when (t.kind) {
            Kind.DEBIT -> debit(t, history, trusted, cfg)
            Kind.BILL -> bill(t, history, cfg)
            Kind.UPCOMING -> upcoming(t, history, trusted)
            Kind.CREDIT -> emptyList()
        }

    private fun debit(t: Txn, history: List<Txn>, trusted: Set<String>, cfg: Config): List<Alert> {
        val out = mutableListOf<Alert>()
        val amt = rupees(t.amount)
        val prior = history.filter { it.kind == Kind.DEBIT && it.merchantKey == t.merchantKey && it.ts < t.ts }

        history.firstOrNull {
            it.kind == Kind.DEBIT && it.smsId != t.smsId && it.merchantKey == t.merchantKey &&
                abs(it.amount - t.amount) < 0.01 && abs(it.ts - t.ts) <= cfg.duplicateWindowMs &&
                (it.ref == null || it.ref != t.ref)
        }?.let {
            val mins = abs(it.ts - t.ts) / 60_000
            out += Alert(AlertType.DUPLICATE, "Same $amt to ${t.merchantRaw.ifBlank { "the same payee" }} twice within ${maxOf(1, mins)} min.")
        }

        if (t.currency != "INR" || "international" in t.body.lowercase()) {
            val what = if (t.currency != "INR") "${t.currency} ${"%.2f".format(t.amount)}" else amt
            out += Alert(AlertType.FOREIGN, "Charged in $what. Check you made a foreign or international purchase.")
        }

        if (prior.isEmpty() && t.merchantKey.isNotBlank() && t.merchantKey !in trusted && t.amount >= cfg.newMerchantMin) {
            out += Alert(AlertType.NEW_MERCHANT, "You've never paid ${t.merchantRaw} before, and this is $amt.")
        }

        if (prior.size >= 3) {
            val med = median(prior.map { it.amount })
            if (t.amount >= cfg.spikeMin && t.amount > cfg.spikeFactor * med) {
                out += Alert(AlertType.SPIKE, "$amt vs your usual ${rupees(med)} here (${prior.size} earlier payments).")
            }
        }

        val hour = Instant.ofEpochMilli(t.ts).atZone(zone).hour
        if (hour < cfg.oddHourEnd && (t.amount >= cfg.oddMin || t.currency != "INR")) {
            out += Alert(AlertType.ODD_HOUR, "$amt at ${"%02d".format(hour)}:xx, when you're usually asleep.")
        }

        if (t.amount >= cfg.largeMin) {
            out += Alert(AlertType.LARGE, "$amt left ${t.bank ?: "your account"} in one go.")
        }

        matchBill(t, history, cfg)?.let { b ->
            if (abs(b.amount - t.amount) > 1.0) {
                out += Alert(AlertType.BILL_MISMATCH, "${b.merchantRaw} billed ${rupees(b.amount)}, but $amt was debited.")
            }
        }
        return out
    }

    private fun bill(t: Txn, history: List<Txn>, cfg: Config): List<Alert> {
        val last = history
            .filter { it.kind == Kind.BILL && it.ts < t.ts && sameBiller(it.merchantKey, t.merchantKey) }
            .maxByOrNull { it.ts } ?: return emptyList()
        if (last.amount > 0 && t.amount > cfg.billJumpFactor * last.amount) {
            val pct = ((t.amount / last.amount - 1) * 100).toInt()
            return listOf(Alert(AlertType.BILL_JUMP, "${rupees(t.amount)}, up $pct% from last time's ${rupees(last.amount)}."))
        }
        return emptyList()
    }

    private fun upcoming(t: Txn, history: List<Txn>, trusted: Set<String>): List<Alert> {
        val seen = t.merchantKey in trusted || history.any {
            it.smsId != t.smsId && it.ts < t.ts && (it.kind == Kind.DEBIT || it.kind == Kind.UPCOMING) &&
                sameBiller(it.merchantKey, t.merchantKey)
        }
        return if (seen) emptyList()
        else listOf(Alert(AlertType.NEW_AUTOPAY, "${t.merchantRaw.ifBlank { "Someone" }} set up a ${rupees(t.amount)} auto-debit you haven't paid before."))
    }

    /** The open bill (or announced auto-debit) that this debit pays, if any. */
    fun matchBill(t: Txn, history: List<Txn>, cfg: Config = Config()): Txn? =
        history
            .filter {
                (it.kind == Kind.BILL || it.kind == Kind.UPCOMING) && it.billPaidBy == null &&
                    it.ts <= t.ts && t.ts - it.ts <= cfg.billLookbackMs && sameBiller(it.merchantKey, t.merchantKey)
            }
            .maxByOrNull { it.ts }

    fun sameBiller(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val ta = tokens(a)
        val tb = tokens(b)
        return ta.isNotEmpty() && ta.any { it in tb }
    }

    private fun tokens(k: String) = k.uppercase().split(' ', '@', '.', '-', '_')
        .filter { it.length >= 4 && it !in generic && it.any(Char::isLetter) }
        .toSet()

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
