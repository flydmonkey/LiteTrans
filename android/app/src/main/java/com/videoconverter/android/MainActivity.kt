package com.videoconverter.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.domain.markInterrupted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JobStore(this).update(::markInterrupted)
    }
}
