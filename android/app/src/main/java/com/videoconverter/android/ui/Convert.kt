package com.videoconverter.android.ui

import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.SessionStore
import com.videoconverter.android.domain.MediaInfo

data class SourceItem(val media: MediaInfo, val probing: Boolean)

enum class ConvertMode { Video, Audio }

data class WizardSession(
    val sources: List<SourceItem>,
    val preset: String,
    val quality: String,
    val size: String,
    val output: OutputTarget,
)

fun defaultVideoSession(): WizardSession = WizardSession(
    sources = emptyList(),
    preset = SessionStore.DEFAULT_PRESET,
    quality = SessionStore.DEFAULT_QUALITY,
    size = "original",
    output = OutputTarget(OutputTarget.Kind.Downloads),
)

fun defaultAudioSession(): WizardSession = WizardSession(
    sources = emptyList(),
    preset = "audio-mp3",
    quality = SessionStore.DEFAULT_QUALITY,
    size = "original",
    output = OutputTarget(OutputTarget.Kind.Music),
)

fun sessionFor(video: WizardSession, audio: WizardSession, mode: ConvertMode): WizardSession =
    when (mode) {
        ConvertMode.Video -> video
        ConvertMode.Audio -> audio
    }

fun replaceSession(
    video: WizardSession,
    audio: WizardSession,
    mode: ConvertMode,
    session: WizardSession,
): Pair<WizardSession, WizardSession> = when (mode) {
    ConvertMode.Video -> session to audio
    ConvertMode.Audio -> video to session
}

fun restrictAudioSource(media: MediaInfo): MediaInfo =
    if (media.audioCodec.isNullOrBlank()) {
        media.copy(importable = false, error = "没有音频流，无法导出音频")
    } else {
        media
    }
