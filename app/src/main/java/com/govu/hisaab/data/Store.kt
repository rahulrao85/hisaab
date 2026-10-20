package com.govu.hisaab.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.govu.hisaab.core.Alert
import com.govu.hisaab.core.Channel
import com.govu.hisaab.core.Ingest
import com.govu.hisaab.core.Kind
import com.govu.hisaab.core.RawSms
import com.govu.hisaab.core.Txn
import com.govu.hisaab.core.Verdict

/**
 * Everything lives in one SQLite file in the app's private storage. Demo mode uses a
 * separate file, so screenshots never touch real data.
 */
class Store private constructor(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE txn(
                id INTEGER PRIMARY KEY AUTOINCREMENT, sms_id INTEGER UNIQUE, ts INTEGER, sender TEXT, body TEXT,
                kind TEXT, amount REAL, currency TEXT, merchant_raw TEXT, merchant_key TEXT, account TEXT,
                channel TEXT, bank TEXT, ref TEXT, due_ts INTEGER, merchant TEXT, category TEXT,
                alerts TEXT, verdict TEXT, bill_paid_by INTEGER)""",
        )
        db.execSQL("CREATE INDEX txn_ts ON txn(ts)")
        db.execSQL("CREATE TABLE trusted(merchant_key TEXT PRIMARY KEY)")
        // One Gemma answer per merchant, reused forever: the model runs once per new payee, not per SMS.
        db.execSQL("CREATE TABLE merchant_cache(merchant_key TEXT PRIMARY KEY, name TEXT, category TEXT)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun all(sinceTs: Long = 0): List<Txn> =
        readableDatabase.rawQuery("SELECT * FROM txn WHERE ts >= ? ORDER BY ts DESC", arrayOf(sinceTs.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.toTxn()) }
        }

    @Synchronized
    fun trusted(): Set<String> = readableDatabase.rawQuery("SELECT merchant_key FROM trusted", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** Parses, de-duplicates, runs the rules and stores. Returns the new transaction, if the SMS was one. */
    @Synchronized
    fun ingest(sms: RawSms, backfill: Boolean = false): Txn? {
        val history = all(sms.ts - 200L * 24 * 3600 * 1000)
        val r = Ingest.ingest(sms, history, trusted(), backfill = backfill) ?: return null
        val cached = cachedMerchant(r.txn.merchantKey)
        val txn = if (cached != null) r.txn.copy(merchant = cached.first, category = cached.second) else r.txn
        writableDatabase.beginTransaction()
        try {
            val id = writableDatabase.insertWithOnConflict("txn", null, txn.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
            r.paidBill?.let { pb ->
                writableDatabase.execSQL("UPDATE txn SET bill_paid_by=? WHERE sms_id=?", arrayOf(pb.billPaidBy, pb.smsId))
            }
            writableDatabase.setTransactionSuccessful()
            return if (id > 0) txn.copy(id = id) else null
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun setVerdict(id: Long, verdict: Verdict) {
        writableDatabase.execSQL("UPDATE txn SET verdict=? WHERE id=?", arrayOf<Any>(verdict.name, id))
        if (verdict == Verdict.MINE) {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO trusted(merchant_key) SELECT merchant_key FROM txn WHERE id=? AND merchant_key<>''",
                arrayOf(id),
            )
        }
    }

    fun cachedMerchant(key: String): Pair<String, String>? =
        readableDatabase.rawQuery("SELECT name, category FROM merchant_cache WHERE merchant_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }

    /** Keys Gemma hasn't named yet, most frequent first. */
    @Synchronized
    fun unnamedMerchants(): List<Txn> =
        readableDatabase.rawQuery(
            """SELECT * FROM txn WHERE merchant IS NULL AND kind IN ('DEBIT','UPCOMING','BILL','CREDIT')
               GROUP BY merchant_key ORDER BY COUNT(*) DESC""",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTxn()) } }

    @Synchronized
    fun saveMerchant(key: String, name: String, category: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO merchant_cache(merchant_key, name, category) VALUES(?,?,?)",
            arrayOf(key, name, category),
        )
        writableDatabase.execSQL("UPDATE txn SET merchant=?, category=? WHERE merchant_key=?", arrayOf(name, category, key))
    }

    fun get(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    fun put(k: String, v: String) = writableDatabase.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES(?,?)", arrayOf(k, v))

    fun wipe() {
        writableDatabase.execSQL("DELETE FROM txn"); writableDatabase.execSQL("DELETE FROM trusted")
        writableDatabase.execSQL("DELETE FROM merchant_cache"); writableDatabase.execSQL("DELETE FROM kv")
    }

    private fun Txn.toValues() = ContentValues().apply {
        put("sms_id", smsId); put("ts", ts); put("sender", sender); put("body", body); put("kind", kind.name)
        put("amount", amount); put("currency", currency); put("merchant_raw", merchantRaw); put("merchant_key", merchantKey)
        put("account", account); put("channel", channel.name); put("bank", bank); put("ref", ref); put("due_ts", dueTs)
        put("merchant", merchant); put("category", category)
        put("alerts", alerts.joinToString("\n") { it.encode() }); put("verdict", verdict.name); put("bill_paid_by", billPaidBy)
    }

    private fun Cursor.str(n: String): String? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.lng(n: String): Long? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.toTxn() = Txn(
        id = lng("id")!!, smsId = lng("sms_id")!!, ts = lng("ts")!!, sender = str("sender") ?: "", body = str("body") ?: "",
        kind = Kind.valueOf(str("kind")!!), amount = getDouble(getColumnIndexOrThrow("amount")), currency = str("currency") ?: "INR",
        merchantRaw = str("merchant_raw") ?: "", merchantKey = str("merchant_key") ?: "", account = str("account"),
        channel = Channel.valueOf(str("channel") ?: "ACCOUNT"), bank = str("bank"), ref = str("ref"), dueTs = lng("due_ts"),
        merchant = str("merchant"), category = str("category"),
        alerts = (str("alerts") ?: "").lines().mapNotNull { Alert.decode(it) },
        verdict = Verdict.valueOf(str("verdict") ?: "NONE"), billPaidBy = lng("bill_paid_by"),
    )

    companion object {
        @Volatile private var real: Store? = null
        @Volatile private var demo: Store? = null

        fun isDemo(ctx: Context) = ctx.getSharedPreferences("hisaab", Context.MODE_PRIVATE).getBoolean("demo", false)

        fun setDemo(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences("hisaab", Context.MODE_PRIVATE).edit().putBoolean("demo", on).apply()

        fun get(ctx: Context): Store = if (isDemo(ctx)) demo(ctx) else real(ctx)

        fun real(ctx: Context): Store = real ?: synchronized(this) {
            real ?: Store(ctx.applicationContext, "hisaab.db").also { real = it }
        }

        fun demo(ctx: Context): Store = demo ?: synchronized(this) {
            demo ?: Store(ctx.applicationContext, "hisaab_demo.db").also { demo = it }
        }
    }
}
