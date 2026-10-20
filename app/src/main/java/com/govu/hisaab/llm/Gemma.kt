package com.govu.hisaab.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.govu.hisaab.core.DayFacts
import com.govu.hisaab.core.Summary
import com.govu.hisaab.core.Txn
import java.io.Closeable
import java.io.File

/**
 * Gemma 3 1B (int4, ~550 MB) running on the phone through MediaPipe's LLM Inference API.
 * The app has no INTERNET permission, so nothing here can leave the device even by accident.
 *
 * Gemma does two jobs only: name the merchant behind a cryptic SMS descriptor, and turn the
 * morning numbers into a short note. Amounts and alerts never depend on it.
 */
class Gemma private constructor(private val llm: LlmInference, val backend: String, val loadMs: Long) : Closeable {

    fun ask(prompt: String): String {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(1)
            .setTemperature(0f)
            .setRandomSeed(7)
            .build()
        LlmInferenceSession.createFromOptions(llm, opts).use { s ->
            // Gemma's chat turns. Without them the 1B model just continues the text,
            // which in the first test meant echoing the instructions back.
            s.addQueryChunk("<start_of_turn>user\n${prompt.trim()}<end_of_turn>\n<start_of_turn>model\n")
            return s.generateResponse().replace("<end_of_turn>", "").trim()
        }
    }

    fun tokens(text: String): Int = runCatching { llm.sizeInTokens(text) }.getOrDefault(text.length / 4)

    /** "Swiggy" + "Food" for "BUNDL TECHNOLOGIES". Falls back to the raw text if the answer is malformed. */
    fun nameMerchant(t: Txn): Pair<String, String> {
        val answer = ask(merchantPrompt(t))
        Log.i(TAG, "merchant '${t.merchantRaw}' -> '$answer'")
        return parseMerchant(answer, t)
    }

    /** Two friendly sentences, or the plain template if Gemma used a number it wasn't given. */
    fun morningNote(f: DayFacts): Pair<String, Boolean> {
        val facts = Summary.template(f)
        val note = ask(
            """Rewrite these spending facts as a short, warm morning note of at most 2 sentences.
Copy every number exactly as written. Do not add any number that is not in the facts. No greeting, no advice.

Facts: $facts

Note:""",
        ).lineSequence().filter { it.isNotBlank() }.joinToString(" ").trim()
        return if (note.isNotBlank() && Summary.numbersAreGrounded(note, f)) note to true else facts to false
    }

