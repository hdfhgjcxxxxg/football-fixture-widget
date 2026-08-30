package com.example.footballfixturewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

abstract class BaseWidgetConfigActivity : AppCompatActivity() {
    abstract val kind: String
    abstract val heading: String
    abstract val entityLabel: String

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var root: LinearLayout
    private lateinit var choicesContainer: LinearLayout
    private lateinit var searchResultsContainer: LinearLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var progress: ProgressBar
    private lateinit var countdownDetailSwitch: MaterialSwitch
    private val selectedWorking = LinkedHashSet<Int>()
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(Activity.RESULT_CANCELED)
        selectedWorking += WidgetSelectionStore.getSelectedIds(this, widgetId, kind)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::choicesContainer.isInitialized) renderFavorites()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurface))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = heading
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        root.addView(TextView(this).apply {
            text = "このウィジェットだけに表示する${entityLabel}を選びます。検索から直接追加できます。"
            textSize = 14f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, dp(16))
        })

        addCountdownSetting()
        addSearchArea()

        root.addView(TextView(this).apply {
            text = "お気に入り${entityLabel}"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        })
        choicesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(choicesContainer)

        root.addView(MaterialButton(this).apply {
            text = "この内容でウィジェットを保存"
            textSize = 16f
            setOnClickListener { saveAndFinish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(14) })

        renderFavorites()
    }

    private fun addCountdownSetting() {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(TextView(this).apply {
            text = "カウントダウンの分・秒"; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textBox.addView(TextView(this).apply {
            text = "ON: 118:29:26 / OFF: 118h（試合中は LIVE / 試合分表示）"; textSize = 12f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countdownDetailSwitch = MaterialSwitch(this).apply {
            isChecked = WidgetSelectionStore.showDetailedCountdown(this@BaseWidgetConfigActivity, widgetId, kind)
        }
        row.addView(countdownDetailSwitch)
        card.addView(row)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addSearchArea() {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat(); cardElevation = 0f
            setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(TextView(this).apply {
            text = "${entityLabel}を検索してこのウィジェットに追加"
            textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        searchInput = TextInputEditText(this).apply {
            hint = when (kind) {
                WidgetKinds.PLAYER -> "例: Bukayo Saka"
                WidgetKinds.LEAGUE -> "例: Premier League"
                else -> "例: Arsenal"
            }
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
            }
        }
        box.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttonRow.addView(MaterialButton(this).apply {
            text = "検索"
            setOnClickListener { doSearch() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        buttonRow.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        box.addView(buttonRow)
        searchResultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(searchResultsContainer)
        card.addView(box)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    private fun doSearch() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (q.length < 2) { toast("2文字以上入力してください"); return }
        progress.visibility = View.VISIBLE
        searchResultsContainer.removeAllViews()
        Thread {
            val result = runCatching {
                when (kind) {
                    WidgetKinds.PLAYER -> FavoriteEntityRepository.searchPlayers(q).map { SearchHit(it.id, it.name, listOf(it.teamName, it.position, it.sourceLabel).filter(String::isNotBlank).joinToString(" • "), player = it) }
                    WidgetKinds.LEAGUE -> FixtureRepository.fetchLeagueDirectory().filter { it.name.contains(q, true) || it.country.contains(q, true) }.take(60).map { SearchHit(it.id, it.name, it.country, league = it) }
                    else -> FixtureRepository.searchTeams(q).map { SearchHit(it.id, it.name, listOf(it.country, it.sourceLabel).filter(String::isNotBlank).joinToString(" • "), team = it) }
                }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { hits -> renderSearchResults(hits) }
                    .onFailure { toast("検索失敗: ${it.message ?: "通信エラー"}") }
            }
        }.start()
    }

    private data class SearchHit(
        val id: Int,
        val name: String,
        val sub: String,
        val team: FavoriteTeam? = null,
        val player: FavoritePlayer? = null,
        val league: LeagueInfo? = null
    )

    private fun renderSearchResults(hits: List<SearchHit>) {
        searchResultsContainer.removeAllViews()
        if (hits.isEmpty()) {
            searchResultsContainer.addView(TextView(this).apply { text = "見つかりませんでした"; setPadding(0, dp(12), 0, 0) })
            return
        }
        hits.take(50).forEach { hit ->
            val card = simpleCard(hit.name, hit.sub)
            val row = card.getChildAt(0) as LinearLayout
            val button = MaterialButton(this).apply {
                text = if (selectedWorking.contains(hit.id)) "✓ 選択済み" else "＋ 追加して選択"
                setOnClickListener {
                    val actualId = when (kind) {
                        WidgetKinds.PLAYER -> {
                            hit.player?.let { FavoriteEntityRepository.addFavoritePlayer(this@BaseWidgetConfigActivity, it) }
                            FavoriteEntityRepository.getFavoritePlayers(this@BaseWidgetConfigActivity)
                                .firstOrNull { it.name.equals(hit.name, true) && (hit.player?.teamName.isNullOrBlank() || it.teamName.equals(hit.player?.teamName, true)) }?.id
                        }
                        WidgetKinds.LEAGUE -> {
                            hit.league?.let { FavoriteEntityRepository.addFavoriteLeague(this@BaseWidgetConfigActivity, it) }
                            FavoriteEntityRepository.getFavoriteLeagues(this@BaseWidgetConfigActivity).firstOrNull { it.name.equals(hit.name, true) }?.id
                        }
                        else -> {
                            hit.team?.let { FixtureRepository.addFavoriteTeam(this@BaseWidgetConfigActivity, it) }
                            FixtureRepository.getFavoriteTeams(this@BaseWidgetConfigActivity).firstOrNull { FixtureRepository.normalizeTeamName(it.name) == FixtureRepository.normalizeTeamName(hit.name) }?.id
                        }
                    } ?: hit.id
                    selectedWorking += actualId
                    renderFavorites()
                    renderSearchResults(hits)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            searchResultsContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun renderFavorites() {
        if (!::choicesContainer.isInitialized) return
        choicesContainer.removeAllViews()
        val entities = entities()
        if (entities.isEmpty()) {
            choicesContainer.addView(TextView(this).apply {
                text = "まだありません。上の検索から${entityLabel}を追加できます。"
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            return
        }
        entities.forEach { entity ->
            val card = simpleCard(entity.name, entity.sub)
            val row = card.getChildAt(0) as LinearLayout
            val check = MaterialCheckBox(this).apply {
                isChecked = selectedWorking.contains(entity.id)
                setOnCheckedChangeListener { _, checked -> if (checked) selectedWorking += entity.id else selectedWorking -= entity.id }
            }
            row.addView(check)
            choicesContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun simpleCard(title: String, sub: String): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(TextView(this).apply {
            text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        if (sub.isNotBlank()) textBox.addView(TextView(this).apply {
            text = sub; textSize = 12f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun saveAndFinish() {
        if (selectedWorking.isEmpty()) { toast("表示する${entityLabel}を1つ以上選んでください"); return }
        WidgetSelectionStore.saveSelectedIds(this, widgetId, kind, selectedWorking)
        WidgetSelectionStore.saveDetailedCountdown(this, widgetId, kind, countdownDetailSwitch.isChecked)
        when (kind) {
            WidgetKinds.PLAYER -> { PlayerWidgetProvider.renderAll(this, "更新中…"); sendBroadcast(Intent(this, PlayerWidgetProvider::class.java).apply { action = PlayerWidgetProvider.ACTION_REFRESH }) }
            WidgetKinds.LEAGUE -> { LeagueWidgetProvider.renderAll(this, "更新中…"); sendBroadcast(Intent(this, LeagueWidgetProvider::class.java).apply { action = LeagueWidgetProvider.ACTION_REFRESH }) }
            else -> { FixtureWidgetProvider.renderAll(this, "更新中…"); sendBroadcast(Intent(this, FixtureWidgetProvider::class.java).apply { action = FixtureWidgetProvider.ACTION_REFRESH }) }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private data class Choice(val id: Int, val name: String, val sub: String)

    private fun entities(): List<Choice> = when (kind) {
        WidgetKinds.PLAYER -> FavoriteEntityRepository.getFavoritePlayers(this).map { Choice(it.id, it.name, listOf(it.teamName, it.position).filter(String::isNotBlank).joinToString(" • ")) }
        WidgetKinds.LEAGUE -> FavoriteEntityRepository.getFavoriteLeagues(this).map { Choice(it.id, it.name, it.country) }
        else -> FixtureRepository.getFavoriteTeams(this).map { Choice(it.id, it.name, listOf(it.country, it.sourceLabel).filter(String::isNotBlank).joinToString(" • ")) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun resolveThemeColor(attr: Int): Int {
        val out = android.util.TypedValue(); theme.resolveAttribute(attr, out, true)
        return if (out.resourceId != 0) getColor(out.resourceId) else out.data
    }
}

class TeamWidgetConfigActivity : BaseWidgetConfigActivity() {
    override val kind = WidgetKinds.TEAM; override val heading = "チームウィジェット"; override val entityLabel = "チーム"
}
class PlayerWidgetConfigActivity : BaseWidgetConfigActivity() {
    override val kind = WidgetKinds.PLAYER; override val heading = "選手ウィジェット"; override val entityLabel = "選手"
}
class LeagueWidgetConfigActivity : BaseWidgetConfigActivity() {
    override val kind = WidgetKinds.LEAGUE; override val heading = "リーグウィジェット"; override val entityLabel = "リーグ"
}
