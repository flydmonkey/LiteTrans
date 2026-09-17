package com.videoconverter.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class LiteTransApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setApplicationLocales(AppCompatDelegate.getApplicationLocales())
    }
}
