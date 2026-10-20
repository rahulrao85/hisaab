package com.govu.hisaab.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.govu.hisaab.core.Summary
import com.govu.hisaab.data.Store
import com.govu.hisaab.llm.Gemma
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Jobs {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** Name new merchants with Gemma, in the background, once per merchant. */
    fun enrichSoon(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "enrich", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<EnrichWorker>().build(),
    )

    /** 08:00 every day: yesterday in two lines. */
    fun scheduleMorning(ctx: Context) {
        val now = LocalDateTime.now(zone)
        var next = now.toLocalDate().atTime(8, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<MorningWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("morning", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun morningNow(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "morning-now", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<MorningWorker>().setInputData(workDataOf("now" to true)).build(),
    )

    fun yesterday(): LocalDate = LocalDate.now(zone).minusDays(1)
}

class EnrichWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        val todo = store.unnamedMerchants()
        if (todo.isEmpty()) return Result.success()
        val gemma = Gemma.open(applicationContext) ?: return Result.success()
        gemma.use { g ->
            for ((i, t) in todo.withIndex()) {
                if (isStopped) break
                val (name, cat) = g.nameMerchant(t)
                store.saveMerchant(t.merchantKey, name, cat)
                store.put("enrich_progress", "${i + 1}/${todo.size}")
            }
        }
        store.put("enrich_progress", "")
        return Result.success()
    }
}

class MorningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        val facts = Summary.facts(store.all(System.currentTimeMillis() - 62L * 24 * 3600 * 1000), Jobs.yesterday())
        val (note, byGemma) = Gemma.open(applicationContext)?.use { it.morningNote(facts) } ?: (Summary.template(facts) to false)
        Log.i(Gemma.TAG, "morning note (gemma=$byGemma): $note")
        store.put("morning_note", note)
        store.put("morning_by_gemma", byGemma.toString())
        store.put("morning_day", facts.day.format(Summary.dayFmt))
        Notifier.summary(applicationContext, "Yesterday, ${facts.day.format(Summary.dayFmt)}", note)
        return Result.success()
    }
}
