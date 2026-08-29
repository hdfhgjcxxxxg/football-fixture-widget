package com.example.footballfixturewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors

abstract class BaseWidgetConfigActivity : AppCompatActivity() {
    abstract val kind: String
    abstract val heading: String
    abstract val entityLabel: String

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var root: LinearLayout
    private val checks = LinkedHashMap<Int, MaterialCheckBox>()
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(Activity.RESULT_CANCELED)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) populateChoices()
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
        populateChoices()
    }

    private fun populateChoices() {
        root.removeAllViews()
        checks.clear()

        root.addView(TextView(this).apply {
            text = heading
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        root.addView(TextView(this).apply {
            text = "このウィジェットだけに表示する${entityLabel}を選びます。あとからウィジェットのタイトルをタップして変更できます。"
            textSize = 14f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, dp(18))
        })

        val entities = entities()
        if (entities.isEmpty()) {
            root.addView(MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
                addView(LinearLayout(this@BaseWidgetConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                    addView(TextView(this@BaseWidgetConfigActivity).apply {
                        text = "お気に入り${entityLabel}がまだありません"
                        textSize = 17f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@BaseWidgetConfigActivity).apply {
                        text = "先にMatchDay Widgetを開いてお気に入りを追加してください。"
                        textSize = 13f
                        setPadding(0, dp(6), 0, dp(12))
                    })
                    addView(MaterialButton(this@BaseWidgetConfigActivity).apply {
                        text = "お気に入りを追加する"
                        setOnClickListener { startActivity(Intent(this@BaseWidgetConfigActivity, MainActivity::class.java)) }
                    })
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }

        val selected = WidgetSelectionStore.getSelectedIds(this, widgetId, kind).toSet()
        entities.forEach { entity ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(14), dp(12))
            }
            val textBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            textBox.addView(TextView(this).apply {
                text = entity.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
            })
            textBox.addView(TextView(this).apply {
                text = entity.sub
                textSize = 12f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = MaterialCheckBox(this).apply {
                isChecked = selected.contains(entity.id)
                setOnCheckedChangeListener { button, isChecked ->
                    if (isChecked && checks.values.count { it.isChecked } > 10) {
                        button.isChecked = false
                        Toast.makeText(this@BaseWidgetConfigActivity, "1つのウィジェットは最大10件です", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            checks[entity.id] = check
            row.addView(check)
            card.addView(row)
            root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        root.addView(MaterialButton(this).apply {
            text = "この内容でウィジェットを作成"
            textSize = 16f
            setOnClickListener { saveAndFinish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })
    }

    private fun saveAndFinish() {
        val ids = checks.filterValues { it.isChecked }.keys.toList()
        if (ids.isEmpty()) {
            Toast.makeText(this, "表示する${entityLabel}を1つ以上選んでください", Toast.LENGTH_SHORT).show()
            return
        }
        WidgetSelectionStore.saveSelectedIds(this, widgetId, kind, ids)
        when (kind) {
            WidgetKinds.PLAYER -> {
                PlayerWidgetProvider.renderAll(this, "更新中…")
                sendBroadcast(Intent(this, PlayerWidgetProvider::class.java).apply { action = PlayerWidgetProvider.ACTION_REFRESH })
            }
            WidgetKinds.LEAGUE -> {
                LeagueWidgetProvider.renderAll(this, "更新中…")
                sendBroadcast(Intent(this, LeagueWidgetProvider::class.java).apply { action = LeagueWidgetProvider.ACTION_REFRESH })
            }
            else -> {
                FixtureWidgetProvider.renderAll(this, "更新中…")
                sendBroadcast(Intent(this, FixtureWidgetProvider::class.java).apply { action = FixtureWidgetProvider.ACTION_REFRESH })
            }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private data class Choice(val id: Int, val name: String, val sub: String)

    private fun entities(): List<Choice> = when (kind) {
        WidgetKinds.PLAYER -> FavoriteEntityRepository.getFavoritePlayers(this).map {
            Choice(it.id, it.name, listOf(it.teamName, it.position).filter { s -> s.isNotBlank() }.joinToString(" • "))
        }
        WidgetKinds.LEAGUE -> FavoriteEntityRepository.getFavoriteLeagues(this).map {
            Choice(it.id, it.name, it.country)
        }
        else -> FixtureRepository.getFavoriteTeams(this).map {
            Choice(it.id, it.name, listOf(it.country, it.sourceLabel).filter { s -> s.isNotBlank() }.joinToString(" • "))
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(attr, out, true)
        return if (out.resourceId != 0) getColor(out.resourceId) else out.data
    }
}

class TeamWidgetConfigActivity : BaseWidgetConfigActivity() {
    override val kind = WidgetKinds.TEAM
    override val heading = "チームウィジェット"
    override val entityLabel = "チーム"
}

class PlayerWidgetConfigActivity : BaseWidgetConfigActivity() {
    override val kind = WidgetKinds.PLAYER
    override val heading = "選手ウィジェット"
    override val entityLabel = "選手"
}

class LeagueWidgetConfigActivity : BaseWidgetConfigActivity() {
    override val kind = WidgetKinds.LEAGUE
    override val heading = "リーグウィジェット"
    override val entityLabel = "リーグ"
}