    /** Load time, then a fixed labelling prompt, so the numbers in the post are reproducible. */
    fun benchmark(): String {
        val prompt = merchantPrompt(BENCH)
        val t0 = SystemClock.elapsedRealtime()
        val out = ask(prompt)
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "BENCH answer: $out")
        val outTok = tokens(out)
        val inTok = tokens(prompt)
        return "backend=$backend load=${loadMs}ms prompt=${inTok}tok answer=${outTok}tok total=${ms}ms " +
            "(~${"%.1f".format((inTok + outTok) * 1000.0 / ms)} tok/s overall) -> \"$out\""
    }

    /** Runs the 24-merchant check from [com.govu.hisaab.core.EvalSet] and returns a one-line score. */
    fun evaluate(onEach: (String) -> Unit = {}): String {
        var names = 0; var cats = 0; var ms = 0L
        val misses = mutableListOf<String>()
        for (c in com.govu.hisaab.core.EvalSet.cases) {
            val t = Txn(smsId = 0, ts = 0, sender = "", body = "", kind = c.kind, amount = 0.0, merchantRaw = c.raw, merchantKey = c.raw)
            val t0 = SystemClock.elapsedRealtime()
            val raw = ask(merchantPrompt(t))
            ms += SystemClock.elapsedRealtime() - t0
            val (name, cat) = parseMerchant(raw, t)
            val nOk = com.govu.hisaab.core.EvalSet.nameOk(c, name)
            val cOk = com.govu.hisaab.core.EvalSet.catOk(c, cat)
            if (nOk) names++
            if (cOk) cats++
            if (!nOk || !cOk) misses += "${c.raw}→$name|$cat"
            val line = "EVAL ${if (nOk) "✓" else "✗"}${if (cOk) "✓" else "✗"} ${c.raw} -> $name | $cat   (raw: ${raw.replace('\n', ' ')})"
            Log.i(TAG, line); onEach(line)
        }
        val n = com.govu.hisaab.core.EvalSet.cases.size
        val r = "names $names/$n, categories $cats/$n, ${ms / n} ms each on $backend. Misses: ${misses.joinToString("; ")}"
        Log.i(TAG, "EVAL RESULT $r")
        return r
    }

    override fun close() = llm.close()

    companion object {
        const val TAG = "Hisaab"

        val CATEGORIES = listOf(
            "Food", "Groceries", "Shopping", "Bills", "Travel", "Fuel", "Subscriptions", "Health",
            "Entertainment", "Transfers", "Salary", "Investments", "EMI", "Cash", "Other",
        )

        private val BENCH = Txn(
            smsId = 0, ts = 0, sender = "AX-HDFCBK-S", kind = com.govu.hisaab.core.Kind.DEBIT, amount = 349.0,
            merchantRaw = "PYU*BUNDL TECHNOLOGIES", merchantKey = "PYU BUNDL",
            body = "Spent Rs.349.00 On HDFC Bank Card 5508 At PYU*BUNDL TECHNOLOGIES On 2026-10-20:13:05:10",
        )

        fun modelFile(ctx: Context): File? =
            ctx.getExternalFilesDir(null)?.listFiles { f -> f.name.endsWith(".task") }?.maxByOrNull { it.length() }

        /** Loads the model. CPU by default: it's the backend that works on every phone. */
        fun open(ctx: Context, gpu: Boolean = false): Gemma? {
            val f = modelFile(ctx) ?: return null
            val t0 = SystemClock.elapsedRealtime()
            return runCatching {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(768)
                    .setMaxTopK(40)
                    .setPreferredBackend(if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
                    .build()
                Gemma(LlmInference.createFromOptions(ctx, opts), if (gpu) "GPU" else "CPU", SystemClock.elapsedRealtime() - t0)
            }.onFailure { Log.e(TAG, "Gemma load failed (gpu=$gpu)", it) }.getOrNull()
        }

        fun merchantPrompt(t: Txn): String {
            val what = when (t.kind) {
                com.govu.hisaab.core.Kind.CREDIT -> "money received from"
                com.govu.hisaab.core.Kind.BILL -> "a bill from"
                else -> "a payment to"
            }
            return """You label Indian bank SMS. Give the real brand or person name behind the payee text, and one category.
Reply with exactly one line in the form: Name | Category
Category must be one of: ${CATEGORIES.joinToString(", ")}.

Use the brand people know, not the company's legal name.
Examples:
BUNDL TECHNOLOGIES (a payment to) -> Swiggy | Food
ROPPEN TRANSPORTATION SERVICES (a payment to) -> Rapido | Travel
SPORTA TECHNOLOGIES (a payment to) -> Dream11 | Entertainment
IRCTC WEB UPI (a payment to) -> IRCTC | Travel
BPCL SAI FUELS (a payment to) -> BPCL | Fuel
RAZ*CULTFIT (a payment to) -> Cult.fit | Health
BBPS-BESCOM (a bill from) -> BESCOM | Bills
VODAFONE IDEA (a payment to) -> Vi | Bills
rk.verma@okhdfcbank (money received from) -> R K Verma | Transfers
ZERODHA BROKING (a payment to) -> Zerodha | Investments

Payee: ${t.merchantRaw.ifBlank { "(none)" }} ($what)"""
        }

        /** Words the model uses for a category, mapped onto ours. */
        private val synonyms = mapOf(
            "telecom" to "Bills", "utilit" to "Bills", "recharge" to "Bills", "electric" to "Bills", "mobile" to "Bills",
            "insurance" to "Bills", "grocer" to "Groceries", "supermarket" to "Groceries", "restaurant" to "Food",
            "dining" to "Food", "food" to "Food", "transport" to "Travel", "cab" to "Travel", "taxi" to "Travel",
            "flight" to "Travel", "petrol" to "Fuel", "streaming" to "Subscriptions", "ott" to "Subscriptions",
            "subscription" to "Subscriptions", "pharma" to "Health", "medical" to "Health", "movie" to "Entertainment",
            "gaming" to "Entertainment", "e-commerce" to "Shopping", "ecommerce" to "Shopping", "retail" to "Shopping",
            "fashion" to "Shopping", "loan" to "EMI", "invest" to "Investments", "mutual fund" to "Investments",
            "transfer" to "Transfers", "salary" to "Salary",
        )

        fun normaliseCategory(raw: String): String {
            val c = raw.trim().trim('"', '*', '.', ' ')
            CATEGORIES.firstOrNull { c.startsWith(it, ignoreCase = true) }?.let { return it }
            val l = c.lowercase()
            return synonyms.entries.firstOrNull { it.key in l }?.value ?: "Other"
        }

        fun parseMerchant(answer: String, t: Txn): Pair<String, String> {
            val fallbackName = t.merchantRaw.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val line = answer.lineSequence().map { it.trim() }.firstOrNull { '|' in it }
                ?: return fallbackName.ifBlank { "Unknown" } to "Other"
            // The model sometimes repeats the example shape "PAYEE (a payment to) -> Name | Category".
            val left = line.substringBefore('|').let { if ("->" in it) it.substringAfterLast("->") else it }
            val name = left.replace(Regex("""\((?:a payment to|a bill from|money received from)\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^(?:payee|answer|name)\s*:\s*""", RegexOption.IGNORE_CASE), "")
                .trim().trim('"', '*', '.', ' ').take(32).ifBlank { fallbackName }
            return name to normaliseCategory(line.substringAfter('|'))
        }
    }
}
