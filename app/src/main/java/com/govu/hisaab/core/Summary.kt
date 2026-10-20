package com.govu.hisaab.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The numbers behind the morning note. Code computes them; Gemma only phrases them. */
data class DayFacts(
    val day: LocalDate,
    val spent: Double,
    val count: Int,
    val topCategories: List<Pair<String, Double>>,
    val biggest: Txn?,
    val received: Double,
    val monthSpent: Double,
    val openAlerts: Int,
    val billsDueSoon: List<Txn>,
    val billsOverdue: List<Txn>,
) {
}

object Summary {
    private val zone = ZoneId.of("Asia/Kolkata")
    val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    fun localDate(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    fun facts(all: List<Txn>, day: LocalDate, now: Long = System.currentTimeMillis()): DayFacts {
        val onDay = all.filter { localDate(it.ts) == day }
        val debits = onDay.filter { it.kind == Kind.DEBIT && it.currency == "INR" }
        val month = all.filter {
            val d = localDate(it.ts)
            it.kind == Kind.DEBIT && it.currency == "INR" && d.year == day.year && d.month == day.month && !d.isAfter(day)
        }
        val today = localDate(now)
        val openBills = all.filter { (it.kind == Kind.BILL) && it.billPaidBy == null && it.dueTs != null }
        return DayFacts(
            day = day,
            spent = debits.sumOf { it.amount },
            count = debits.size,
            topCategories = debits.groupBy { it.category ?: "Other" }
                .mapValues { e -> e.value.sumOf { it.amount } }
                .entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
            biggest = debits.maxByOrNull { it.amount },
            received = onDay.filter { it.kind == Kind.CREDIT && it.currency == "INR" }.sumOf { it.amount },
            monthSpent = month.sumOf { it.amount },
            openAlerts = all.count { it.alerts.isNotEmpty() && it.verdict == Verdict.NONE },
            billsDueSoon = openBills.filter {
                val d = localDate(it.dueTs!!)
                !d.isBefore(today) && !d.isAfter(today.plusDays(3))
            },
            billsOverdue = openBills.filter { localDate(it.dueTs!!).isBefore(today) && localDate(it.dueTs).isAfter(today.minusDays(30)) },
        )
    }

    /** The plain version. Used as-is when Gemma isn't loaded, and as Gemma's input. */
    fun template(f: DayFacts): String = buildString {
        if (f.count == 0) append("No spends on ${f.day.format(dayFmt)}.")
        else {
            append("${f.day.format(dayFmt)}: ${rupees(f.spent)} across ${f.count} payment${if (f.count == 1) "" else "s"}")
            if (f.topCategories.isNotEmpty()) {
                append(", mostly ")
                append(f.topCategories.joinToString(", ") { "${it.first} ${rupees(it.second)}" })
            }
            append(".")
            f.biggest?.let { append(" Biggest: ${it.displayName} ${rupees(it.amount)}.") }
        }
        append(" Month so far: ${rupees(f.monthSpent)}.")
        if (f.received > 0) append(" Received ${rupees(f.received)}.")
        if (f.openAlerts > 0) append(" ${f.openAlerts} alert${if (f.openAlerts == 1) "" else "s"} need a yes or no.")
        f.billsDueSoon.forEach { append(" ${it.displayName} bill ${rupees(it.amount)} due ${localDate(it.dueTs!!).format(dayFmt)}.") }
        f.billsOverdue.forEach { append(" ${it.displayName} bill ${rupees(it.amount)} looks unpaid.") }
    }

    /**
     * Gemma writes one friendly line; the figures underneath come from code. The line may
     * describe the day but never state a figure, so it can't get an amount or its label wrong.
     */
    fun openerIsSafe(text: String): Boolean {
        val l = text.lowercase()
        return text.isNotBlank() && text.length <= 160 && text.none { it.isDigit() } &&
            listOf("₹", "rs.", "rs ", "inr", "rupee").none { it in l }
    }
}
