package com.example.footballfixturewidget

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.net.Uri

class MainActivity : Activity() {
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var favoritesCount: TextView
    private lateinit var tokenInput: EditText
    private lateinit var tapSpinner: Spinner
    private lateinit var colorPreview: View
    private lateinit var colorHex: EditText
    private lateinit var redSeek: SeekBar
    private lateinit var greenSeek: SeekBar
    private lateinit var blueSeek: SeekBar
    private lateinit var redLabel: TextView
    private lateinit var greenLabel: TextView
    private lateinit var blueLabel: TextView
    private lateinit var progress: ProgressBar

    private var selectedColor: Int = FixtureRepository.DEFAULT_WIDGET_COLOR
    private var updatingColorControls = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int) = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedColor = FixtureRepository.getWidgetColor(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        scroll.addView(root)

        root.addView(title("Football Fixtures", 28f))
        root.addView(body("お気に入りのクラブを最大10チーム選び、それぞれの次の試合をホーム画面に表示します。最上段のチームが初期（先頭）チームです。"))

        addSection(root, "1. API設定")
        root.addView(label("football-data.org APIキー"))
        tokenInput = EditText(this).apply {
            hint = "APIキーを貼り付け"
            setText(FixtureRepository.getToken(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(tokenInput, matchWrap())

        val apiSiteButton = Button(this).apply {
            text = "football-data.org を開く"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.football-data.org/"))) }
        }
        root.addView(apiSiteButton, matchHeight(48, 8))

        addSection(root, "2. お気に入りチーム")
        favoritesCount = label("")
        root.addView(favoritesCount)
        root.addView(body("リーグからチーム一覧を読み込んで選択できます。最大10チーム。『先頭』でウィジェットの最初に表示するチームを変更できます。"))

        val competitionSpinner = Spinner(this)
        val competitionLabels = FixtureRepository.COMPETITIONS.map { "${it.first} (${it.second})" }
        competitionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, competitionLabels)
        root.addView(competitionSpinner, matchWrap())

        val loadTeamsButton = Button(this).apply {
            text = "このリーグからチームを選ぶ"
            setOnClickListener {
                val code = FixtureRepository.COMPETITIONS[competitionSpinner.selectedItemPosition].second
                loadCompetitionTeams(code)
            }
        }
        root.addView(loadTeamsButton, matchHeight(52, 8))

        root.addView(label("Team IDから直接追加（任意）").apply { setPadding(0, dp(16), 0, 0) })
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val teamIdInput = EditText(this).apply {
            hint = "例: 57"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        manualRow.addView(teamIdInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        manualRow.addView(Button(this).apply {
            text = "追加"
            setOnClickListener {
                val id = teamIdInput.text.toString().toIntOrNull()
                if (id == null || id <= 0) {
                    toast("Team IDを正しく入力してください")
                    return@setOnClickListener
                }
                addManualTeam(id) { teamIdInput.text.clear() }
            }
        }, LinearLayout.LayoutParams(dp(96), dp(48)).apply { leftMargin = dp(8) })
        root.addView(manualRow, matchWrap())

        favoritesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(favoritesContainer, matchWrap())

        addSection(root, "3. チームをタップした時")
        root.addView(body("ウィジェット内のチームを押したときの動作を選べます。FotMob / SofaScoreでは、そのチームの表示中の次の試合ページを直接開きます。試合IDを取得できない場合だけアプリ本体へフォールバックします。"))
        tapSpinner = Spinner(this)
        tapSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "何もしない",
                "FotMobのその試合を開く",
                "SofaScoreのその試合を開く",
                "OneFootballを開く",
                "Flashscoreを開く",
                "LiveScoreを開く",
                "365Scoresを開く",
                "このアプリの設定を開く"
            )
        )
        tapSpinner.setSelection(
            when (FixtureRepository.getTapTarget(this)) {
                FixtureRepository.TAP_FOTMOB -> 1
                FixtureRepository.TAP_SOFASCORE -> 2
                FixtureRepository.TAP_ONEFOOTBALL -> 3
                FixtureRepository.TAP_FLASHSCORE -> 4
                FixtureRepository.TAP_LIVESCORE -> 5
                FixtureRepository.TAP_365SCORES -> 6
                FixtureRepository.TAP_SETTINGS -> 7
                else -> 0
            }
        )
        root.addView(tapSpinner, matchWrap())

        addSection(root, "4. ウィジェットの色")
        root.addView(body("RGBスライダーまたはHEX（例 #17202A）で好きな色を指定できます。文字色は読みやすいよう自動で白/黒に切り替わります。"))

        colorPreview = View(this)
        root.addView(colorPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { bottomMargin = dp(12) })

        val hexRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colorHex = EditText(this).apply {
            hint = "#17202A"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        hexRow.addView(colorHex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hexRow.addView(Button(this).apply {
            text = "HEX反映"
            setOnClickListener {
                val parsed = FixtureRepository.parseColorOrNull(colorHex.text.toString())
                if (parsed == null) toast("HEXカラーを正しく入力してください") else setColorControls(parsed)
            }
        }, LinearLayout.LayoutParams(dp(112), dp(48)).apply { leftMargin = dp(8) })
        root.addView(hexRow, matchWrap())

        redLabel = label("")
        redSeek = colorSeekBar()
        root.addView(redLabel)
        root.addView(redSeek, matchWrap())
        greenLabel = label("")
        greenSeek = colorSeekBar()
        root.addView(greenLabel)
        root.addView(greenSeek, matchWrap())
        blueLabel = label("")
        blueSeek = colorSeekBar()
        root.addView(blueLabel)
        root.addView(blueSeek, matchWrap())

        val presetScroller = HorizontalScrollView(this)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "濃紺" to Color.rgb(23, 32, 42),
            "黒" to Color.BLACK,
            "白" to Color.WHITE,
            "赤" to Color.rgb(190, 30, 45),
            "青" to Color.rgb(20, 80, 180),
            "緑" to Color.rgb(20, 120, 75),
            "紫" to Color.rgb(95, 55, 160)
        ).forEach { (name, color) ->
            presets.addView(Button(this).apply {
                text = name
                setOnClickListener { setColorControls(color) }
            }, LinearLayout.LayoutParams(dp(82), dp(44)).apply { rightMargin = dp(6) })
        }
        presetScroller.addView(presets)
        root.addView(presetScroller, matchWrap().apply { topMargin = dp(8) })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(12) })

        val saveButton = Button(this).apply {
            text = "設定を保存してウィジェットを更新"
            textSize = 16f
            setOnClickListener { saveAndRefresh() }
        }
        root.addView(saveButton, matchHeight(58, 14))

        root.addView(body("試合日時は端末のタイムゾーンで『8/30 (日) 22:00』のように曜日＋24時間表記で表示されます。FotMob / SofaScoreを選んだ場合は更新時に外部サービスの試合IDも照合します。ウィジェット右上の ↻ で手動更新できます。\nPrimary fixture data provided by football-data.org"))

        setContentView(scroll)
        attachColorListeners()
        setColorControls(selectedColor)
        refreshFavoritesUi()
    }

    private fun addSection(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.rgb(20, 20, 20))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(28), 0, dp(8))
        })
    }

    private fun refreshFavoritesUi() {
        val teams = FixtureRepository.getFavoriteTeams(this)
        favoritesCount.text = "選択中: ${teams.size} / ${FixtureRepository.MAX_FAVORITES}"
        favoritesContainer.removeAllViews()
        if (teams.isEmpty()) {
            favoritesContainer.addView(body("まだチームが選択されていません。最初に追加したチームが初期チームになります。"))
            return
        }

        teams.forEachIndexed { index, team ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(4), dp(7))
                setBackgroundColor(if (index == 0) Color.rgb(229, 239, 255) else Color.WHITE)
            }
            row.addView(TextView(this).apply {
                text = if (index == 0) "★ ${team.name}\n初期/先頭チーム" else team.name
                textSize = 15f
                setTextColor(Color.rgb(25, 25, 25))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index != 0) {
                row.addView(Button(this).apply {
                    text = "先頭"
                    setOnClickListener {
                        FixtureRepository.moveFavoriteToTop(this@MainActivity, team.id)
                        refreshFavoritesUi()
                        notifyWidgetDataChanged()
                    }
                }, LinearLayout.LayoutParams(dp(72), dp(44)))
            }
            row.addView(Button(this).apply {
                text = "削除"
                setOnClickListener {
                    FixtureRepository.removeFavoriteTeam(this@MainActivity, team.id)
                    refreshFavoritesUi()
                    notifyWidgetDataChanged()
                }
            }, LinearLayout.LayoutParams(dp(72), dp(44)).apply { leftMargin = dp(5) })
            favoritesContainer.addView(row, matchWrap().apply { bottomMargin = dp(6) })
        }
    }

    private fun loadCompetitionTeams(code: String) {
        val token = tokenInput.text.toString().trim()
        if (token.isBlank()) {
            toast("先にAPIキーを入力してください")
            return
        }
        FixtureRepository.saveToken(this, token)
        setBusy(true)
        Thread {
            try {
                val teams = FixtureRepository.fetchTeamsForCompetition(this, code)
                runOnUiThread {
                    setBusy(false)
                    showTeamPicker(teams)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    toast("チーム一覧の取得に失敗: ${t.message}")
                }
            }
        }.start()
    }

    private fun showTeamPicker(allTeams: List<FavoriteTeam>) {
        val current = FixtureRepository.getFavoriteTeams(this)
        val currentIds = current.map { it.id }.toSet()
        val available = allTeams.filterNot { it.id in currentIds }
        if (available.isEmpty()) {
            toast("追加できるチームがありません")
            return
        }
        val remaining = FixtureRepository.MAX_FAVORITES - current.size
        if (remaining <= 0) {
            toast("お気に入りは最大10チームです")
            return
        }

        val checked = BooleanArray(available.size)
        AlertDialog.Builder(this)
            .setTitle("チームを選択（あと${remaining}チーム）")
            .setMultiChoiceItems(available.map { it.name }.toTypedArray(), checked) { dialog, which, isChecked ->
                checked[which] = isChecked
                if (checked.count { it } > remaining) {
                    checked[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    toast("あと${remaining}チームまで追加できます")
                }
            }
            .setPositiveButton("追加") { _, _ ->
                val chosen = available.filterIndexed { index, _ -> checked[index] }
                val merged = (current + chosen).distinctBy { it.id }.take(FixtureRepository.MAX_FAVORITES)
                FixtureRepository.saveFavoriteTeams(this, merged)
                refreshFavoritesUi()
                requestWidgetRefresh()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun addManualTeam(teamId: Int, onSuccess: () -> Unit) {
        val current = FixtureRepository.getFavoriteTeams(this)
        if (current.size >= FixtureRepository.MAX_FAVORITES) {
            toast("お気に入りは最大10チームです")
            return
        }
        val token = tokenInput.text.toString().trim()
        if (token.isBlank()) {
            toast("先にAPIキーを入力してください")
            return
        }
        FixtureRepository.saveToken(this, token)
        setBusy(true)
        Thread {
            try {
                val team = FixtureRepository.resolveTeam(this, teamId)
                val ok = FixtureRepository.addFavoriteTeam(this, team)
                runOnUiThread {
                    setBusy(false)
                    if (ok) {
                        onSuccess()
                        refreshFavoritesUi()
                        requestWidgetRefresh()
                        toast("${team.name} を追加しました")
                    } else toast("お気に入りは最大10チームです")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    toast("チーム取得に失敗: ${t.message}")
                }
            }
        }.start()
    }

    private fun saveAndRefresh() {
        FixtureRepository.saveToken(this, tokenInput.text.toString())
        FixtureRepository.saveWidgetColor(this, selectedColor)
        FixtureRepository.saveTapTarget(
            this,
            when (tapSpinner.selectedItemPosition) {
                1 -> FixtureRepository.TAP_FOTMOB
                2 -> FixtureRepository.TAP_SOFASCORE
                3 -> FixtureRepository.TAP_ONEFOOTBALL
                4 -> FixtureRepository.TAP_FLASHSCORE
                5 -> FixtureRepository.TAP_LIVESCORE
                6 -> FixtureRepository.TAP_365SCORES
                7 -> FixtureRepository.TAP_SETTINGS
                else -> FixtureRepository.TAP_NONE
            }
        )
        sendBroadcast(Intent(this, FixtureWidgetProvider::class.java).apply {
            action = FixtureWidgetProvider.ACTION_REFRESH
        })
        notifyWidgetDataChanged()
        toast("設定を保存しました")
    }

    private fun requestWidgetRefresh() {
        sendBroadcast(Intent(this, FixtureWidgetProvider::class.java).apply {
            action = FixtureWidgetProvider.ACTION_REFRESH
        })
    }

    private fun notifyWidgetDataChanged() {
        FixtureWidgetProvider.renderAll(this)
    }

    private fun attachColorListeners() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingColorControls) return
                selectedColor = Color.rgb(redSeek.progress, greenSeek.progress, blueSeek.progress)
                updateColorPreviewOnly()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        redSeek.setOnSeekBarChangeListener(listener)
        greenSeek.setOnSeekBarChangeListener(listener)
        blueSeek.setOnSeekBarChangeListener(listener)
    }

    private fun setColorControls(color: Int) {
        selectedColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        updatingColorControls = true
        redSeek.progress = Color.red(selectedColor)
        greenSeek.progress = Color.green(selectedColor)
        blueSeek.progress = Color.blue(selectedColor)
        updatingColorControls = false
        updateColorPreviewOnly()
    }

    private fun updateColorPreviewOnly() {
        colorPreview.setBackgroundColor(selectedColor)
        colorHex.setText(FixtureRepository.colorToHex(selectedColor))
        colorHex.setSelection(colorHex.text.length)
        redLabel.text = "R: ${Color.red(selectedColor)}"
        greenLabel.text = "G: ${Color.green(selectedColor)}"
        blueLabel.text = "B: ${Color.blue(selectedColor)}"
    }

    private fun colorSeekBar() = SeekBar(this).apply { max = 255 }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(17, 17, 17))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(70, 70, 70))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(82, 82, 82))
        setPadding(0, dp(6), 0, dp(8))
        setLineSpacing(0f, 1.15f)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchHeight(height: Int, top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(top) }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
