package com.example.footballfixturewidget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var apiBadge: TextView
    private lateinit var apiStatus: TextView
    private lateinit var testConnectionButton: MaterialButton
    private lateinit var dataSourceDropdown: AutoCompleteTextView
    private lateinit var favoritesCount: TextView
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var popularTeamsContainer: LinearLayout
    private lateinit var leagueDropdown: AutoCompleteTextView
    private lateinit var loadLeagueTeamsButton: MaterialButton
    private lateinit var teamSearchInput: TextInputEditText
    private lateinit var searchTeamButton: MaterialButton
    private lateinit var playersCount: TextView
    private lateinit var playerSearchInput: TextInputEditText
    private lateinit var searchPlayerButton: MaterialButton
    private lateinit var favoritePlayersContainer: LinearLayout
    private lateinit var favoriteLeaguesCount: TextView
    private lateinit var favoriteLeagueDropdown: AutoCompleteTextView
    private lateinit var addFavoriteLeagueButton: MaterialButton
    private lateinit var favoriteLeaguesContainer: LinearLayout
    private lateinit var tapTargetDropdown: AutoCompleteTextView
    private lateinit var colorPreview: View
    private lateinit var colorPresets: LinearLayout
    private lateinit var colorHexInput: TextInputEditText
    private lateinit var applyHexButton: MaterialButton
    private lateinit var iconChoicesContainer: LinearLayout
    private lateinit var autoUpdateSwitch: MaterialSwitch
    private lateinit var updateStatus: TextView
    private lateinit var checkUpdateButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var saveButton: MaterialButton

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int) = (value * density).toInt()

    private var selectedColor = FixtureRepository.DEFAULT_WIDGET_COLOR
    private var leagueOptions: List<LeagueInfo> = emptyList()
    private var selectedLeague: LeagueInfo? = null
    private var selectedFavoriteLeague: LeagueInfo? = null

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

        // v11.10: keep launcher startup resilient. Network/API initialization is
        // deliberately deferred until the user taps "接続を確認" so a transport
        // problem can never crash the app during launch.
        runCatching { DynamicColors.applyToActivityIfAvailable(this) }
        runCatching { setContentView(R.layout.activity_main) }
            .onFailure {
                RuntimeCrashStore.record(this, "setContentView", it)
                showFatalStartupScreen("画面の初期化に失敗しました", it)
                return
            }

        try {
            bindViews()
            selectedColor = FixtureRepository.getWidgetColor(this)
            setupTapTarget()
            setupDataSource()
            setupColorControls()
            setupPopularTeams()
            setupIconChoices()
            setupUpdateControls()
            setupActions()
            refreshFavoritesUi()
            refreshPlayerFavoritesUi()
            refreshLeagueFavoritesUi()

            // No automatic SofaScore request here. This is the key safe-start change.
            apiBadge.text = "待機中"
            apiStatus.text = "取得元: ${DataSourceManager.label(this)} • 「接続を確認」で接続します"
            setBusy(false)
        } catch (t: Throwable) {
            RuntimeCrashStore.record(this, "MainActivity.onCreate", t)
            showFatalStartupScreen("起動処理でエラーが発生しました", t)
        }
    }

    private fun showFatalStartupScreen(title: String, error: Throwable) {
        val message = buildString {
            append(title)
            append("\n\n")
            append(error.javaClass.simpleName)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": " + it) }
            append("\n\n次回起動時にクラッシュレポート画面から共有できます。")
        }
        setContentView(TextView(this).apply {
            text = message
            textSize = 18f
            setPadding(dp(24), dp(48), dp(24), dp(24))
        })
    }

    private fun bindViews() {
        apiBadge = findViewById(R.id.api_badge)
        apiStatus = findViewById(R.id.api_status)
        testConnectionButton = findViewById(R.id.test_connection_button)
        dataSourceDropdown = findViewById(R.id.data_source_dropdown)
        favoritesCount = findViewById<TextView>(R.id.favorites_count).apply { visibility = View.GONE }
        favoritesContainer = findViewById(R.id.favorites_container)
        popularTeamsContainer = findViewById(R.id.popular_teams_container)
        leagueDropdown = findViewById(R.id.league_dropdown)
        loadLeagueTeamsButton = findViewById(R.id.load_league_teams_button)
        teamSearchInput = findViewById(R.id.team_search_input)
        searchTeamButton = findViewById(R.id.search_team_button)
        playersCount = findViewById<TextView>(R.id.players_count).apply { visibility = View.GONE }
        playerSearchInput = findViewById(R.id.player_search_input)
        searchPlayerButton = findViewById(R.id.search_player_button)
        favoritePlayersContainer = findViewById(R.id.favorite_players_container)
        favoriteLeaguesCount = findViewById<TextView>(R.id.favorite_leagues_count).apply { visibility = View.GONE }
        favoriteLeagueDropdown = findViewById(R.id.favorite_league_dropdown)
        addFavoriteLeagueButton = findViewById(R.id.add_favorite_league_button)
        favoriteLeaguesContainer = findViewById(R.id.favorite_leagues_container)
        tapTargetDropdown = findViewById(R.id.tap_target_dropdown)
        colorPreview = findViewById(R.id.color_preview)
        colorPresets = findViewById(R.id.color_presets)
        colorHexInput = findViewById(R.id.color_hex_input)
        applyHexButton = findViewById(R.id.apply_hex_button)
        iconChoicesContainer = findViewById(R.id.icon_choices_container)
        autoUpdateSwitch = findViewById(R.id.auto_update_switch)
        updateStatus = findViewById(R.id.update_status)
        checkUpdateButton = findViewById(R.id.check_update_button)
        progress = findViewById(R.id.progress)
        saveButton = findViewById(R.id.save_button)
    }

    private fun setupDataSource() {
        // AutoCompleteTextView normally filters its adapter using the currently displayed text.
        // That caused the source menu to show only the selected item ("両方（自動統合）").
        // Use an unfiltered adapter so all three choices are always visible.
        val choices = listOf(
            "結合" to DataSourceManager.AUTO_BOTH,
            "SofaScore" to DataSourceManager.SOFASCORE,
            "FotMob" to DataSourceManager.FOTMOB
        )
        val labels = choices.map { it.first }
        val modes = choices.map { it.second }
        dataSourceDropdown.threshold = 0
        dataSourceDropdown.setAdapter(createUnfilteredAdapter(labels))
        val currentMode = DataSourceManager.getMode(this)
        val index = modes.indexOf(currentMode).coerceAtLeast(0)
        dataSourceDropdown.setText(labels[index], false)
        dataSourceDropdown.setOnClickListener { dataSourceDropdown.showDropDown() }
        dataSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            val mode = modes.getOrElse(position) { DataSourceManager.AUTO_BOTH }
            DataSourceManager.setMode(this, mode)
            FixtureRepository.clearCache(this)
            selectedLeague = null
            selectedFavoriteLeague = null
            apiBadge.text = "待機中"
            apiStatus.text = "取得元: ${DataSourceManager.label(mode)} • 接続を確認してください"
            leagueOptions = emptyList()
            leagueDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList<String>()))
            favoriteLeagueDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList<String>()))
        }
    }

    private fun createUnfilteredAdapter(items: List<String>): ArrayAdapter<String> {
        val allItems = items.toList()
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            allItems.toMutableList()
        ) {
            private val unfiltered = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    return FilterResults().apply {
                        values = allItems
                        count = allItems.size
                    }
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    clear()
                    addAll(allItems)
                    notifyDataSetChanged()
                }
            }

            override fun getFilter(): Filter = unfiltered
        }
    }

    private fun setupActions() {
        testConnectionButton.setOnClickListener { loadLeagueDirectory(showSuccessToast = true) }

        leagueDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position)?.toString().orEmpty()
            selectedLeague = leagueOptions.firstOrNull { it.label == label }
        }

        loadLeagueTeamsButton.setOnClickListener {
            val league = selectedLeague
            if (league == null) toast("リーグ・大会を選んでください") else loadTeamsForLeague(league)
        }

        favoriteLeagueDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position)?.toString().orEmpty()
            selectedFavoriteLeague = leagueOptions.firstOrNull { it.label == label }
        }
        addFavoriteLeagueButton.setOnClickListener {
            val league = selectedFavoriteLeague
            if (league == null) {
                toast("お気に入りにするリーグを選んでください")
            } else if (!FavoriteEntityRepository.addFavoriteLeague(this, league)) {
                toast("お気に入りリーグを追加できませんでした")
            } else {
                refreshLeagueFavoritesUi()
                toast("${league.name} をお気に入りに追加しました")
            }
        }

        searchTeamButton.setOnClickListener { searchTeams() }
        teamSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchTeams()
                true
            } else false
        }

        searchPlayerButton.setOnClickListener { searchPlayers() }
        playerSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPlayers()
                true
            } else false
        }

        applyHexButton.setOnClickListener {
            val parsed = FixtureRepository.parseColorOrNull(colorHexInput.text?.toString().orEmpty())
            if (parsed == null) toast("HEXカラーを正しく入力してください") else setSelectedColor(parsed)
        }
        saveButton.setOnClickListener { saveAndRefresh() }
    }

    private fun setupPopularTeams() {
        val popular = listOf(
            "Arsenal", "Liverpool", "Manchester City", "Manchester United",
            "Real Madrid", "Barcelona", "Bayern Munich", "Paris Saint-Germain",
            "Inter", "Juventus"
        )
        popularTeamsContainer.removeAllViews()
        popular.forEach { name ->
            popularTeamsContainer.addView(MaterialButton(this).apply {
                text = name
                textSize = 12f
                setOnClickListener {
                    teamSearchInput.setText(name)
                    searchTeams(name)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun setupTapTarget() {
        tapTargetDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tapLabels))
        tapTargetDropdown.setText(labelForTapTarget(FixtureRepository.getTapTarget(this)), false)
    }

    private fun setupColorControls() {
        setSelectedColor(selectedColor)
        val presets = listOf(
            0xFF15171C.toInt(), 0xFF0F172A.toInt(), 0xFF1D4ED8.toInt(), 0xFF6D28D9.toInt(),
            0xFFB91C1C.toInt(), 0xFF047857.toInt(), 0xFFF1F5F9.toInt(), 0xFFFFFFFF.toInt()
        )
        colorPresets.removeAllViews()
        presets.forEachIndexed { index, color ->
            val button = MaterialButton(this).apply {
                text = ""
                contentDescription = "カラープリセット ${index + 1}"
                backgroundTintList = ColorStateList.valueOf(color)
                cornerRadius = dp(26)
                insetTop = 0
                insetBottom = 0
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(if (Color.luminance(color) > 0.7) 0x33000000 else 0x44FFFFFF)
                setOnClickListener { setSelectedColor(color) }
            }
            colorPresets.addView(button, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) })
        }
    }

    private fun setSelectedColor(color: Int) {
        selectedColor = color
        colorPreview.backgroundTintList = ColorStateList.valueOf(color)
        colorHexInput.setText(FixtureRepository.colorToHex(color))
    }

    private fun setupIconChoices() {
        val current = LauncherIconManager.currentStyle(this)
        iconChoicesContainer.removeAllViews()
        for (style in 1..LauncherIconManager.STYLE_COUNT) {
            val card = MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                cardElevation = 0f
                strokeWidth = if (style == current) dp(3) else dp(1)
                strokeColor = if (style == current) {
                    resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
                } else {
                    resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    LauncherIconManager.selectStyle(this@MainActivity, style)
                    setupIconChoices()
                    toast("アイコン $style に変更しました")
                }
            }
            val image = ImageView(this).apply {
                setImageResource(LauncherIconManager.iconRes(style))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "アプリアイコン $style"
            }
            card.addView(image, ViewGroup.LayoutParams(dp(88), dp(88)))
            iconChoicesContainer.addView(card, LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(12) })
        }
    }

    private fun setupUpdateControls() {
        autoUpdateSwitch.isChecked = UpdateManager.isAutoEnabled(this)
        updateStatus.text = "現在 v${BuildConfig.VERSION_NAME} • 12時間ごとに更新確認"
        autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            UpdateManager.setAutoEnabled(this, checked)
            updateStatus.text = if (checked) "自動確認ON • 新版APKを自動ダウンロード" else "自動確認OFF"
        }
        checkUpdateButton.setOnClickListener { checkForUpdateManually() }

        if (autoUpdateSwitch.isChecked) {
            // Schedule the periodic check, but do not make a network request during
            // Activity startup. This prevents updater/network failures from affecting launch.
            runCatching { UpdateManager.schedule(this) }
        }
    }

    private fun checkForUpdateManually() {
        checkUpdateButton.isEnabled = false
        updateStatus.text = "アップデートを確認中…"
        UpdateManager.checkAsync(this, manual = true) { result ->
            runOnUiThread {
                checkUpdateButton.isEnabled = true
                result.onSuccess { info ->
                    if (info == null) {
                        updateStatus.text = "最新版です • v${BuildConfig.VERSION_NAME}"
                        toast("最新版です")
                    } else {
                        updateStatus.text = "v${info.versionName} をダウンロード中…"
                        runCatching { UpdateManager.enqueueDownload(this, info) }
                            .onSuccess { toast("アップデートをダウンロードします") }
                            .onFailure { updateStatus.text = "ダウンロード失敗: ${it.message ?: "エラー"}" }
                    }
                }.onFailure {
                    updateStatus.text = "更新確認できません • ${it.message ?: "通信エラー"}"
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
    }

    private fun migrateAndLoadLeagues() {
        setBusy(true)
        Thread {
            val migrated = runCatching { FixtureRepository.migrateFavoriteFormatIfNeeded(this) }.getOrDefault(false)
            val result = runCatching { FixtureRepository.fetchLeagueDirectory() }
            runOnUiThread {
                setBusy(false)
                if (migrated) refreshFavoritesUi()
                result.onSuccess { applyLeagueDirectory(it, false) }
                    .onFailure {
                        apiBadge.text = "再試行"
                        apiStatus.text = "リーグ一覧を取得できません • ${DataSourceManager.label(this)} を再確認してください"
                    }
            }
        }.start()
    }

    private fun loadLeagueDirectory(showSuccessToast: Boolean) {
        setBusy(true)
        apiStatus.text = "リーグ一覧へ接続中…"
        Thread {
            val result = runCatching { FixtureRepository.fetchLeagueDirectory() }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { applyLeagueDirectory(it, showSuccessToast) }
                    .onFailure {
                        apiBadge.text = "エラー"
                        apiStatus.text = "接続失敗 • ${it.message ?: "通信エラー"}"
                        toast("リーグ一覧の取得に失敗しました")
                    }
            }
        }.start()
    }

    private fun applyLeagueDirectory(leagues: List<LeagueInfo>, showToast: Boolean) {
        leagueOptions = leagues
        selectedLeague = null
        selectedFavoriteLeague = null
        val labels = leagues.map { it.label }
        leagueDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        leagueDropdown.setText("", false)
        favoriteLeagueDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        favoriteLeagueDropdown.setText("", false)
        apiBadge.text = "接続済み"
        apiStatus.text = "${DataSourceManager.label(this)} • ${leagues.size}リーグ/大会"
        if (showToast) toast("接続OK：${leagues.size}大会を取得しました")
    }

    private fun loadTeamsForLeague(league: LeagueInfo) {
        setBusy(true)
        Thread {
            val result = runCatching { FixtureRepository.fetchTeamsForLeague(league) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { teams ->
                    if (teams.isEmpty()) toast("この大会からチーム一覧を取得できませんでした")
                    else showTeamPicker(league.name, teams)
                }.onFailure { toast("チーム取得失敗: ${it.message ?: "通信エラー"}") }
            }
        }.start()
    }

    private fun searchTeams(queryOverride: String? = null) {
        val query = queryOverride ?: teamSearchInput.text?.toString()?.trim().orEmpty()
        if (query.length < 2) {
            toast("チーム名を2文字以上入力してください")
            return
        }
        setBusy(true)
        apiStatus.text = "${DataSourceManager.label(this)}で検索中…"
        Thread {
            val result = runCatching { FixtureRepository.searchTeams(query) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { teams ->
                    apiStatus.text = "検索OK • ${DataSourceManager.label(this)}"
                    if (teams.isEmpty()) toast("チームが見つかりませんでした。英語名でも試してください")
                    else showTeamPicker("「$query」の検索結果", teams)
                }.onFailure {
                    apiStatus.text = "検索失敗 • ${it.message ?: "通信エラー"}"
                    toast("検索失敗: ${it.message ?: "通信エラー"}")
                }
            }
        }.start()
    }

    private fun searchPlayers() {
        val query = playerSearchInput.text?.toString()?.trim().orEmpty()
        if (query.length < 2) {
            toast("選手名を2文字以上入力してください")
            return
        }
        setBusy(true)
        apiStatus.text = "${DataSourceManager.label(this)}で選手を検索中…"
        Thread {
            val result = runCatching { FavoriteEntityRepository.searchPlayers(query) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { players ->
                    apiStatus.text = "選手検索OK • ${DataSourceManager.label(this)}"
                    if (players.isEmpty()) toast("選手が見つかりませんでした。英語表記でも試してください")
                    else showPlayerPicker("「$query」の選手検索", players)
                }.onFailure {
                    apiStatus.text = "選手検索失敗 • ${it.message ?: "通信エラー"}"
                    toast("選手検索失敗: ${it.message ?: "通信エラー"}")
                }
            }
        }.start()
    }

    private fun showPlayerPicker(title: String, candidates: List<FavoritePlayer>) {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        root.addView(TextView(this).apply {
            text = "お気に入り選手 • タップですぐ追加/解除"
            textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(4), 0, dp(12))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidates.take(50).forEach { player -> list.addView(createPlayerPickerRow(player)) }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.62f).toInt()
        ))
        dialog.setContentView(root)
        dialog.show()
    }

    private fun createPlayerPickerRow(player: FavoritePlayer): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        row.addView(image, LinearLayout.LayoutParams(dp(46), dp(46)))
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        textBox.addView(TextView(this).apply {
            text = player.name
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        textBox.addView(TextView(this).apply {
            text = listOf(player.teamName, player.position, player.sourceLabel).filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val button = MaterialButton(this)
        fun currentSaved(): FavoritePlayer? = FavoriteEntityRepository.getFavoritePlayers(this).firstOrNull {
            it.id == player.id || (it.name.equals(player.name, true) && it.teamName.equals(player.teamName, true))
        }
        fun renderButton() {
            button.text = if (currentSaved() != null) "✓ 追加済み" else "＋ 追加"
        }
        renderButton()
        button.setOnClickListener {
            val saved = currentSaved()
            if (saved != null) {
                FavoriteEntityRepository.removeFavoritePlayer(this, saved.id)
            } else if (!FavoriteEntityRepository.addFavoritePlayer(this, player)) {
                toast("選手を追加できませんでした")
            }
            renderButton()
            refreshPlayerFavoritesUi()
        }
        row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        card.addView(row)
        Thread {
            val bitmap = EntityImageLoader.loadPlayer(this, player)
            if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
        }.start()
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    /** FotMob/SofaScore風: 検索結果から即追加・即解除。確定ボタンは不要。 */
    private fun showTeamPicker(title: String, candidates: List<FavoriteTeam>) {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        root.addView(TextView(this).apply {
            text = "お気に入りチーム • タップですぐ追加/解除"
            textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(4), 0, dp(12))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidates.take(50).forEach { team -> list.addView(createPickerRow(team)) }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.62f).toInt()
        ))
        dialog.setContentView(root)
        dialog.show()
    }

    private fun createPickerRow(team: FavoriteTeam): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        textBox.addView(TextView(this).apply {
            text = team.name
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        textBox.addView(TextView(this).apply {
            text = listOf(team.country, team.sourceLabel).filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val button = MaterialButton(this)
        fun currentSaved(): FavoriteTeam? = FixtureRepository.getFavoriteTeams(this).firstOrNull {
            FixtureRepository.normalizeTeamName(it.name) == FixtureRepository.normalizeTeamName(team.name)
        }
        fun renderButton() {
            val saved = currentSaved()
            button.text = if (saved != null) "✓ 追加済み" else "＋ 追加"
            button.isSelected = saved != null
        }
        renderButton()
        button.setOnClickListener {
            val saved = currentSaved()
            if (saved != null) {
                FixtureRepository.removeFavoriteTeam(this, saved.id)
                renderButton()
                refreshFavoritesUi()
            } else {
                if (!FixtureRepository.addFavoriteTeam(this, team)) {
                    toast("チームを追加できませんでした")
                } else {
                    renderButton()
                    refreshFavoritesUi()
                }
            }
        }
        row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        card.addView(row)

        Thread {
            val bitmap = TeamLogoLoader.load(this, team)
            if (bitmap != null) runOnUiThread { logo.setImageBitmap(bitmap) }
        }.start()
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun refreshFavoritesUi() {
        val teams = FixtureRepository.getFavoriteTeams(this)
        favoritesCount.text = "先頭が初期チーム"
        favoritesContainer.removeAllViews()

        if (teams.isEmpty()) {
            favoritesContainer.addView(TextView(this).apply {
                text = "まだチームがありません。人気チーム、リーグ、検索から追加してください。"
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
            val logo = ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            row.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))

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
                val prefix = if (index == 0) "初期チーム" else team.sourceLabel
                text = if (index == 0) "$prefix • ${team.sourceLabel}" else prefix
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
            favoritesContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            Thread {
                val bitmap = TeamLogoLoader.load(this, team)
                if (bitmap != null) runOnUiThread { logo.setImageBitmap(bitmap) }
            }.start()
        }
    }

    private fun refreshPlayerFavoritesUi() {
        val players = FavoriteEntityRepository.getFavoritePlayers(this)
        playersCount.text = ""
        favoritePlayersContainer.removeAllViews()
        if (players.isEmpty()) {
            favoritePlayersContainer.addView(TextView(this).apply {
                text = "まだ選手がありません。上の検索から追加してください。"
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                textSize = 14f
                setPadding(dp(4), dp(10), dp(4), dp(6))
            })
            return
        }
        players.forEach { player ->
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
            val image = ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            box.addView(TextView(this).apply {
                text = player.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
            })
            box.addView(TextView(this).apply {
                text = listOf(player.teamName, player.position, player.sourceLabel).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this).apply {
                text = "削除"
                textSize = 12f
                setOnClickListener {
                    FavoriteEntityRepository.removeFavoritePlayer(this@MainActivity, player.id)
                    refreshPlayerFavoritesUi()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            card.addView(row)
            favoritePlayersContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            Thread {
                val bitmap = EntityImageLoader.loadPlayer(this, player)
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            }.start()
        }
    }

    private fun refreshLeagueFavoritesUi() {
        val leagues = FavoriteEntityRepository.getFavoriteLeagues(this)
        favoriteLeaguesCount.text = ""
        favoriteLeaguesContainer.removeAllViews()
        if (leagues.isEmpty()) {
            favoriteLeaguesContainer.addView(TextView(this).apply {
                text = "まだリーグがありません。リーグ・大会を選んで追加してください。"
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                textSize = 14f
                setPadding(dp(4), dp(10), dp(4), dp(6))
            })
            return
        }
        leagues.forEach { league ->
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
            val image = ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            box.addView(TextView(this).apply {
                text = league.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
            })
            box.addView(TextView(this).apply {
                text = league.country.ifBlank { "International" }
                textSize = 12f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this).apply {
                text = "削除"
                textSize = 12f
                setOnClickListener {
                    FavoriteEntityRepository.removeFavoriteLeague(this@MainActivity, league.id)
                    refreshLeagueFavoritesUi()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            card.addView(row)
            favoriteLeaguesContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            Thread {
                val bitmap = EntityImageLoader.loadLeague(this, league)
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            }.start()
        }
    }

    private fun saveAndRefresh() {
        val target = tapTargetForLabel(tapTargetDropdown.text?.toString().orEmpty())
        FixtureRepository.saveTapTarget(this, target)
        FixtureRepository.saveWidgetColor(this, selectedColor)
        FixtureWidgetProvider.renderAll(this, statusOverride = "更新中…")
        PlayerWidgetProvider.renderAll(this, statusOverride = "更新中…")
        LeagueWidgetProvider.renderAll(this, statusOverride = "更新中…")
        sendBroadcast(Intent(this, FixtureWidgetProvider::class.java).apply { action = FixtureWidgetProvider.ACTION_REFRESH })
        sendBroadcast(Intent(this, PlayerWidgetProvider::class.java).apply { action = PlayerWidgetProvider.ACTION_REFRESH })
        sendBroadcast(Intent(this, LeagueWidgetProvider::class.java).apply { action = LeagueWidgetProvider.ACTION_REFRESH })
        toast("保存しました。チーム・選手・リーグの日程を更新しています")
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
        searchPlayerButton.isEnabled = !busy
        addFavoriteLeagueButton.isEnabled = !busy
        saveButton.isEnabled = !busy
    }

    private fun resolveThemeColor(attr: Int): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(attr, out, true)
        return if (out.resourceId != 0) getColor(out.resourceId) else out.data
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
