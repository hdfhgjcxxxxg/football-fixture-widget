package com.example.footballfixturewidget

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class UpdateInstallActivity : AppCompatActivity() {
    private var requestedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continueInstall()
    }

    override fun onResume() {
        super.onResume()
        if (requestedPermission) continueInstall()
    }

    private fun continueInstall() {
        val file = UpdateManager.downloadedFile(this) ?: run { finish(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            if (!requestedPermission) {
                requestedPermission = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }
}
