package com.govu.hisaab

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.govu.hisaab.core.DayFacts
import com.govu.hisaab.core.Ingest
import com.govu.hisaab.core.Kind
import com.govu.hisaab.core.Summary
import com.govu.hisaab.core.Txn
import com.govu.hisaab.core.Verdict
import com.govu.hisaab.core.rupees
import com.govu.hisaab.data.SmsSource
import com.govu.hisaab.data.Store
import com.govu.hisaab.llm.Gemma
import com.govu.hisaab.ui.Backdrop
import com.govu.hisaab.ui.Chip
import com.govu.hisaab.ui.Glass
import com.govu.hisaab.ui.HisaabTheme
import com.govu.hisaab.ui.LocalTokens
import com.govu.hisaab.ui.SectionTitle
import com.govu.hisaab.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { getSharedPreferences("hisaab", MODE_PRIVATE) }
            var mode by remember { mutableStateOf(prefs.getString("theme", "system")!!) }
            val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            HisaabTheme(dark) {
                Root(dark, onToggleTheme = {
                    mode = if (dark) "light" else "dark"
                    prefs.edit().putString("theme", mode).apply()
                })
            }
        }
    }
}

private val zone = ZoneId.of("Asia/Kolkata")
private val whenFmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy, HH:mm", Locale.ENGLISH)
private fun fmtTs(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).format(whenFmt)
private fun money(t: Txn) = if (t.currency == "INR") rupees(t.amount) else "${t.currency} ${"%.2f".format(t.amount)}"

private class Snapshot(val txns: List<Txn>, val facts: DayFacts, val note: String, val noteByGemma: Boolean, val demo: Boolean, val progress: String)

@Composable
private fun Root(dark: Boolean, onToggleTheme: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var snap by remember { mutableStateOf<Snapshot?>(null) }
    val reload: () -> Unit = { tick++ }

    LaunchedEffect(tick) {
        snap = withContext(Dispatchers.IO) {
            val store = Store.get(ctx)
            val all = store.all(System.currentTimeMillis() - 120L * 24 * 3600 * 1000)
            val facts = Summary.facts(all, Jobs.yesterday())
            val saved = store.get("morning_note")
            val savedDay = store.get("morning_day")
            val fresh = saved != null && savedDay == facts.day.format(Summary.dayFmt)
            Snapshot(
                all, facts, if (fresh) saved!! else Summary.template(facts),
                fresh && store.get("morning_by_gemma") == "true", Store.isDemo(ctx), store.get("enrich_progress") ?: "",
            )
        }
    }
    // Background jobs write to the store; poll lightly while the app is open.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(4000); tick++ } }

    Backdrop(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = t.surface.copy(alpha = 0.92f)) {
                    listOf("Today" to Icons.Outlined.WbSunny, "Bills" to Icons.Outlined.Receipt, "Setup" to Icons.Outlined.Settings)
                        .forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = { Icon(icon, label) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = t.primary, selectedTextColor = t.primary,
                                    indicatorColor = t.primary.copy(alpha = 0.12f), unselectedIconColor = t.text2, unselectedTextColor = t.text2,
                                ),
                            )
                        }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).statusBarsPadding()) {
                Header(dark, snap?.demo == true, onToggleTheme)
                val s = snap
                if (s == null) Text("Loading…", color = t.text2, modifier = Modifier.padding(16.dp))
                else when (tab) {
                    0 -> TodayScreen(s, reload)
                    1 -> BillsScreen(s)
                    else -> SetupScreen(s, reload)
                }
            }
        }
    }
}

@Composable
private fun Header(dark: Boolean, demo: Boolean, onToggleTheme: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hisaab", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = t.text)
                if (demo) { Spacer(Modifier.width(8.dp)); Chip("DEMO DATA", t.warning) }
            }
            Text("Your bank SMS, read on this phone by Gemma", fontSize = 12.sp, color = t.text2)
        }
        IconButton(onClick = onToggleTheme) {
            Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, "Theme", tint = t.text2)
        }
    }
}

