package com.videoconverter.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.videoconverter.android.document.installPoiStaxFactories

class LiteTransApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setApplicationLocales(AppCompatDelegate.getApplicationLocales())
        installPoiStaxFactories()
    }
}
