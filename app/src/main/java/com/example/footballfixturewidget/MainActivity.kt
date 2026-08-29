package com.example.footballfixturewidget

import android.content.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var apiBadge: TextView
    private lateinit var apiStatus: TextView
    private lateinit var testConnectionButton: MaterialButton
    private lateinit var favoritesCount: TextView
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var leagueDropdown: AutoCompleteTextView
    private lateinit var loadLeagueTeamsButton: MaterialButton
    private lateinit var teamSearchInput: TextInputEditText
    private lateinit var searchTeamButton: MaterialButton
    private lateinit var tapTargetDropdown: AutoCompleteTextView
    private lateinit var colorPreview: View
    private lateinit var colorPresets: LinearLayout
    private lateinit var colorHexInput: TextInputEditText
    private lateinit var applyHexButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var saveButton: MaterialButton

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int) = (value * density).toInt()

    private var selectedColor = FixtureRepository.DEFAULT_WIDGET_COLOR
    private var leagueOptions: List<LeagueInfo> = emptyList()
    private var selectedLeague: LeagueInfo? = null

    private val tapLabels = listOf(
        "FotMobのその試合を開く",
        "SofaScoreのその試合を開く",
        "OneFootballを開く",
        "Flashscoreを開く",
        "LiveScoreを開く",
        "365Scoresを開く",
        "何もしない",
        "このアプリの設定を開く"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        bindViews()
        selectedColor = FixtureRepository.getWidgetColor(this)
        setupTapTarget()
        setupColorControls()
        setupActions()
        refreshFavoritesUi()
        migrateOldFavoritesAndLoadLeagues()
    }

    private fun bindViews() {
        apiBadge = findViewById(R.id.api_badge)
        apiStatus = findViewById(R.id.api_status)
        testConnectionButton = findViewById(R.id.test_connection_button)
        favoritesCount = findViewById(R.id.favorites_count)
        favoritesContainer = findViewById(R.id.favorites_container)
        leagueDropdown = findViewById(R.id.league_dropdown)
        loadLeagueTeamsButton = findViewById(R.id.load_league_teams_button)
        teamSearchInput = findViewById(R.id.team_search_input)
        searchTeamButton = findViewById(R.id.search_team_button)
        tapTargetDropdown = findViewById(R.id.tap_target_dropdown)
        colorPreview = findViewById(R.id.color_preview)
        colorPresets = findViewById(R.id.color_presets)
        colorHexInput = findViewById(R.id.color_hex_input)
        applyHexButton = findViewById(R.id.apply_hex_button)
        progress = findViewById(R.id.progress)
        saveButton = findViewById(R.id.save_button)
    }

    private fun setupActions() {
        testConnectionButton.setOnClickListener { loadLeagueDirectory(showSuccessToast = true) }

        leagueDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position)?.toString().orEmpty()
            selectedLeague = leagueOptions.firstOrNull { it.label == label }
        }

        loadLeagueTeamsButton.setOnClickListener {
            val league = selectedLeague
            if (league == null) {
                toast("リーグ・大会を選んでください")
            } else {
                loadTeamsForLeague(league)
            }
        }

        searchTeamButton.setOnClickListener { searchTeams() }
        teamSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchTeams()
                true
            } else false
        }

        applyHexButton.setOnClickListener {
            val parsed = FixtureRepository.parseColorOrNull(colorHexInput.text?.toString().orEmpty())
            if (parsed == null) toast("HEXカラーを正しく入力してください") else setSelectedColor(parsed)
        }

        saveButton.setOnClickListener { saveAndRefresh() }
    }

    private fun setupTapTarget() {
        tapTargetDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tapLabels)
        )
        tapTargetDropdown.setText(labelForTapTarget(FixtureRepository.getTapTarget(this)), false)
    }

    private fun setupColorControls() {
        setSelectedColor(selectedColor)
        val presets = listOf(
            0xFF15171C.toInt(),
            0xFF0F172A.toInt(),
            0xFF1D4ED8.toInt(),
            0xFF6D28D9.toInt(),
            0xFFB91C1C.toInt(),
            0xFF047857.toInt(),
            0xFFF1F5F9.toInt(),
            0xFFFFFFFF.toInt()
        )
        colorPresets.removeAllViews()
        presets.forEachIndexed { index, color ->
            val button = MaterialButton(this).apply {
                text = ""
                contentDescription = "カラープリセット ${index + 1}"
                setBackgroundTintList(ColorStateList.valueOf(color))
                setCornerRadius(dp(26))
                setInsetTop(0)
                setInsetBottom(0)
                setStrokeWidth(dp(1))
                setStrokeColor(ColorStateList.valueOf(if (Color.luminance(color) > 0.7) 0x33000000 else 0x44FFFFFF))
                setOnClickListener { setSelectedColor(color) }
            }
            colorPresets.addView(button, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(10)
            })
        }
    }

    private fun setSelectedColor(color: Int) {
        selectedColor = color
        colorPreview.backgroundTintList = ColorStateList.valueOf(color)
        colorHexInput.setText(FixtureRepository.colorToHex(color))
    }

    private fun migrateOldFavoritesAndLoadLeagues() {
        setBusy(true)
        Thread {
            val migrated = runCatching { FixtureRepository.migrateFavoritesToFotMobIfNeeded(this) }.getOrDefault(false)
            runOnUiThread {
                if (migrated) {
                    refreshFavoritesUi()
                    toast("お気に入りを新しい自動データ方式へ移行しました")
                }
            }
            val result = runCatching { FixtureRepository.fetchLeagueDirectory() }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { applyLeagueDirectory(it, false) }
                    .onFailure {
                        apiBadge.text = "再試行"
                        apiStatus.text = "自動接続できませんでした • ${it.message ?: "通信エラー"}"
                    }
            }
        }.start()
    }

    private fun loadLeagueDirectory(showSuccessToast: Boolean) {
        setBusy(true)
        apiStatus.text = "FotMobへ接続中…"
        Thread {
            val result = runCatching { FixtureRepository.fetchLeagueDirectory() }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { applyLeagueDirectory(it, showSuccessToast) }
                    .onFailure {
                        apiBadge.text = "エラー"
                        apiStatus.text = "接続失敗 • ${it.message ?: "通信エラー"}"
                        toast("データ接続に失敗しました")
                    }
            }
        }.start()
    }

    private fun applyLeagueDirectory(leagues: List<LeagueInfo>, showToast: Boolean) {
        leagueOptions = leagues
        selectedLeague = null
        leagueDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, leagues.map { it.label })
        )
        leagueDropdown.setText("", false)
        apiBadge.text = "接続済み"
        apiStatus.text = "APIキー不要 • ${leagues.size}リーグ/大会を自動取得"
        if (showToast) toast("接続OK：${leagues.size}大会を取得しました")
    }

    private fun loadTeamsForLeague(league: LeagueInfo) {
        setBusy(true)
        Thread {
            val result = runCatching { FixtureRepository.fetchTeamsForLeague(league.id) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { teams ->
                    if (teams.isEmpty()) toast("この大会からチーム一覧を取得できませんでした")
                    else showTeamPicker(league.name, teams)
                }.onFailure { toast("チーム取得失敗: ${it.message ?: "通信エラー"}") }
            }
        }.start()
    }

    private fun searchTeams() {
        val query = teamSearchInput.text?.toString()?.trim().orEmpty()
        if (query.length < 2) {
            toast("チーム名を2文字以上入力してください")
            return
        }
        setBusy(true)
        Thread {
            val result = runCatching { FixtureRepository.searchTeams(query) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { teams ->
                    if (teams.isEmpty()) toast("チームが見つかりませんでした")
                    else showTeamPicker("「$query」の検索結果", teams)
                }.onFailure { toast("検索失敗: ${it.message ?: "通信エラー"}") }
            }
        }.start()
    }

    private fun showTeamPicker(title: String, candidates: List<FavoriteTeam>) {
        val current = FixtureRepository.getFavoriteTeams(this)
        val currentIds = current.map { it.id }.toMutableSet()
        val labels = candidates.map { it.name }.toTypedArray()
        val checked = BooleanArray(candidates.size) { candidates[it].id in currentIds }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("お気に入りは最大${FixtureRepository.MAX_FAVORITES}チーム。複数選択できます。")
            .setMultiChoiceItems(labels, checked) { dialogInterface, which, isChecked ->
                val team = candidates[which]
                if (isChecked) {
                    val candidateTotal = currentIds.size + if (team.id in currentIds) 0 else 1
                    if (candidateTotal > FixtureRepository.MAX_FAVORITES) {
                        (dialogInterface as? androidx.appcompat.app.AlertDialog)?.listView?.setItemChecked(which, false)
                        toast("最大${FixtureRepository.MAX_FAVORITES}チームまでです")
                    } else {
                        currentIds += team.id
                    }
                } else {
                    currentIds -= team.id
                }
            }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("反映", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val existingOutside = current.filter { old -> candidates.none { it.id == old.id } }
                val selectedInside = candidates.filter { it.id in currentIds }
                val combined = (existingOutside + selectedInside).distinctBy { it.id }.take(FixtureRepository.MAX_FAVORITES)
                FixtureRepository.saveFavoriteTeams(this, combined)
                refreshFavoritesUi()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshFavoritesUi() {
        val teams = FixtureRepository.getFavoriteTeams(this)
        favoritesCount.text = "${teams.size} / ${FixtureRepository.MAX_FAVORITES}  •  先頭が初期チーム"
        favoritesContainer.removeAllViews()

        if (teams.isEmpty()) {
            favoritesContainer.addView(TextView(this).apply {
                text = "まだチームがありません。リーグまたは検索から追加してください。"
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                textSize = 14f
                setPadding(dp(4), dp(10), dp(4), dp(6))
            })
            return
        }

        teams.forEachIndexed { index, team ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
            }

            val avatar = TextView(this).apply {
                text = team.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
                setBackgroundResource(R.drawable.team_avatar_background)
                backgroundTintList = ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer))
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))

            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            nameBox.addView(TextView(this).apply {
                text = team.name
                textSize = 16f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            nameBox.addView(TextView(this).apply {
                text = if (index == 0) "初期チーム • FotMob ID ${team.id}" else "FotMob ID ${team.id}"
                textSize = 12f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            row.addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (index != 0) {
                row.addView(MaterialButton(this).apply {
                    text = "先頭"
                    textSize = 12f
                    setOnClickListener {
                        FixtureRepository.moveFavoriteToTop(this@MainActivity, team.id)
                        refreshFavoritesUi()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(4) })
            }

            row.addView(MaterialButton(this).apply {
                text = "削除"
                textSize = 12f
                setOnClickListener {
                    FixtureRepository.removeFavoriteTeam(this@MainActivity, team.id)
                    refreshFavoritesUi()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))

            card.addView(row)
            favoritesContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun saveAndRefresh() {
        val target = tapTargetForLabel(tapTargetDropdown.text?.toString().orEmpty())
        FixtureRepository.saveTapTarget(this, target)
        FixtureRepository.saveWidgetColor(this, selectedColor)
        FixtureWidgetProvider.renderAll(this, statusOverride = "更新中…")
        sendBroadcast(Intent(this, FixtureWidgetProvider::class.java).apply {
            action = FixtureWidgetProvider.ACTION_REFRESH
        })
        toast("保存しました。試合日程を更新しています")
    }

    private fun labelForTapTarget(value: String): String = when (value) {
        FixtureRepository.TAP_FOTMOB -> tapLabels[0]
        FixtureRepository.TAP_SOFASCORE -> tapLabels[1]
        FixtureRepository.TAP_ONEFOOTBALL -> tapLabels[2]
        FixtureRepository.TAP_FLASHSCORE -> tapLabels[3]
        FixtureRepository.TAP_LIVESCORE -> tapLabels[4]
        FixtureRepository.TAP_365SCORES -> tapLabels[5]
        FixtureRepository.TAP_NONE -> tapLabels[6]
        FixtureRepository.TAP_SETTINGS -> tapLabels[7]
        else -> tapLabels[0]
    }

    private fun tapTargetForLabel(label: String): String = when (label) {
        tapLabels[1] -> FixtureRepository.TAP_SOFASCORE
        tapLabels[2] -> FixtureRepository.TAP_ONEFOOTBALL
        tapLabels[3] -> FixtureRepository.TAP_FLASHSCORE
        tapLabels[4] -> FixtureRepository.TAP_LIVESCORE
        tapLabels[5] -> FixtureRepository.TAP_365SCORES
        tapLabels[6] -> FixtureRepository.TAP_NONE
        tapLabels[7] -> FixtureRepository.TAP_SETTINGS
        else -> FixtureRepository.TAP_FOTMOB
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        testConnectionButton.isEnabled = !busy
        loadLeagueTeamsButton.isEnabled = !busy
        searchTeamButton.isEnabled = !busy
        saveButton.isEnabled = !busy
    }

    private fun resolveThemeColor(attr: Int): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(attr, out, true)
        return if (out.resourceId != 0) getColor(out.resourceId) else out.data
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
