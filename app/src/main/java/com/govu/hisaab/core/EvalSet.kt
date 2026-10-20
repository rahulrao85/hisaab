package com.govu.hisaab.core

/**
 * Real-world style payee descriptors from Indian bank SMS, with the answer a person would give.
 * None of these appear in Gemma's few-shot examples. Used for the on-phone accuracy check.
 */
data class EvalCase(val raw: String, val kind: Kind, val names: List<String>, val categories: Set<String>)

object EvalSet {
    private fun d(raw: String, names: List<String>, vararg cats: String) = EvalCase(raw, Kind.DEBIT, names, cats.toSet())

    val cases = listOf(
        d("ZOMATO LTD", listOf("zomato"), "Food"),
        d("UBER INDIA SYSTEMS", listOf("uber"), "Travel"),
        d("ANI TECHNOLOGIES", listOf("ola"), "Travel"),
        d("blinkit@hdfcbank", listOf("blinkit"), "Groceries"),
        d("KIRANAKART TECHNOLOGIES", listOf("zepto"), "Groceries"),
        d("INNOVATIVE RETAIL CONCEPTS", listOf("bigbasket", "big basket"), "Groceries"),
        d("AVENUE SUPERMARTS", listOf("dmart", "d-mart", "d mart"), "Groceries", "Shopping"),
        d("AMAZON PAY INDIA", listOf("amazon"), "Shopping"),
        d("FLIPKART INTERNET", listOf("flipkart"), "Shopping"),
        d("FSN E-COMMERCE VENTURES", listOf("nykaa"), "Shopping"),
        d("OPENAI *CHATGPT SUBSCR", listOf("chatgpt", "openai"), "Subscriptions"),
        d("NETFLIX", listOf("netflix"), "Subscriptions", "Entertainment"),
        d("YOUTUBEGOOGLE", listOf("youtube"), "Subscriptions", "Entertainment"),
        d("GOOGLE *Google One", listOf("google one", "google"), "Subscriptions"),
        d("BIGTREE ENTERTAINMENT", listOf("bookmyshow", "book my show"), "Entertainment"),
        d("BHARTI AIRTEL", listOf("airtel"), "Bills"),
        d("RELIANCE JIO INFOCOMM", listOf("jio"), "Bills"),
        d("TATA POWER DDL", listOf("tata power"), "Bills"),
        d("HPCL MAHALAXMI SERVICE STN", listOf("hpcl", "hindustan petroleum"), "Fuel"),
        d("APOLLO PHARMACIES", listOf("apollo"), "Health"),
        d("MAKEMYTRIP INDIA", listOf("makemytrip", "make my trip"), "Travel"),
        d("RAZ*DREAMPLUG TECHNOLOGIES", listOf("cred"), "Bills", "Transfers"),
        d("ONE97 COMMUNICATIONS", listOf("paytm"), "Shopping", "Transfers", "Other", "Bills"),
        d("GROWW INVEST TECH", listOf("groww"), "Investments"),
    )

    fun nameOk(c: EvalCase, answer: String) = c.names.any { it in answer.lowercase() }
    fun catOk(c: EvalCase, cat: String) = cat in c.categories
}
