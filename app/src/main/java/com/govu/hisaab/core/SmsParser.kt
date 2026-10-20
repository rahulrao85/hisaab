package com.govu.hisaab.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Turns an Indian bank / UPI / biller SMS into a [Txn], or null when it isn't one
 * (OTPs, promos, "payment received, thank you" receipts).
 *
 * Plain regex on purpose: amounts and account numbers must be exact, so the model never
 * touches them. Gemma only names the merchant and picks the category afterwards.
 */
object SmsParser {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private val skipWords = listOf(
        "otp", "one time password", "verification code", "do not share", "pre-approved",
        "pre approved", "apply now", "loan up to", "loan of upto", "cashback offer", "congratulations",
        "is requested", "has requested", "collect request",
    )

    private val upcomingWords = listOf(
        "will be debited", "will be charged", "will be deducted", "scheduled for", "due for debit",
        "mandate is set up", "e-mandate registered", "mandate created",
    )

    private val debitWords = listOf(
        "debited", "spent", "sent rs", "sent inr", "sent ₹", "paid", "used for", "withdrawn",
        "purchase of", "trf to", "transferred to", "autopay of", "charged", "payment of",
    )
    private val creditWords = listOf("credited", "received", "deposited", "refund of", "refunded")

    private val foreign = Regex("""\b(USD|EUR|GBP|AED|SGD|JPY|AUD|CAD|CHF|HKD)\s?([0-9][0-9,]*(?:\.\d+)?)""")
    private val inr = Regex("""(?:Rs\.?|INR|₹)\s?([0-9][0-9,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val bareDebit = Regex("""debited (?:by|for)\s?([0-9][0-9,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val billAmount = Regex(
        """(?:total (?:amount )?due|total amt due|bill (?:amount|amt|of)|amount due|amt due|bill amt)[^0-9₹]{0,18}(?:Rs\.?|INR|₹)?\s?([0-9][0-9,]*(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    private val account = Regex(
        """(?:a/?c|acct|account|card|ac|ending)\s*(?:no\.?\s*)?(?:with\s*)?[x*.\s]*(\d{3,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ref = Regex(
        """(?:ref(?:\s?no\.?)?|refno|rrn|upi ref(?:\s?no)?|upi)[\s:./#-]*(\d{9,12})""",
        RegexOption.IGNORE_CASE,
    )
    private val upiP2 = Regex("""UPI/P2[MA]/\d+/([^/\n]+?)(?:/|\s+Not|\s+-|$)""", RegexOption.IGNORE_CASE)
    private val vpa = Regex("""\b([a-z0-9][a-z0-9.\-_]{1,}@[a-z]{2,})\b""", RegexOption.IGNORE_CASE)

    private val stop = """(?=\s+(?:on|not you|not u|ref|refno|upi|avl|avbl|bal|via|from|using|in your|if not|call|txn|dated|at \d)\b|[.;,(]|\s*$)"""

    private val debitMerchant = listOf(
        Regex("""\bat\s+(.+?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""mandate (?:for|towards)\s+(.+?)\s+(?:of|for|rs|inr)""", RegexOption.IGNORE_CASE),
        Regex("""autopay (?:of\s+\S+\s+)?(?:to|for)\s+(.+?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""\btrf to\s+(.+?)$stop""", RegexOption.IGNORE_CASE),
        Regex(""";\s*(.+?)\s+credited""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:to|towards)\s+(.+?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""\bfor\s+([A-Z][A-Za-z0-9 &*'.-]{2,40}?)$stop"""),
    )
    private val creditMerchant = listOf(
        Regex("""\bfrom\s+(?:vpa\s+)?(.+?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""\bby\s+(?:vpa\s+)?(.+?)$stop""", RegexOption.IGNORE_CASE),
    )
    private val billMerchant = listOf(
        Regex("""your\s+(.+?)\s+(?:postpaid\s+|broadband\s+|electricity\s+|gas\s+)?bill""", RegexOption.IGNORE_CASE),
        Regex("""^(?:dear customer,?\s*)?(.+?)\s+(?:bill amt|bill amount|bill of|bill for|statement)""", RegexOption.IGNORE_CASE),
    )

    private val banks = linkedMapOf(
        "HDFC" to "HDFC", "ICICI" to "ICICI", "SBI" to "SBI", "AXIS" to "Axis", "KOTAK" to "Kotak",
        "HSBC" to "HSBC", "IDFC" to "IDFC First", "YESB" to "Yes Bank", "YES BANK" to "Yes Bank",
        "INDUS" to "IndusInd", "PNB" to "PNB", "BOB" to "Bank of Baroda", "CANARA" to "Canara",
        "AMEX" to "Amex", "SCB" to "Standard Chartered", "FEDERAL" to "Federal", "AUBANK" to "AU",
        "PAYTM" to "Paytm", "JUPITER" to "Jupiter", "ONECARD" to "OneCard",
    )

    fun parse(smsId: Long, ts: Long, sender: String, rawBody: String): Txn? {
        val body = rawBody.replace(Regex("""\s+"""), " ").trim()
        val lower = body.lowercase()
        if (skipWords.any { it in lower }) return null
        // A biller or card issuer confirming it got paid: the bank's own debit SMS already counts it.
        if (Regex("""payment of .{0,30}(?:has been )?received""").containsMatchIn(lower)) return null
        if ("thank you for (?:your )?payment".toRegex().containsMatchIn(lower)) return null

        val kind = detectKind(lower) ?: return null

        val (amount, currency) = findAmount(body, kind) ?: return null
        if (amount <= 0.0) return null

        val merchantRaw = cleanMerchant(findMerchant(body, kind) ?: "")
        val channel = when {
            "upi" in lower || vpa.containsMatchIn(body) -> Channel.UPI
            "card" in lower -> Channel.CARD
            else -> Channel.ACCOUNT
        }
        return Txn(
            smsId = smsId,
            ts = ts,
            sender = sender,
            body = body,
            kind = kind,
            amount = amount,
            currency = currency,
            merchantRaw = merchantRaw,
            merchantKey = merchantKey(merchantRaw),
            account = account.find(body)?.groupValues?.get(1),
            channel = channel,
            bank = findBank(sender, body),
            ref = ref.find(body)?.groupValues?.get(1),
            dueTs = if (kind == Kind.BILL || kind == Kind.UPCOMING) findDate(body) else null,
        )
    }

    private fun detectKind(lower: String): Kind? {
        if (upcomingWords.any { it in lower }) return Kind.UPCOMING
        // Word-boundary match: "Postpaid" must not read as "paid".
        fun first(words: List<String>) = words.mapNotNull { w ->
            Regex("""(?<![a-z])${Regex.escape(w)}""").find(lower)?.range?.first
        }.minOrNull()
        val d = first(debitWords)
        val c = first(creditWords)
        val looksLikeBill = Regex("""\b(bill|statement)\b""").containsMatchIn(lower) &&
            Regex("""\b(due|payable|amt|amount)\b""").containsMatchIn(lower)
        return when {
            d != null && (c == null || d < c) -> Kind.DEBIT
            looksLikeBill -> Kind.BILL
            c != null -> Kind.CREDIT
            else -> null
        }
    }

    private fun findAmount(body: String, kind: Kind): Pair<Double, String>? {
        if (kind == Kind.BILL) {
            billAmount.find(body)?.let { return num(it.groupValues[1]) to "INR" }
        }
        val f = foreign.find(body)
        val i = inr.findAll(body).firstOrNull { m ->
            val before = body.substring(maxOf(0, m.range.first - 16), m.range.first).lowercase()
            listOf("bal", "limit", "min due", "minimum").none { it in before }
        }
        return when {
            f != null && (i == null || f.range.first < i.range.first) -> num(f.groupValues[2]) to f.groupValues[1]
            i != null -> num(i.groupValues[1]) to "INR"
            else -> bareDebit.find(body)?.let { num(it.groupValues[1]) to "INR" }
        }
    }

    private fun findMerchant(body: String, kind: Kind): String? {
        upiP2.find(body)?.let { return it.groupValues[1] }
        val patterns = when (kind) {
            Kind.CREDIT -> creditMerchant
            Kind.BILL -> billMerchant
            Kind.DEBIT, Kind.UPCOMING -> debitMerchant
        }
        for (p in patterns) {
            val m = p.find(body) ?: continue
            val v = m.groupValues[1].trim()
            if (v.isBlank() || isAccountRef(v)) continue
            return v
        }
        return vpa.find(body)?.groupValues?.get(1)
    }

    private fun isAccountRef(v: String): Boolean {
        val l = v.lowercase()
        return Regex("""^(your |the )?(a/?c|acct|account|card|ac|bank)\b""").containsMatchIn(l) ||
            l.matches(Regex("""[x*\d\s]+"""))
    }

    private fun cleanMerchant(v: String): String = v
        .replace(Regex("""^(?:vpa|merchant|m/s\.?)\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(?:on|dated)\s+\d.*$""", RegexOption.IGNORE_CASE), "")
        .trim(' ', '.', ',', '-', ':', ';')
        .take(48)

    /** Stable grouping key: case, punctuation, company suffixes and long digit runs don't matter. */
    fun merchantKey(raw: String): String {
        if (vpa.matches(raw.trim())) return raw.trim().lowercase()
        return raw.uppercase()
            .replace(Regex("""\d{4,}"""), " ")
            .replace(Regex("""[^A-Z0-9 ]"""), " ")
            .replace(Regex("""\b(PVT|PRIVATE|LTD|LIMITED|INDIA|IN|LLP|INC|TECHNOLOGIES|TECH|SERVICES|PAYMENTS)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { raw.uppercase().trim() }
    }

    private fun findBank(sender: String, body: String): String? {
        val s = sender.uppercase()
        banks.entries.firstOrNull { it.key in s }?.let { return it.value }
        val b = body.uppercase()
        return banks.entries.firstOrNull { Regex("""\b${Regex.escape(it.key)}\b""").containsMatchIn(b) }?.value
    }

    private val dateFormats: List<DateTimeFormatter> = listOf(
        "d-MMM-uuuu", "d-MMM-uu", "d MMM uuuu", "d MMM uu", "d-M-uuuu", "d-M-uu", "d/M/uuuu", "d/M/uu",
        "d.M.uuuu", "dMMMuu", "uuuu-M-d",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    private val dueDate = Regex(
        """(?:due (?:on|date|by)?|by|before|on)[:\s]*(\d{1,2}[-/. ]?[A-Za-z]{3}[-/. ]?\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{4}-\d{1,2}-\d{1,2})""",
        RegexOption.IGNORE_CASE,
    )

    private fun findDate(body: String): Long? {
        for (m in dueDate.findAll(body)) {
            val s = m.groupValues[1]
            for (f in dateFormats) {
                val d = runCatching { LocalDate.parse(s, f) }.getOrNull() ?: continue
                return d.atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun num(s: String) = s.replace(",", "").toDoubleOrNull() ?: 0.0
}
