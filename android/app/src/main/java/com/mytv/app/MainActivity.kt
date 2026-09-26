package com.mytv.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: Repository
    private lateinit var adapter: ChannelAdapter
    private lateinit var chips: ChipGroup
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout

    private var all: List<Channel> = emptyList()
    private var selected: String = FILTER_ALL
    private var query: String = ""
    private var busy: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        repo = Repository(applicationContext)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        chips = findViewById(R.id.chips)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)

        adapter = ChannelAdapter(
            onClick = ::play,
            onToggleFavorite = { ch ->
                val added = prefs.toggleFavorite(ch.url)
                toast(if (added) "أضيفت للمفضلة" else "حُذفت من المفضلة")
                render()
            },
        )
        val list = findViewById<RecyclerView>(R.id.list)
        // One column on phones, more on tablets / TVs.
        val columns = (resources.configuration.screenWidthDp / 360).coerceIn(1, 4)
        list.layoutManager = GridLayoutManager(this, columns)
        list.adapter = adapter

        swipe.setOnRefreshListener { refresh() }

        selected = savedInstanceState?.getString("selected") ?: FILTER_ALL
        all = repo.loadCache()
        render()

        val stale = System.currentTimeMillis() - prefs.lastRefresh > TimeUnit.HOURS.toMillis(12)
        if (all.isEmpty() || stale) refresh()
    }

    override fun onResume() {
        super.onResume()
        // Recents / favorites may have changed in the player.
        if (::adapter.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selected)
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_hide_dead).isChecked = prefs.hideDead
        val search = menu.findItem(R.id.action_search).actionView as SearchView
        search.queryHint = getString(R.string.search)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty().trim()
                render()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refresh()
            R.id.action_check -> checkChannels()
            R.id.action_hide_dead -> {
                prefs.hideDead = !prefs.hideDead
                item.isChecked = prefs.hideDead
                render()
            }
            R.id.action_sources -> showSources()
            R.id.action_adult -> showAdult()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ data

    private fun activeSources(): List<Source> =
        // +18 sources are the user's own; they load only while the section is enabled.
        prefs.sources.filter { !it.adult || prefs.adultEnabled }

    /** Restarts if something is already running, so a new request (e.g. enabling +18) is never dropped. */
    private fun refresh(onDone: (() -> Unit)? = null) {
        busy?.cancel()
        busy = lifecycleScope.launch {
            setBusy(true, "جارٍ تحميل القوائم…")
            val result = repo.refresh(activeSources())
            if (result.channels.isNotEmpty()) {
                all = result.channels
                prefs.lastRefresh = System.currentTimeMillis()
                // Forget check results for channels that no longer exist.
                val urls = all.mapTo(HashSet()) { it.url }
                prefs.dead = prefs.dead.filterTo(HashSet()) { it in urls }
            }
            setBusy(false)
            if (result.errors.isNotEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تعذّر تحميل بعض المصادر")
                    .setMessage(result.errors.joinToString("\n\n"))
                    .setPositiveButton("حسناً", null)
                    .show()
            }
            render()
            onDone?.invoke()
        }
    }

    /** Called after the adult section is enabled or unlocked: jump to it, or ask for a source. */
    private fun showAdultResult() {
        val count = all.count { it.adult }
        val hasSources = prefs.sources.any { it.adult }
        if (!hasSources) {
            AlertDialog.Builder(this)
                .setTitle(R.string.adult)
                .setMessage(
                    "القسم مفعّل ومحمي بالرقم السري، لكن لا توجد فيه مصادر بعد.\n\n" +
                        "لا يوجد مصدر مجاني عام موثوق لقنوات الكبار: مشروع iptv-org توقف عن نشرها منذ 2024. " +
                        "أضف رابط قائمة M3U تملكه (مثلاً من اشتراك IPTV)، وسيبقى مخفياً خلف الرقم السري."
                )
                .setPositiveButton("إضافة مصدر للكبار") { _, _ -> addSource(adult = true) }
                .setNegativeButton("لاحقاً", null)
                .show()
        } else if (count > 0) {
            selected = Groups.ADULT
            render()
            toast("قسم الكبار: $count قناة")
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.adult)
                .setMessage(
                    "لم يتم تحميل أي قناة من مصادر الكبار التي أضفتها.\n\n" +
                        "تأكد من صحة الرابط (من «المصادر»)، أو أن الاشتراك ما زال فعّالاً."
                )
                .setPositiveButton("إضافة مصدر للكبار") { _, _ -> addSource(adult = true) }
                .setNeutralButton("إعادة المحاولة") { _, _ -> refresh(::showAdultResult) }
                .setNegativeButton("إغلاق", null)
                .show()
        }
    }

    private fun checkChannels() {
        if (busy?.isActive == true) {
            toast("انتظر حتى ينتهي التحميل الحالي")
            return
        }
        val targets = all.filter { !it.adult || Prefs.adultUnlocked }
        if (targets.isEmpty()) return
        busy = lifecycleScope.launch {
            setBusy(true, "جارٍ فحص ${targets.size} قناة…")
            val dead = repo.check(targets) { done, total ->
                runOnUiThread { status.text = "فحص القنوات: $done / $total" }
            }
            // Keep earlier results for channels that weren't part of this check (locked adult ones).
            val checked = targets.mapTo(HashSet()) { it.url }
            prefs.dead = prefs.dead.filterTo(HashSet()) { it !in checked } + dead
            setBusy(false)
            toast("تعمل ${targets.size - dead.size} قناة، ولا تستجيب ${dead.size}")
            render()
        }
    }

    private fun setBusy(on: Boolean, message: String? = null) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) swipe.isRefreshing = false
        message?.let { status.text = it }
    }

    // ------------------------------------------------------------------ rendering

    private fun visibleChannels(): List<Channel> {
        val dead = prefs.dead
        return all.filter { ch ->
            (!ch.adult || Prefs.adultUnlocked) && !(prefs.hideDead && ch.url in dead)
        }
    }

    private fun render() {
        val visible = visibleChannels()
        buildChips(visible)

        val byUrl = visible.associateBy { it.url }
        var list = when (selected) {
            FILTER_ALL -> visible.filter { !it.adult }
            FILTER_FAVORITES -> prefs.favorites.mapNotNull { byUrl[it] }.sortedBy { it.name.lowercase() }
            FILTER_RECENT -> prefs.recents.mapNotNull { byUrl[it] }
            else -> visible.filter { it.group == selected }
        }
        if (query.isNotEmpty()) list = list.filter { it.name.contains(query, ignoreCase = true) }

        adapter.submit(list, prefs.favorites, prefs.dead)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        if (progress.visibility != View.VISIBLE) {
            val hidden = all.count { (!it.adult || Prefs.adultUnlocked) } - visible.size
            val updated = if (prefs.lastRefresh > 0)
                " • آخر تحديث " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(prefs.lastRefresh)) else ""
            status.text = "${list.size} قناة" +
                (if (hidden > 0) " • $hidden معطلة مخفية" else "") + updated
        }
    }

    private fun buildChips(visible: List<Channel>) {
        val groups = visible.map { it.group }.distinct().sortedWith(Groups.comparator)
        val filters = listOf(
            FILTER_ALL to getString(R.string.chip_all),
            FILTER_FAVORITES to getString(R.string.chip_favorites),
            FILTER_RECENT to getString(R.string.chip_recent),
        ) + groups.map { g -> g to "$g (${visible.count { it.group == g }})" }

        if (filters.none { it.first == selected }) selected = FILTER_ALL

        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        for ((key, label) in filters) {
            val chip = (layoutInflater.inflate(R.layout.chip_filter, chips, false) as Chip).apply {
                id = View.generateViewId()
                text = label
                tag = key
                isCheckable = true
                isChecked = key == selected
            }
            chips.addView(chip)
        }
        chips.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            selected = chip.tag as String
            // Rebuild after the ChipGroup finishes dispatching this change.
            group.post { render() }
        }
    }

    private fun play(position: Int) {
        PlayerSession.channels = adapter.items
        PlayerSession.index = position
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------------ sources

    private fun showSources() {
        val sources = prefs.sources.filter { !it.adult || Prefs.adultUnlocked }
        val labels = sources.map { (if (it.adult) "🔞 " else "") + "${it.name}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("المصادر (اضغط على مصدر لحذفه)")
            .setItems(labels) { _, which -> confirmRemoveSource(sources[which]) }
            .setPositiveButton("إضافة مصدر") { _, _ -> addSource() }
            .setNeutralButton("استعادة الافتراضي") { _, _ ->
                // Keep the user's own +18 sources; they are managed behind the PIN.
                prefs.sources = Source.DEFAULTS + prefs.sources.filter { it.adult }
                refresh()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun confirmRemoveSource(src: Source) {
        AlertDialog.Builder(this)
            .setMessage("حذف المصدر «${src.name}»؟")
            .setPositiveButton("حذف") { _, _ ->
                prefs.sources = prefs.sources.filter { it != src }
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun addSource(adult: Boolean = false) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply { hint = "الاسم" }
        val url = EditText(this).apply {
            hint = "رابط M3U (https://…)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val adultBox = CheckBox(this).apply {
            text = "قائمة للكبار +18 (محمية بالرقم السري)"
            isChecked = adult
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(url)
            // Only offered once a PIN exists and the section is unlocked.
            if (Prefs.adultUnlocked) addView(adultBox)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة مصدر")
            .setView(box)
            .setPositiveButton("إضافة") { _, _ ->
                val u = url.text.toString().trim()
                if (!u.startsWith("http")) {
                    toast("الرابط غير صحيح")
                    return@setPositiveButton
                }
                val n = name.text.toString().trim().ifEmpty { u.substringAfterLast('/') }
                val isAdult = Prefs.adultUnlocked && adultBox.isChecked
                prefs.sources = prefs.sources + Source(n, u, adult = isAdult)
                refresh(if (isAdult) ::showAdultResult else null)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ------------------------------------------------------------------ adult section

    private fun showAdult() {
        when {
            !prefs.hasPin -> askPin("اختر رقماً سرياً (4 أرقام على الأقل) لقسم الكبار") { pin ->
                prefs.setPin(pin)
                prefs.adultEnabled = true
                Prefs.adultUnlocked = true
                toast("تم تفعيل قسم الكبار")
                showAdultResult()
            }
            !Prefs.adultUnlocked -> askPin("أدخل الرقم السري") { pin ->
                if (prefs.checkPin(pin)) {
                    Prefs.adultUnlocked = true
                    prefs.adultEnabled = true
                    if (prefs.sources.any { it.adult } && all.none { it.adult }) {
                        toast("جارٍ تحميل قنوات الكبار…")
                        refresh(::showAdultResult)
                    } else {
                        showAdultResult()
                    }
                } else {
                    toast("رقم سري خاطئ")
                }
            }
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.adult)
                .setItems(arrayOf("قفل القسم الآن", "تعطيل القسم وحذف الرقم السري")) { _, which ->
                    Prefs.adultUnlocked = false
                    if (which == 1) {
                        prefs.adultEnabled = false
                        prefs.clearPin()
                        all = all.filter { !it.adult }
                        refresh()
                    }
                    if (selected == Groups.ADULT) selected = FILTER_ALL
                    render()
                }
                .show()
        }
    }

    private fun askPin(title: String, onPin: (String) -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("تأكيد") { _, _ ->
                val pin = input.text.toString()
                if (pin.length < 4) toast("الرقم السري 4 أرقام على الأقل") else onPin(pin)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val FILTER_ALL = "\u0000all"
        private const val FILTER_FAVORITES = "\u0000fav"
        private const val FILTER_RECENT = "\u0000recent"
    }
}
