package com.example.footballfixturewidget

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class UpdateInstallActivity : AppCompatActivity() {
    private var requestedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPermission = savedInstanceState?.getBoolean("requested_permission", false) ?: false
        continueInstall()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested_permission", requestedPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (requestedPermission) continueInstall()
    }

    private fun continueInstall() {
        val file = UpdateManager.downloadedFile(this) ?: run {
            Toast.makeText(this, "検証済みの更新APKが見つかりません", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            if (!requestedPermission) {
                requestedPermission = true
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                runCatching { startActivity(settingsIntent) }
                    .onFailure {
                        Toast.makeText(this, "「不明なアプリのインストール」を許可してください", Toast.LENGTH_LONG).show()
                        finish()
                    }
                return
            }
            Toast.makeText(this, "更新するには、このアプリからのインストールを許可してください", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        requestedPermission = false
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(flags)
        }
        try {
            startActivity(installIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(flags)
                })
            } catch (t: Throwable) {
                Toast.makeText(this, "Androidのインストーラーを開けません: ${t.message ?: "エラー"}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
