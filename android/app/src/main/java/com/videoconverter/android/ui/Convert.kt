package com.videoconverter.android.ui

import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.SessionStore
import com.videoconverter.android.domain.MediaInfo

data class SourceItem(val media: MediaInfo, val probing: Boolean)

enum class ConvertMode { Video, Audio, Document }

data class WizardSession(
    val sources: List<SourceItem>,
    val preset: String,
    val quality: String,
    val size: String,
    val output: OutputTarget,
    val container: String? = null,
)

data class WizardSessions(
    val video: WizardSession,
    val audio: WizardSession,
    val document: WizardSession,
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

fun defaultDocumentSession(): WizardSession = WizardSession(
    sources = emptyList(),
    preset = "image-jpg",
    quality = SessionStore.DEFAULT_QUALITY,
    size = "original",
    output = OutputTarget(OutputTarget.Kind.Gallery),
    container = "jpg",
)

fun sessionFor(sessions: WizardSessions, mode: ConvertMode): WizardSession =
    when (mode) {
        ConvertMode.Video -> sessions.video
        ConvertMode.Audio -> sessions.audio
        ConvertMode.Document -> sessions.document
    }

fun replaceSession(
    sessions: WizardSessions,
    mode: ConvertMode,
    session: WizardSession,
): WizardSessions = when (mode) {
    ConvertMode.Video -> sessions.copy(video = session)
    ConvertMode.Audio -> sessions.copy(audio = session)
    ConvertMode.Document -> sessions.copy(document = session)
}

fun restrictAudioSource(media: MediaInfo): MediaInfo =
    when {
        !media.importable && !media.error.isNullOrBlank() -> media
        media.audioCodec.isNullOrBlank() ->
            media.copy(importable = false, error = "没有音频流，无法导出音频")
        else -> media
    }
