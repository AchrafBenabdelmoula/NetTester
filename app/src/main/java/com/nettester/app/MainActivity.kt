package com.nettester.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// ---------- Theme (meme palette que la version Windows) ----------
val BG = Color.rgb(17, 24, 39)
val CARD = Color.rgb(31, 41, 55)
val BORDER = Color.rgb(55, 65, 81)
val TXT = Color.rgb(243, 244, 246)
val MUTED = Color.rgb(156, 163, 175)
val BLUE = Color.rgb(59, 130, 246)
val GREEN = Color.rgb(34, 197, 94)
val RED = Color.rgb(239, 68, 68)
val ORANGE = Color.rgb(245, 158, 11)
val ROW_BAD = Color.rgb(74, 29, 36)

enum class St(val label: String, val color: Int) {
    IDLE("PRET", MUTED), RUN("EN COURS", BLUE), OK("OK", GREEN), WARN("ATTENTION", ORANGE), FAIL("ECHEC", RED)
}

class PingRes(val ms: Long, val ip: String?) { val ok get() = ms >= 0 }
class TestResult(val st: St, val text: String)

class Target(val name: String, val host: String) {
    var ip = "-"; var sent = 0L; var lost = 0L; var sum = 0L
    var min = Long.MAX_VALUE; var max = 0L; var down = false
    lateinit var head: TextView; lateinit var info: TextView; lateinit var row: LinearLayout
}

class MainActivity : Activity() {

