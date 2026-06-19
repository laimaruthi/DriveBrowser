package com.myapp.drivebrowser.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myapp.drivebrowser.data.BrowserPreferences
import com.myapp.drivebrowser.databinding.ActivitySettingsBinding
import com.myapp.drivebrowser.model.AppThemeMode
import com.myapp.drivebrowser.ui.ThemeManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

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
    }

    private fun loadInto() = with(binding) {
        editHomePage.setText(BrowserPreferences.getHomePageUrl(this@SettingsActivity).orEmpty())
        switchRestoreTabs.isChecked = BrowserPreferences.isRestoreTabs(this@SettingsActivity)
        switchDesktop.isChecked = BrowserPreferences.isDesktopDefault(this@SettingsActivity)
        switchPersistentUrl.isChecked = BrowserPreferences.isPersistentUrlBar(this@SettingsActivity)
        switchDarkPages.isChecked = BrowserPreferences.isDarkPagesEnabled(this@SettingsActivity)
        switchAdBlock.isChecked = BrowserPreferences.isAdBlockEnabled(this@SettingsActivity)
        when (BrowserPreferences.getThemeMode(this@SettingsActivity)) {
            AppThemeMode.SYSTEM -> themeSystem.isChecked = true
            AppThemeMode.LIGHT -> themeLight.isChecked = true
            AppThemeMode.DARK -> themeDark.isChecked = true
        }
    }

    private fun save() = with(binding) {
        val ctx = this@SettingsActivity
        BrowserPreferences.setHomePageUrl(ctx, editHomePage.text?.toString())
        BrowserPreferences.setRestoreTabs(ctx, switchRestoreTabs.isChecked)
        BrowserPreferences.setDesktopDefault(ctx, switchDesktop.isChecked)
        BrowserPreferences.setPersistentUrlBar(ctx, switchPersistentUrl.isChecked)
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
    }

    private fun toast() = Toast.makeText(this, getString(com.myapp.drivebrowser.R.string.settings_cleared), Toast.LENGTH_SHORT).show()
}
