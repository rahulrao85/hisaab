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
            s.addQueryChunk(prompt)
            return s.generateResponse().trim()
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
        val outTok = tokens(out)
        val inTok = tokens(prompt)
        return "backend=$backend load=${loadMs}ms prompt=${inTok}tok answer=${outTok}tok total=${ms}ms " +
            "(~${"%.1f".format((inTok + outTok) * 1000.0 / ms)} tok/s overall) -> \"$out\""
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

Payee: BUNDL TECHNOLOGIES (a payment to) -> Swiggy | Food
Payee: IRCTC WEB UPI (a payment to) -> IRCTC | Travel
Payee: BPCL SAI FUELS (a payment to) -> BPCL petrol pump | Fuel
Payee: RAZ*CULTFIT (a payment to) -> Cult.fit | Health
Payee: BBPS-BESCOM (a bill from) -> BESCOM electricity | Bills
Payee: rk.verma@okhdfcbank (money received from) -> R K Verma | Transfers
Payee: ZERODHA BROKING (a payment to) -> Zerodha | Investments

Payee: ${t.merchantRaw.ifBlank { "(none)" }} ($what) ->"""
        }

        fun parseMerchant(answer: String, t: Txn): Pair<String, String> {
            val line = answer.lineSequence().map { it.trim().removePrefix("->").trim() }.firstOrNull { '|' in it }
            val fallbackName = t.merchantRaw.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            if (line == null) return fallbackName.ifBlank { "Unknown" } to "Other"
            val name = line.substringBefore('|').trim().trim('"', '*', '.').take(32).ifBlank { fallbackName }
            val catRaw = line.substringAfter('|').trim().trim('"', '*', '.')
            val cat = CATEGORIES.firstOrNull { catRaw.startsWith(it, ignoreCase = true) } ?: "Other"
            return name to cat
        }
    }
}