    private val targets = ArrayList<Target>()
    @Volatile private var liveOn = false
    @Volatile private var loopActive = false
    private var cuts = 0
    private var lastRestore = ""
    private lateinit var banner: TextView
    private lateinit var btnLive: Button
    private lateinit var btnAll: Button
    private lateinit var liveState: TextView
    private val cards = ArrayList<TestCard>()
    private val traces = ArrayList<TracePanel>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val gw = gateway()
        if (gw != null) targets.add(Target("Passerelle (routeur)", gw))
        targets.add(Target("Google DNS", "8.8.8.8"))
        targets.add(Target("Google DNS 2", "8.8.4.4"))
        targets.add(Target("Cloudflare DNS", "1.1.1.1"))
        targets.add(Target("Google", "google.com"))
        targets.add(Target("MAGISTOR", "192.168.100.64"))
        targets.add(Target("Microsoft", "microsoft.com"))
        targets.add(Target("Facebook", "facebook.com"))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); addView(root) }
        setContentView(scroll)

        // En-tete
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        ht.addView(text("Net Tester", 22f, TXT, true))
        ht.addView(text("Testeur de connexion Internet", 12f, MUTED))
        head.addView(ht)
        root.addView(head)

        // Boutons principaux
        val btns = LinearLayout(this).apply { setPadding(0, dp(14), 0, dp(6)) }
        btnAll = button("TESTER TOUT", GREEN, Color.rgb(5, 46, 22)) { testAll() }
        val btnReset = button("ACTUALISER", Color.rgb(75, 85, 99), Color.WHITE) { resetAll() }
        btns.addView(btnAll, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        btns.addView(btnReset, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(btns)

        // Cartes de test
        cards.add(TestCard("Internet (Google)") { testInternet() })
        cards.add(TestCard("Passerelle Wi-Fi") { testGateway() })
        cards.add(TestCard("Wi-Fi / Reseau") { testWifi() })
        cards.add(TestCard("Speed Test") { testSpeed() })
        cards.forEach { root.addView(it.view, matchWrap(top = 8)) }

        // Ping continu
        val lh = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(6)) }
        lh.addView(text("Ping continu", 17f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        liveState = text("\u25CF En pause", 13f, ORANGE)
        lh.addView(liveState)
        btnLive = button("Reprendre", BLUE, Color.WHITE) { toggleLive() }
        lh.addView(btnLive, LinearLayout.LayoutParams(dp(110), dp(38)).apply { leftMargin = dp(10) })
        root.addView(lh)

        banner = text("Aucune coupure detectee", 13f, GREEN)
        banner.setPadding(dp(12), dp(8), dp(12), dp(8))
        root.addView(banner, matchWrap(bottom = 6))

        for (t in targets) {
            t.row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            t.row.background = box(CARD)
            t.head = text("\u25CF ${t.name}    ...", 14f, TXT, true)
            t.info = text(t.host, 11f, MUTED)
            t.row.addView(t.head); t.row.addView(t.info)
            root.addView(t.row, matchWrap(top = 5))
        }

        // Tracert (3 panneaux comme la version Windows)
        val th = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(6)) }
        th.addView(text("Tracert", 17f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        th.addView(button("TOUT LANCER", GREEN, Color.rgb(5, 46, 22)) { traces.forEach { it.start() } },
            LinearLayout.LayoutParams(dp(120), dp(38)).apply { rightMargin = dp(6) })
        th.addView(button("TOUT ARRETER", Color.rgb(75, 85, 99), Color.WHITE) { traces.forEach { it.stop() } },
            LinearLayout.LayoutParams(dp(120), dp(38)))
        root.addView(th)
        traces.add(TracePanel("Tracert 8.8.8.8", "8.8.8.8"))
        traces.add(TracePanel("Tracert MAGISTOR", "192.168.100.64"))
        traces.add(TracePanel("Tracert Passerelle", gw))
        traces.forEach { root.addView(it.view, matchWrap(top = 8)) }
    }

    override fun onPause() { super.onPause(); if (liveOn) toggleLive(); traces.forEach { it.stop() } }

    // ---------- UI helpers ----------
    private fun matchWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun box(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), BORDER)
    }

    private fun button(label: String, bg: Int, fg: Int, click: () -> Unit) = Button(this).apply {
        text = label; setTextColor(fg); isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(10).toFloat() }
        setOnClickListener { click() }
    }

    inner class TestCard(val title: String, val action: () -> TestResult) {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = box(CARD) }
        private val status = text("\u25CF PRET", 12f, MUTED, true)
        private val result = text("", 13f, MUTED)
        private val btn: Button
        var running = false
        init {
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text(title, 16f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(status)
            view.addView(top)
            result.setPadding(0, dp(6), 0, dp(6))
            view.addView(result)
            btn = button("TESTER", BLUE, Color.WHITE) { run() }
            view.addView(btn, LinearLayout.LayoutParams(dp(120), dp(38)))
        }
        fun set(st: St, msg: String? = null) {
            status.text = "\u25CF " + st.label; status.setTextColor(st.color)
            if (msg != null) result.text = msg
        }
        fun reset() { set(St.IDLE, ""); }
        fun run(done: (() -> Unit)? = null) {
            if (running) { done?.invoke(); return }
            running = true; btn.isEnabled = false; set(St.RUN, "Test en cours...")
            thread {
                val r = try { action() } catch (e: Exception) { TestResult(St.FAIL, "Erreur : ${e.message}") }
                runOnUiThread { set(r.st, r.text); running = false; btn.isEnabled = true; done?.invoke() }
            }
        }
    }

    class Probe(val ip: String?, val reached: Boolean, val ms: Long)

    // Equivalent de "tracert" : ping avec TTL croissant
    private fun probe(host: String, ttl: Int): Probe {
        return try {
            val t0 = System.nanoTime()
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-t", ttl.toString(), "-W", "2", host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            val tm = Regex("time=([0-9.]+)").find(out)
            val reply = Regex("bytes from ([0-9a-fA-F.:]+)").find(out)
            if (tm != null && reply != null) Probe(reply.groupValues[1], true, Math.round(tm.groupValues[1].toDouble()))
            else {
                val hop = Regex("[Ff]rom ([0-9a-fA-F.:]+)").find(out)
                if (hop != null) Probe(hop.groupValues[1], false, elapsed) else Probe(null, false, -1)
            }
        } catch (e: Exception) { Probe(null, false, -1) }
    }

    inner class TracePanel(val title: String, val host: String?) {
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = box(CARD) }
        private val state = text("\u25CF PRET", 12f, MUTED, true)
        private val out = text("", 11f, TXT).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, dp(8)) }
        private val btnRun: Button
        private val sb = StringBuilder()
        @Volatile var active = false

        init {
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text(title, 16f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(state)
            view.addView(top)
            view.addView(text(if (host != null) "Cible : $host   (max 30 sauts)" else "Cible indisponible (Wi-Fi deconnecte ?)", 11f, MUTED))
            val row = LinearLayout(this@MainActivity).apply { setPadding(0, dp(8), 0, 0) }
            btnRun = button("Lancer", BLUE, Color.WHITE) { if (active) stop() else start() }
            row.addView(btnRun, LinearLayout.LayoutParams(dp(100), dp(36)).apply { rightMargin = dp(8) })
            row.addView(button("Copier", Color.rgb(75, 85, 99), Color.WHITE) {
                if (sb.isNotEmpty()) (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("tracert", sb.toString()))
            }, LinearLayout.LayoutParams(dp(90), dp(36)))
            view.addView(row)
            view.addView(out)
        }

        private fun setState(st: St, label: String = st.label) { state.text = "\u25CF $label"; state.setTextColor(st.color) }
        private fun add(line: String) { sb.append(line).append('\n'); runOnUiThread { out.text = sb.toString() } }

        fun stop() { active = false }

        fun start() {
            if (host == null || active) return
            active = true; sb.setLength(0); out.text = ""
            btnRun.text = "Arreter"; setState(St.RUN)
            thread {
                add("Trace vers $host")
                var reached = false
                var h = 1
                while (active && h <= 30 && !reached) {
                    val times = ArrayList<String>(); var ip: String? = null
                    for (i in 0 until 3) {
                        if (!active) break
                        val r = probe(host, h)
                        times.add(if (r.ms < 0) "*" else "${r.ms} ms")
                        if (r.ip != null) ip = r.ip
                        if (r.reached) reached = true
                    }
                    if (!active) break
                    add(String.format(Locale.US, "%2d  %-8s %-8s %-8s %s", h, times[0], times[1], times[2], ip ?: "Delai depasse"))
                    h++
                }
                val ok = reached; val stopped = !active
                active = false
                add(if (ok) "Trace terminee." else if (stopped) "Arrete." else "Destination non atteinte.")
                runOnUiThread {
                    btnRun.text = "Lancer"
                    if (ok) setState(St.OK, "TERMINE") else if (stopped) setState(St.IDLE, "ARRETE") else setState(St.WARN, "NON ATTEINT")
                }
            }
        }
    }

    // ---------- Ping ----------
    private fun pingHost(host: String): PingRes {
        try {
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val ip = Regex("\\(([0-9a-fA-F.:]+)\\)").find(out)?.groupValues?.get(1)
            val m = Regex("time=([0-9.]+)").find(out)
            if (m != null) return PingRes(Math.round(m.groupValues[1].toDouble()), ip)
            return PingRes(-1, ip)
        } catch (e: Exception) {
            // Repli si la commande ping n'est pas disponible
            return try {
                val t0 = System.nanoTime()
                val a = InetAddress.getByName(host)
                if (a.isReachable(2000)) PingRes((System.nanoTime() - t0) / 1_000_000, a.hostAddress) else PingRes(-1, a.hostAddress)
            } catch (e2: Exception) { PingRes(-1, null) }
        }
    }

    private fun fmt(ms: Long) = if (ms < 0) "ECHEC" else "$ms ms"

    private fun wifi() = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Suppress("DEPRECATION")
    private fun gateway(): String? = try {
        val g = wifi().dhcpInfo.gateway
        if (g == 0) null else "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
    } catch (e: Exception) { null }

    // ---------- Tests ----------
    private fun testInternet(): TestResult {
        val ex = Executors.newFixedThreadPool(2)
        val fa = ex.submit(Callable { pingHost("8.8.8.8") })
        val fb = ex.submit(Callable { pingHost("google.com") })
        val a = fa.get(); val b = fb.get(); ex.shutdown()
        var txt = "8.8.8.8  ->  ${fmt(a.ms)}\ngoogle.com  ->  ${fmt(b.ms)}"
        if (b.ip != null) txt += "\nIP Google : ${b.ip}"
        return when {
            a.ok && b.ok -> TestResult(St.OK, txt)
            a.ok -> TestResult(St.WARN, "$txt\nInternet OK mais DNS en panne")
            else -> TestResult(St.FAIL, txt)
        }
    }

    private fun testGateway(): TestResult {
        val gw = gateway() ?: return TestResult(St.FAIL, "Aucune passerelle detectee\n(Wi-Fi deconnecte ?)")
        var ok = 0; var sum = 0L
        repeat(3) { val r = pingHost(gw); if (r.ok) { ok++; sum += r.ms } }
        val txt = "Passerelle : $gw\nPing : ${if (ok > 0) fmt(sum / ok) else "ECHEC"}  ($ok/3)"
        return TestResult(if (ok == 3) St.OK else if (ok > 0) St.WARN else St.FAIL, txt)
    }

    @Suppress("DEPRECATION")
    private fun testWifi(): TestResult {
        val info = wifi().connectionInfo
        if (info == null || info.networkId == -1) return TestResult(St.WARN, "Non connecte en Wi-Fi\n(donnees mobiles ou hors ligne)")
        val ip = info.ipAddress
        val ipS = "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        val rssi = info.rssi
        val txt = "IP : $ipS\nVitesse de liaison : ${info.linkSpeed} Mbps\nSignal : $rssi dBm"
        return TestResult(if (rssi > -70) St.OK else St.WARN, txt)
    }

    private fun testSpeed(): TestResult {
        try {
            val t0 = System.nanoTime()
            http("https://speed.cloudflare.com/__down?bytes=0", null)
            val lat = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val n = http("https://speed.cloudflare.com/__down?bytes=10000000", null)
            val dl = Math.round(n * 8.0 / 1e6 / ((System.nanoTime() - t1) / 1e9) * 100) / 100.0
            val t2 = System.nanoTime()
            http("https://speed.cloudflare.com/__up", ByteArray(5_000_000))
            val ul = Math.round(5_000_000 * 8.0 / 1e6 / ((System.nanoTime() - t2) / 1e9) * 100) / 100.0
            return TestResult(St.OK, "Download : $dl Mbps\nUpload : $ul Mbps\nLatence : $lat ms")
        } catch (e: Exception) {
            return TestResult(St.FAIL, "Echec :\n${e.message}")
        }
    }

    private fun http(url: String, post: ByteArray?): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 NetTester")
        if (post != null) { c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(post.size); c.outputStream.use { it.write(post) } }
        var total = 0L
        c.inputStream.use { s -> val buf = ByteArray(65536); while (true) { val r = s.read(buf); if (r < 0) break; total += r } }
        c.disconnect()
        return total
    }

    private fun testAll() {
        btnAll.isEnabled = false
        thread {
            for (c in cards) {
                val latch = java.util.concurrent.CountDownLatch(1)
                runOnUiThread { c.run(done = { latch.countDown() }) }
                latch.await()
            }
            runOnUiThread { btnAll.isEnabled = true }
        }
    }

    private fun resetAll() {
        cards.forEach { if (!it.running) it.reset() }
        for (t in targets) { t.sent = 0; t.lost = 0; t.sum = 0; t.max = 0; t.min = Long.MAX_VALUE; t.down = false
            t.head.text = "\u25CF ${t.name}    ..."; t.info.text = t.host; t.row.background = box(CARD) }
        cuts = 0; lastRestore = ""; refreshBanner()
    }

    // ---------- Ping continu ----------
    private fun toggleLive() {
        liveOn = !liveOn
        if (liveOn) { liveState.text = "\u25CF En cours"; liveState.setTextColor(GREEN); btnLive.text = "Pause"; startLoop() }
        else { liveState.text = "\u25CF En pause"; liveState.setTextColor(ORANGE); btnLive.text = "Reprendre" }
    }

    private fun startLoop() {
        if (loopActive) return
        loopActive = true
        thread {
            val ex = Executors.newFixedThreadPool(targets.size)
            try {
                while (liveOn) {
                    val t0 = System.currentTimeMillis()
                    val futures = targets.map { t -> ex.submit(Callable { pingHost(t.host) }) }
                    val res = futures.map { it.get() }
                    runOnUiThread { for (i in targets.indices) update(targets[i], res[i]); refreshBanner() }
                    val wait = 1000 - (System.currentTimeMillis() - t0)
                    if (wait > 0) Thread.sleep(wait)
                }
            } finally { ex.shutdown(); loopActive = false }
        }
    }

    private fun update(t: Target, r: PingRes) {
        t.sent++
        if (r.ip != null) t.ip = r.ip
        if (r.ok) {
            t.sum += r.ms; if (r.ms < t.min) t.min = r.ms; if (r.ms > t.max) t.max = r.ms
            if (t.down) { t.down = false; lastRestore = "${t.name} retabli a ${now()}" }
            t.row.background = box(CARD)
        } else {
            t.lost++
            if (!t.down) { t.down = true; cuts++ }
            t.row.background = box(ROW_BAD)
        }
        val okN = t.sent - t.lost
        val avg = if (okN > 0) "${t.sum / okN} ms" else "-"
        val loss = if (t.sent > 0) "${t.lost * 100 / t.sent} %" else "0 %"
        t.head.text = "\u25CF ${t.name}    ${fmt(r.ms)}"
        t.head.setTextColor(if (r.ok) TXT else RED)
        t.info.text = "${t.ip}  |  moy $avg  |  min ${if (t.min == Long.MAX_VALUE) "-" else "${t.min} ms"}  max ${t.max} ms  |  perte $loss"
    }

    private fun refreshBanner() {
        val down = targets.filter { it.down }
        if (down.isNotEmpty()) {
            banner.text = "Coupure : " + down.joinToString(", ") { it.name }; banner.setTextColor(RED)
        } else {
            banner.text = if (cuts == 0) "Aucune coupure detectee" else "Coupures : $cuts  |  $lastRestore"
            banner.setTextColor(if (cuts == 0) GREEN else ORANGE)
        }
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
