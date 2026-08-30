package com.example.footballfixturewidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SafeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RuntimeCrashStore.hasPending(this)) {
            openMain()
            return
        }
        showCrashRecovery()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showCrashRecovery() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(44), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "前回の起動でクラッシュしました"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} ではクラッシュログを保存しています。ログを共有すると原因を特定できます。"
            textSize = 15f
            setPadding(0, dp(10), 0, dp(18))
        })

        val report = TextView(this).apply {
            text = RuntimeCrashStore.read(this@SafeLauncherActivity)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(Button(this).apply {
            text = "クラッシュログを共有"
            setOnClickListener {
                val text = RuntimeCrashStore.read(this@SafeLauncherActivity)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "FootballFixtureWidget runtime crash v${BuildConfig.VERSION_NAME}")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(share, "クラッシュログを共有"))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(16)
        })

        root.addView(Button(this).apply {
            text = "ログを消して通常起動を試す"
            setOnClickListener {
                RuntimeCrashStore.clear(this@SafeLauncherActivity)
                openMain()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(10)
        })

        root.gravity = Gravity.CENTER_HORIZONTAL
        setContentView(root)
    }
}
