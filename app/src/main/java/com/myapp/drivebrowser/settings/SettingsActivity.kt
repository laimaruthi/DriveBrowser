package com.myapp.drivebrowser.settings

import android.os.Bundle
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myapp.drivebrowser.BuildConfig
import com.myapp.drivebrowser.R
import com.myapp.drivebrowser.data.BrowserPreferences
import com.myapp.drivebrowser.model.QuickActionButtonMode
import com.myapp.drivebrowser.model.QuickActionButtonPosition
import com.myapp.drivebrowser.update.UpdateChecker
import com.myapp.drivebrowser.databinding.ActivitySettingsBinding
import com.myapp.drivebrowser.model.AppThemeMode
import com.myapp.drivebrowser.ui.ThemeManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                BrowserPreferences.setStartBackgroundUri(this, uri.toString())
                toastMsg(getString(R.string.settings_start_background))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadInto()

        binding.btnSaveSettings.setOnClickListener { save(); finish() }

        binding.btnClearCleartext.setOnClickListener {
            BrowserPreferences.clearAllowedCleartextHosts(this)
            toast()
        }
        binding.btnClearData.setOnClickListener {
            BrowserPreferences.clearSitePermissions(this)
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            toast()
        }

        binding.labelVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }

        binding.btnPickBackground.setOnClickListener { pickBackground.launch(arrayOf("image/*")) }
        binding.btnClearBackground.setOnClickListener {
            BrowserPreferences.setStartBackgroundUri(this, null); toast()
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.update_checking)
        UpdateChecker.check(BuildConfig.VERSION_NAME) { result ->
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = getString(R.string.settings_check_update)
            when {
                result.error != null ->
                    toastMsg(getString(R.string.update_failed, result.error))
                result.updateAvailable ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_message, result.latestVersion, result.currentVersion))
                        .setNegativeButton(R.string.update_later, null)
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))) }
                        }
                        .show()
                else ->
                    toastMsg(getString(R.string.update_up_to_date, result.currentVersion))
            }
        }
    }

    private fun toastMsg(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun loadInto() = with(binding) {
        editHomePage.setText(BrowserPreferences.getHomePageUrl(this@SettingsActivity).orEmpty())
        switchRestoreTabs.isChecked = BrowserPreferences.isRestoreTabs(this@SettingsActivity)
        switchDesktop.isChecked = BrowserPreferences.isDesktopDefault(this@SettingsActivity)
        switchPersistentUrl.isChecked = BrowserPreferences.isPersistentUrlBar(this@SettingsActivity)
        switchResumeLastPage.isChecked = BrowserPreferences.isResumeLastPage(this@SettingsActivity)
        switchDarkPages.isChecked = BrowserPreferences.isDarkPagesEnabled(this@SettingsActivity)
        switchAdBlock.isChecked = BrowserPreferences.isAdBlockEnabled(this@SettingsActivity)
        val scale = BrowserPreferences.getGlobalScalePercent(this@SettingsActivity)
        seekScale.progress = scale
        labelScale.text = getString(R.string.settings_display_scale) + ": $scale%"
        seekScale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                labelScale.text = getString(R.string.settings_display_scale) + ": ${value.coerceAtLeast(50)}%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        when (BrowserPreferences.getThemeMode(this@SettingsActivity)) {
            AppThemeMode.SYSTEM -> themeSystem.isChecked = true
            AppThemeMode.LIGHT -> themeLight.isChecked = true
            AppThemeMode.DARK -> themeDark.isChecked = true
        }
        switchFab.isChecked = BrowserPreferences.isFabEnabled(this@SettingsActivity)
        when (BrowserPreferences.getFabMode(this@SettingsActivity)) {
            QuickActionButtonMode.MENU -> fabModeMenu.isChecked = true
            QuickActionButtonMode.URL_BAR -> fabModeUrl.isChecked = true
        }
        when (BrowserPreferences.getFabPosition(this@SettingsActivity)) {
            QuickActionButtonPosition.TOP_START -> fabPosTopStart.isChecked = true
            QuickActionButtonPosition.TOP_END -> fabPosTopEnd.isChecked = true
            QuickActionButtonPosition.BOTTOM_START -> fabPosBottomStart.isChecked = true
            QuickActionButtonPosition.BOTTOM_END -> fabPosBottomEnd.isChecked = true
        }
    }

    private fun save() = with(binding) {
        val ctx = this@SettingsActivity
        BrowserPreferences.setHomePageUrl(ctx, editHomePage.text?.toString())
        BrowserPreferences.setRestoreTabs(ctx, switchRestoreTabs.isChecked)
        BrowserPreferences.setDesktopDefault(ctx, switchDesktop.isChecked)
        BrowserPreferences.setPersistentUrlBar(ctx, switchPersistentUrl.isChecked)
        BrowserPreferences.setResumeLastPage(ctx, switchResumeLastPage.isChecked)
        BrowserPreferences.setGlobalScalePercent(ctx, seekScale.progress.coerceAtLeast(50))
        BrowserPreferences.setDarkPagesEnabled(ctx, switchDarkPages.isChecked)
        BrowserPreferences.setAdBlockEnabled(ctx, switchAdBlock.isChecked)
        com.myapp.drivebrowser.adblock.AdBlocker.setEnabled(switchAdBlock.isChecked)
        val mode = when {
            themeLight.isChecked -> AppThemeMode.LIGHT
            themeDark.isChecked -> AppThemeMode.DARK
            else -> AppThemeMode.SYSTEM
        }
        BrowserPreferences.setThemeMode(ctx, mode)
        ThemeManager.applyThemeMode(mode)

        BrowserPreferences.setFabEnabled(ctx, switchFab.isChecked)
        BrowserPreferences.setFabMode(
            ctx,
            if (fabModeUrl.isChecked) QuickActionButtonMode.URL_BAR else QuickActionButtonMode.MENU
        )
        BrowserPreferences.setFabPosition(
            ctx,
            when {
                fabPosTopStart.isChecked -> QuickActionButtonPosition.TOP_START
                fabPosTopEnd.isChecked -> QuickActionButtonPosition.TOP_END
                fabPosBottomStart.isChecked -> QuickActionButtonPosition.BOTTOM_START
                else -> QuickActionButtonPosition.BOTTOM_END
            }
        )
    }

    private fun toast() = Toast.makeText(this, getString(com.myapp.drivebrowser.R.string.settings_cleared), Toast.LENGTH_SHORT).show()
}
