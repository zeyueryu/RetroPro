package com.retropro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import com.retropro.feature.shell.AppShell
import com.retropro.uikit.theme.AppTheme
import com.retropro.util.AppPrefs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 首帧前同步应用一次偏好（isDarkIntent）。注意它只写「意图层」，
        // 计分板的 AMOLED 覆写（forceDark）不会被这里冲掉。
        AppPrefs.applyThemeAtStartup(this, resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

        setContent {
            // 「跟随系统」：系统切换深色时该值变化，重新应用偏好；
            // 用户明确选了浅/深时本来就会在 onCreate/设置页写意图层。
            val systemDark = isSystemInDarkTheme()
            LaunchedEffect(systemDark) {
                AppPrefs.applyThemeAtStartup(this@MainActivity, systemDark)
            }

            AppTheme {
                AppShell()
            }
        }
    }
}