@Composable
private fun TodayScreen(s: Snapshot, reload: () -> Unit) {
    val t = LocalTokens.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = s.txns.filter { it.alerts.isNotEmpty() && it.verdict == Verdict.NONE }
    val flagged = s.txns.filter { it.verdict == Verdict.NOT_MINE }
    val recent = s.txns.filter { it.kind == Kind.DEBIT || it.kind == Kind.CREDIT }.take(40)
    var editing by remember { mutableStateOf<Txn?>(null) }

    fun verdict(tx: Txn, v: Verdict) = scope.launch {
        withContext(Dispatchers.IO) { Store.get(ctx).setVerdict(tx.id, v) }
        reload()
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Yesterday, ${s.facts.day.format(Summary.dayFmt)}", fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
                    if (s.noteByGemma) Chip("Gemma · on device", t.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text(s.note, color = t.text2, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Spent", rupees(s.facts.spent), Modifier.weight(1f))
                    Stat("This month", rupees(s.facts.monthSpent), Modifier.weight(1f))
                    Stat("To check", open.size.toString(), Modifier.weight(1f), if (open.isEmpty()) t.success else t.warning)
                }
            }
        }
        if (open.isNotEmpty()) {
            item { SectionTitle("Was this you?") }
            items(open, key = { it.id }) { tx -> AlertCard(tx, onMine = { verdict(tx, Verdict.MINE) }, onNotMine = { verdict(tx, Verdict.NOT_MINE) }) }
        }
        if (flagged.isNotEmpty()) {
            item { SectionTitle("You said: not me") }
            items(flagged, key = { "f${it.id}" }) { tx ->
                Glass(tint = t.danger) {
                    Text("${money(tx)} · ${tx.displayName}", fontWeight = FontWeight.SemiBold, color = t.text)
                    Text("Call the number in the bank's SMS below, or block the card or UPI from your bank's app.", color = t.text2, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(tx.body, color = t.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item { SectionTitle("Recent") }
        if (recent.isEmpty()) item { Text("No bank SMS yet. Open Setup to scan your inbox or load demo data.", color = t.text2, modifier = Modifier.padding(4.dp)) }
        items(recent, key = { "r${it.id}" }) { tx -> TxnRow(tx) { editing = tx } }
    }
    editing?.let { tx ->
        RenameDialog(tx, onDismiss = { editing = null }) { name, cat ->
            editing = null
            scope.launch {
                withContext(Dispatchers.IO) { Store.get(ctx).saveMerchant(tx.merchantKey, name, cat) }
                reload()
            }
        }
    }
}

/** Fix a merchant once; every past and future payment to it follows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenameDialog(tx: Txn, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val t = LocalTokens.current
    var name by remember { mutableStateOf(tx.displayName) }
    var cat by remember { mutableStateOf(tx.category ?: "Other") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.surface,
        title = { Text("Who is this?", color = t.text) },
        text = {
            Column {
                Text("SMS said: ${tx.merchantRaw}", color = t.muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it.take(32) }, singleLine = true, label = { Text("Name") })
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Gemma.CATEGORIES.forEach { c ->
                        FilterChip(selected = c == cat, onClick = { cat = c }, label = { Text(c, fontSize = 12.sp) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), cat) }) { Text("Save for all", color = t.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = t.text2) } },
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    val t = LocalTokens.current
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = t.muted)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color ?: t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlertCard(tx: Txn, onMine: () -> Unit, onNotMine: () -> Unit) {
    val t = LocalTokens.current
    Glass(tint = t.warning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tx.displayName, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmtTs(tx.ts)} · ${listOfNotNull(tx.bank, tx.channel.name.lowercase()).joinToString(" ")}", fontSize = 12.sp, color = t.muted)
            }
            Text(money(tx), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = t.text)
        }
        Spacer(Modifier.height(8.dp))
        tx.alerts.forEach { a ->
            Text(a.type.title, color = t.warning, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(a.reason, color = t.text2, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMine, colors = ButtonDefaults.buttonColors(containerColor = t.primary, contentColor = androidx.compose.ui.graphics.Color.White), modifier = Modifier.weight(1f)) { Text("It was me") }
            OutlinedButton(onClick = onNotMine, modifier = Modifier.weight(1f)) { Text("Not me", color = t.danger) }
        }
    }
}

@Composable
private fun TxnRow(tx: Txn, onEdit: () -> Unit) {
    val t = LocalTokens.current
    Glass(Modifier.clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tx.displayName, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(tx.category ?: "naming…", if (tx.category == null) t.muted else t.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(fmtTs(tx.ts), fontSize = 12.sp, color = t.muted)
                }
            }
            Text(
                (if (tx.kind == Kind.CREDIT) "+" else "−") + money(tx),
                fontWeight = FontWeight.SemiBold,
                color = if (tx.kind == Kind.CREDIT) t.success else t.text,
            )
        }
    }
}

@Composable
private fun BillsScreen(s: Snapshot) {
    val t = LocalTokens.current
    val now = System.currentTimeMillis()
    val bills = s.txns.filter { it.kind == Kind.BILL }
    val upcoming = s.txns.filter { it.kind == Kind.UPCOMING }
    val byId = s.txns.associateBy { it.smsId }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Bills") }
        if (bills.isEmpty()) item { Text("No bill SMS yet.", color = t.text2) }
        items(bills, key = { it.id }) { b ->
            val paid = b.billPaidBy?.let { byId[it] }
            val (label, color) = when {
                paid != null && kotlin.math.abs(paid.amount - b.amount) <= 1.0 -> "Paid ${rupees(paid.amount)} · matches" to t.success
                paid != null -> "Paid ${rupees(paid.amount)} · bill was ${rupees(b.amount)}" to t.danger
                b.dueTs != null && b.dueTs < now -> "Overdue?" to t.danger
                b.dueTs != null -> {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(Summary.localDate(now), Summary.localDate(b.dueTs)).toInt()
                    (when (days) { 0 -> "Due today"; 1 -> "Due tomorrow"; else -> "Due in $days days" }) to t.warning
                }
                else -> "Unpaid" to t.warning
            }
            Glass(tint = if (color == t.danger) t.danger else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.displayName, fontWeight = FontWeight.SemiBold, color = t.text)
                        Text(b.dueTs?.let { "Due ${Summary.localDate(it).format(Summary.dayFmt)}" } ?: "Due date not in SMS", fontSize = 12.sp, color = t.muted)
                    }
                    Text(rupees(b.amount), fontWeight = FontWeight.Bold, color = t.text)
                }
                Spacer(Modifier.height(6.dp))
                Chip(label, color)
                b.alerts.forEach { Spacer(Modifier.height(6.dp)); Text(it.reason, color = t.warning, fontSize = 13.sp) }
            }
        }
        item { SectionTitle("Auto-debits announced") }
        if (upcoming.isEmpty()) item { Text("None yet.", color = t.text2) }
        items(upcoming, key = { "u${it.id}" }) { u ->
            Glass(tint = if (u.alerts.isNotEmpty()) t.warning else null) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(u.displayName, fontWeight = FontWeight.SemiBold, color = t.text)
                        Text(u.dueTs?.let { "On ${Summary.localDate(it).format(Summary.dayFmt)}" } ?: "", fontSize = 12.sp, color = t.muted)
                    }
                    Text(rupees(u.amount), fontWeight = FontWeight.Bold, color = t.text)
                }
                u.alerts.forEach { Spacer(Modifier.height(6.dp)); Text(it.reason, color = t.warning, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SetupScreen(s: Snapshot, reload: () -> Unit) {
    val t = LocalTokens.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var bench by remember { mutableStateOf(Store.get(ctx).get("bench") ?: "") }
    var evalResult by remember { mutableStateOf(Store.get(ctx).get("eval") ?: "") }

    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var smsOk by remember { mutableStateOf(has(Manifest.permission.READ_SMS) && has(Manifest.permission.RECEIVE_SMS)) }
    var notifOk by remember { mutableStateOf(has(Manifest.permission.POST_NOTIFICATIONS)) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        smsOk = has(Manifest.permission.READ_SMS) && has(Manifest.permission.RECEIVE_SMS)
        notifOk = has(Manifest.permission.POST_NOTIFICATIONS)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w -> w.write(csv(Store.get(ctx).all())) }
        }
    }
    val internet = remember {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.INTERNET) == true
    }
    var modelPick by remember { mutableIntStateOf(0) }
    val model = remember(tick(s), modelPick) { Gemma.modelFile(ctx) }
    val allModels = remember(tick(s)) { Gemma.models(ctx) }

    fun job(label: String, block: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = label
        scope.launch {
            try { withContext(Dispatchers.Default) { block() } } finally { busy = ""; reload() }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Glass(tint = if (internet) t.danger else t.success) {
                Text(if (internet) "Internet permission present" else "No internet permission", fontWeight = FontWeight.SemiBold, color = t.text)
                Text(
                    "Checked live from this app's own manifest. Hisaab cannot send your SMS anywhere: Gemma runs on this phone, and Android blocks every network call.",
                    color = t.text2, fontSize = 13.sp,
                )
            }
        }
        item { SectionTitle("Permissions") }
        item {
            Glass {
                Line("Read bank SMS", smsOk); Line("Show alerts", notifOk)
                if (!smsOk || !notifOk) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        askPerms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
                    }, colors = ButtonDefaults.buttonColors(containerColor = t.primary, contentColor = androidx.compose.ui.graphics.Color.White)) { Text("Grant") }
                }
                Text("Only business senders like VM-HDFCBK are read. Messages from people are skipped unread.", color = t.muted, fontSize = 12.sp)
            }
        }
        item { SectionTitle("Gemma on this phone") }
        item {
            Glass {
                Text(model?.let { "${Gemma.label(it)} · ${it.length() / 1_048_576} MB" } ?: "No model yet", fontWeight = FontWeight.SemiBold, color = t.text)
                if (allModels.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allModels.forEach { f ->
                        FilterChip(selected = f == model, onClick = {
                            ctx.getSharedPreferences("hisaab", android.content.Context.MODE_PRIVATE).edit().putString("model", f.name).apply()
                            modelPick++
                        }, label = { Text(Gemma.label(f), fontSize = 12.sp) })
                    }
                }
                if (model == null) Text("Copy gemma3-1b-it-int4.task into Android/data/com.govu.hisaab/files/ (see README).", color = t.text2, fontSize = 13.sp)
                if (s.progress.isNotEmpty()) Text("Naming merchants: ${s.progress}", color = t.primary, fontSize = 13.sp)
                if (bench.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(bench, color = t.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = model != null && busy.isEmpty(), onClick = {
                        job("Benchmarking on CPU…") {
                            val r = Gemma.open(ctx, gpu = false)?.use { it.benchmark() } ?: "CPU load failed"
                            bench = r; Store.get(ctx).put("bench", r)
                        }
                    }) { Text("Benchmark CPU") }
                    OutlinedButton(enabled = model != null && busy.isEmpty(), onClick = {
                        job("Benchmarking on GPU…") {
                            val r = Gemma.open(ctx, gpu = true)?.use { it.benchmark() } ?: "GPU backend not available on this phone"
                            bench = r; Store.get(ctx).put("bench", r)
                        }
                    }) { Text("GPU") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = model != null && busy.isEmpty(), onClick = {
                        job("Checking Gemma on 24 known merchants…") {
                            val r = Gemma.open(ctx)?.use { it.evaluate() } ?: "Model load failed"
                            Store.get(ctx).put("eval", r); evalResult = r
                        }
                    }) { Text("Accuracy check") }
                    OutlinedButton(enabled = model != null, onClick = { Jobs.enrichSoon(ctx) }) { Text("Name merchants") }
                }
                if (evalResult.isNotEmpty()) Text(evalResult, color = t.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        item { SectionTitle("Data") }
        item {
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demo mode", fontWeight = FontWeight.SemiBold, color = t.text)
                        Text("Made-up SMS in a separate database. Your real data is untouched.", color = t.text2, fontSize = 12.sp)
                    }
                    Switch(checked = s.demo, onCheckedChange = { on ->
                        job("Switching…") {
                            Store.setDemo(ctx, on)
                            if (on) {
                                ctx.getSharedPreferences("hisaab", android.content.Context.MODE_PRIVATE).edit().putInt("sim_step", 0).apply()
                                val d = Store.demo(ctx)
                                d.wipe()
                                Ingest.demo(ctx.assets.open("demo_sms.tsv").bufferedReader().readText())
                                    .sortedBy { it.ts }.forEach { d.ingest(it) }
                            }
                            Jobs.enrichSoon(ctx)
                        }
                    })
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = t.border)
                Button(enabled = smsOk && !s.demo && busy.isEmpty(), onClick = {
                    job("Scanning inbox…") {
                        val store = Store.real(ctx)
                        val since = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
                        SmsSource.inbox(ctx, since).forEach { store.ingest(it, backfill = true) }
                        Jobs.enrichSoon(ctx)
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = t.primary, contentColor = androidx.compose.ui.graphics.Color.White)) { Text("Scan last 90 days of SMS") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Jobs.morningNow(ctx) }) { Text("Morning note now") }
                    OutlinedButton(onClick = { exportCsv.launch("hisaab_${java.time.LocalDate.now()}.csv") }) { Text("Export CSV") }
                }
                if (s.demo) OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val prefs = ctx.getSharedPreferences("hisaab", android.content.Context.MODE_PRIVATE)
                        val step = prefs.getInt("sim_step", 0)
                        prefs.edit().putInt("sim_step", step + 1).apply()
                        Store.demo(ctx).ingest(com.govu.hisaab.core.DemoLive.next(step))?.let { tx ->
                            if (tx.alerts.isNotEmpty()) com.govu.hisaab.work.Notifier.alert(ctx, tx)
                        }
                        Jobs.enrichSoon(ctx)
                    }
                }) { Text("Simulate a bank SMS") }
                if (busy.isNotEmpty()) Text(busy, color = t.primary, fontSize = 13.sp)
            }
        }
    }
}

private fun tick(s: Snapshot) = s.txns.size

@Composable
private fun Line(label: String, ok: Boolean) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.text, modifier = Modifier.weight(1f))
        Chip(if (ok) "Allowed" else "Needed", if (ok) t.success else t.warning)
    }
}

private fun csv(all: List<Txn>): String = buildString {
    appendLine("date,time,bank,account,channel,kind,amount,currency,merchant,category,ref,alerts,verdict")
    val d = DateTimeFormatter.ISO_LOCAL_DATE
    val h = DateTimeFormatter.ofPattern("HH:mm")
    fun q(v: String?) = "\"" + (v ?: "").replace("\"", "\"\"") + "\""
    all.sortedBy { it.ts }.forEach { t ->
        val z = Instant.ofEpochMilli(t.ts).atZone(zone)
        appendLine(
            listOf(
                z.format(d), z.format(h), q(t.bank), q(t.account), t.channel.name, t.kind.name, "%.2f".format(t.amount), t.currency,
                q(t.displayName), q(t.category), q(t.ref), q(t.alerts.joinToString("; ") { it.type.name }), t.verdict.name,
            ).joinToString(","),
        )
    }
}
