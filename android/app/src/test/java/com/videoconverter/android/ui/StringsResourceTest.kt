package com.videoconverter.android.ui

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.videoconverter.android.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class StringsResourceTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun defaultEnglishAndZhCn() {
        assertEquals("LiteTrans", app.getString(R.string.app_name))
        assertEquals("History", app.getString(R.string.tab_history))
        val zh = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("zh-CN"))
            },
        )
        assertEquals("历史记录", zh.getString(R.string.tab_history))
        val fr = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply {
                setLocale(Locale.FRANCE)
            },
        )
        assertEquals("History", fr.getString(R.string.tab_history))
    }

    @Test
    fun requiredCatalogMatchesBrief() {
        val zh = zhCn()
        val expected = listOf(
            Triple(R.string.app_name, "LiteTrans", "LiteTrans"),
            Triple(R.string.tab_transcode, "Video", "视频转码"),
            Triple(R.string.tab_audio, "Audio", "音频转换"),
            Triple(R.string.tab_document, "Documents", "文档"),
            Triple(R.string.tab_history, "History", "历史记录"),
            Triple(R.string.tab_mine, "Me", "我的"),
            Triple(R.string.mine_lan, "LAN access", "局域网访问"),
            Triple(R.string.mine_language, "Language", "语言"),
            Triple(R.string.mine_privacy, "Privacy", "隐私协议"),
            Triple(R.string.mine_terms, "Terms", "使用条款"),
            Triple(R.string.mine_about, "About", "关于"),
            Triple(R.string.language_follow_system, "System default", "跟随系统"),
            Triple(R.string.language_zh_cn, "简体中文", "简体中文"),
            Triple(R.string.language_zh_tw, "繁體中文", "繁體中文"),
            Triple(R.string.language_en, "English", "English"),
            Triple(R.string.language_ja, "日本語", "日本語"),
            Triple(R.string.language_ko, "한국어", "한국어"),
            Triple(R.string.history_empty_video, "No video history yet", "还没有视频记录"),
            Triple(R.string.history_empty_audio, "No audio history yet", "还没有音频记录"),
            Triple(R.string.history_empty_document, "No document history yet", "还没有文档记录"),
            Triple(R.string.status_queued, "Queued", "排队中"),
            Triple(R.string.status_running, "Converting", "正在转码"),
            Triple(R.string.status_completed, "Done", "已完成"),
            Triple(R.string.status_failed, "Failed", "出错了"),
            Triple(R.string.status_cancelled, "Cancelled", "已取消"),
            Triple(R.string.lan_notify_on, "LAN access is on", "局域网访问已开启"),
            Triple(R.string.lan_notify_stop, "Turn off", "关闭"),
            Triple(R.string.lan_notify_channel, "LAN access", "局域网访问"),
            Triple(R.string.lan_need_token, "Password required", "需要正确口令"),
            Triple(R.string.lan_download, "Download", "下载"),
            Triple(R.string.lan_segment_video, "Video", "视频"),
            Triple(R.string.lan_segment_audio, "Audio", "音频"),
            Triple(R.string.lan_segment_document, "Documents", "文档"),
            Triple(
                R.string.lan_open_warning,
                "Anyone on this network who has the address can view history and download finished files.",
                "同一网络中知道此地址的设备可以查看记录并下载已完成文件。",
            ),
            Triple(R.string.notify_transcode_channel, "Conversion progress", "转码进度"),
            Triple(R.string.notify_checking_queue, "Checking the queue", "正在检查转码队列"),
            Triple(R.string.error_interrupted, "Conversion was interrupted", "转码被中断"),
        )
        for ((id, en, zhCn) in expected) {
            assertEquals(en, app.getString(id))
            assertEquals(zhCn, zh.getString(id))
        }
    }

    @Test
    fun legalBodiesExistInBothLocales() {
        val zh = zhCn()
        val privacyEn = app.getString(R.string.privacy_body)
        val privacyZh = zh.getString(R.string.privacy_body)
        assertTrue(privacyEn.contains("not uploaded") || privacyEn.contains("do not upload"))
        assertTrue(privacyZh.contains("不会上传"))
        assertTrue(privacyZh.contains("不要求联网才能转码"))
        assertTrue(privacyZh.contains("局域网访问"))

        val termsEn = app.getString(R.string.terms_body)
        val termsZh = zh.getString(R.string.terms_body)
        assertTrue(termsEn.contains("history") || termsEn.contains("History"))
        assertTrue(termsZh.contains("历史记录"))

        val aboutEn = app.getString(R.string.about_body, "0.1.0")
        val aboutZh = zh.getString(R.string.about_body, "0.1.0")
        assertTrue(aboutEn.contains("LiteTrans"))
        assertTrue(aboutEn.contains("0.1.0"))
        assertTrue(aboutZh.contains("LiteTrans"))
        assertTrue(aboutZh.contains("0.1.0"))
        assertTrue(aboutZh.contains("不上传"))
    }

    @Test
    fun userFacingKeysResolveInBothLocales() {
        val zh = zhCn()
        val ids = intArrayOf(
            R.string.action_cancel,
            R.string.action_open,
            R.string.action_share,
            R.string.action_rename,
            R.string.action_delete,
            R.string.action_retry,
            R.string.action_back,
            R.string.action_previous,
            R.string.action_next,
            R.string.action_save,
            R.string.action_copy,
            R.string.action_copied,
            R.string.action_got_it,
            R.string.action_remove,
            R.string.action_more,
            R.string.action_collapse,
            R.string.wizard_step_sources,
            R.string.wizard_step_format,
            R.string.wizard_step_output,
            R.string.wizard_title_sources,
            R.string.wizard_title_format,
            R.string.wizard_title_output,
            R.string.wizard_start_transcode,
            R.string.wizard_start_convert,
            R.string.wizard_joining_queue,
            R.string.wizard_converting,
            R.string.wizard_more_formats,
            R.string.wizard_less_formats,
            R.string.wizard_add_audio,
            R.string.wizard_add_document,
            R.string.wizard_add_video,
            R.string.wizard_add_audio_hint,
            R.string.wizard_add_document_hint,
            R.string.wizard_add_video_hint,
            R.string.wizard_source_music,
            R.string.wizard_source_music_hint,
            R.string.wizard_source_gallery,
            R.string.wizard_source_gallery_hint_video,
            R.string.wizard_source_gallery_hint_image,
            R.string.wizard_source_files,
            R.string.wizard_source_files_hint,
            R.string.wizard_need_document,
            R.string.wizard_need_audio,
            R.string.wizard_need_video,
            R.string.wizard_selected_count,
            R.string.wizard_need_video_then_format,
            R.string.wizard_need_video_then_start,
            R.string.wizard_convert_files,
            R.string.wizard_convert_videos,
            R.string.wizard_convert_videos_quality,
            R.string.wizard_save_to,
            R.string.wizard_source_kinds,
            R.string.wizard_reading_format,
            R.string.wizard_file_unreadable,
            R.string.wizard_pages,
            R.string.wizard_page_range,
            R.string.wizard_image,
            R.string.wizard_trimmed,
            R.string.wizard_kind_video,
            R.string.wizard_preview_video,
            R.string.wizard_preview_document,
            R.string.wizard_trim_clock,
            R.string.wizard_files_trimmed,
            R.string.wizard_files_paged,
            R.string.wizard_result_images,
            R.string.wizard_result_pdfs,
            R.string.output_gallery,
            R.string.output_movies,
            R.string.output_downloads,
            R.string.output_music,
            R.string.output_documents,
            R.string.output_custom,
            R.string.output_selected_folder,
            R.string.output_app_dir,
            R.string.output_gallery_hint,
            R.string.output_movies_hint,
            R.string.output_downloads_hint,
            R.string.output_custom_hint,
            R.string.output_music_hint,
            R.string.output_documents_hint,
            R.string.quality_original,
            R.string.quality_standard,
            R.string.quality_small,
            R.string.quality_high,
            R.string.quality_smaller,
            R.string.quality_original_hint,
            R.string.quality_standard_hint,
            R.string.quality_small_hint,
            R.string.quality_high_hint,
            R.string.quality_smaller_hint,
            R.string.size_original,
            R.string.size_original_hint,
            R.string.size_1080p_hint,
            R.string.size_720p_hint,
            R.string.size_480p_hint,
            R.string.format_best_compat,
            R.string.format_lossless,
            R.string.format_smaller,
            R.string.format_image_title,
            R.string.format_compress_title,
            R.string.quality_audio_title,
            R.string.quality_video_title,
            R.string.resolution_title,
            R.string.history_subtitle,
            R.string.history_empty_hint,
            R.string.history_rename_title,
            R.string.history_rename_hint,
            R.string.untitled,
            R.string.lan_token_label,
            R.string.lan_token_placeholder,
            R.string.lan_token_empty_hint,
            R.string.lan_need_wifi,
            R.string.lan_ports_busy,
            R.string.lan_download_named,
            R.string.lan_download_index,
            R.string.trim_title,
            R.string.trim_hint,
            R.string.trim_reset,
            R.string.trim_no_preview,
            R.string.trim_pause,
            R.string.trim_play_selection,
            R.string.trim_set_start,
            R.string.trim_set_end,
            R.string.trim_start,
            R.string.trim_current,
            R.string.trim_end,
            R.string.trim_keep,
            R.string.document_page_range,
            R.string.document_start_page,
            R.string.document_end_page,
            R.string.document_total_pages,
            R.string.document_preview,
            R.string.document_preview_failed,
            R.string.duration_seconds,
            R.string.duration_minutes,
            R.string.duration_min_sec,
            R.string.duration_hour_min,
            R.string.preset_badge_common,
            R.string.preset_badge_fastest,
            R.string.preset_mp4_h264_desc,
            R.string.preset_mp4_copy_title,
            R.string.preset_mp4_copy_desc,
            R.string.preset_mp4_h265_desc,
            R.string.preset_mov_h264_desc,
            R.string.preset_mkv_copy_friendly_desc,
            R.string.preset_mkv_h265_desc,
            R.string.preset_webm_vp9_desc,
            R.string.preset_avi_mpeg4_desc,
            R.string.preset_gif_desc,
            R.string.preset_audio_mp3_desc,
            R.string.preset_audio_aac_desc,
            R.string.preset_audio_mp3_audio_desc,
            R.string.preset_audio_aac_audio_desc,
            R.string.preset_audio_wav_desc,
            R.string.preset_audio_flac_desc,
            R.string.preset_audio_ogg_desc,
            R.string.preset_audio_amr_desc,
            R.string.preset_image_jpg_desc,
            R.string.preset_image_png_desc,
            R.string.preset_image_webp_desc,
            R.string.preset_image_bmp_desc,
            R.string.preset_image_gif_desc,
            R.string.preset_image_compress_title,
            R.string.preset_image_compress_desc,
            R.string.preset_pdf_image_title,
            R.string.preset_pdf_image_desc,
            R.string.preset_pdf_txt_title,
            R.string.preset_pdf_txt_desc,
            R.string.preset_pdf_compress_title,
            R.string.preset_pdf_compress_desc,
            R.string.preset_pdf_split_title,
            R.string.preset_pdf_split_desc,
            R.string.preset_office_pdf_title,
            R.string.preset_office_pdf_desc,
            R.string.preset_mp4_h264_catalog_desc,
            R.string.preset_mp4_copy_catalog_desc,
            R.string.preset_webm_vp9_catalog_desc,
            R.string.preset_mkv_copy_friendly_catalog_desc,
            R.string.preset_mov_h264_catalog_desc,
            R.string.preset_avi_mpeg4_catalog_desc,
            R.string.preset_audio_mp3_catalog_desc,
            R.string.preset_audio_aac_catalog_desc,
            R.string.preset_audio_wav_catalog_desc,
            R.string.preset_audio_flac_catalog_desc,
            R.string.preset_audio_ogg_catalog_desc,
            R.string.hint_office_layout,
            R.string.hint_scan_no_text,
            R.string.hint_flac,
            R.string.hint_wav,
            R.string.hint_copy_mp4,
            R.string.hint_amr,
            R.string.error_no_audio_stream,
            R.string.error_cannot_read_media,
            R.string.error_wait_probe,
            R.string.error_cannot_create_job,
            R.string.error_invalid_filename,
            R.string.error_cannot_rename,
            R.string.error_mixed_document_types,
            R.string.error_unsupported_format,
            R.string.error_cannot_read_pages,
            R.string.error_cannot_transcode,
            R.string.error_select_output,
            R.string.error_add_audio_first,
            R.string.error_add_document_first,
            R.string.error_add_video_first,
            R.string.error_no_audio_for_export,
            R.string.error_no_video_for_gif,
            R.string.error_no_video_for_copy,
            R.string.error_container_video_codec,
            R.string.error_copy_cannot_change_video,
            R.string.error_container_audio_codec,
            R.string.error_unknown_preset,
            R.string.error_ffmpeg_validate,
            R.string.error_no_av_stream,
            R.string.error_cannot_parse_media,
            R.string.error_no_extractable_text,
            R.string.error_cancelled,
            R.string.error_encrypted_pdf,
            R.string.error_cannot_convert_document,
            R.string.error_convert_failed,
            R.string.error_cannot_read_image,
            R.string.error_job_already_running,
            R.string.error_ffmpeg_failed,
            R.string.error_transcode_failed,
            R.string.error_cannot_write_output_reselect,
            R.string.error_duplicate_name,
            R.string.error_cache_full,
            R.string.error_cannot_read_source,
        )
        for (id in ids) {
            val en = app.getString(id)
            val zhCn = zh.getString(id)
            assertTrue(en.isNotBlank())
            assertTrue(zhCn.isNotBlank())
        }
        assertEquals("Cancel", app.getString(R.string.action_cancel))
        assertEquals("取消", zh.getString(R.string.action_cancel))
        assertEquals("Open", app.getString(R.string.action_open))
        assertEquals("打开", zh.getString(R.string.action_open))
        assertEquals("Share", app.getString(R.string.action_share))
        assertEquals("分享", zh.getString(R.string.action_share))
        assertEquals("Add files", app.getString(R.string.wizard_title_sources))
        assertEquals("添加文件", zh.getString(R.string.wizard_title_sources))
    }

    @Test
    fun traditionalJapaneseAndKoreanLocales() {
        val tw = locale("zh-TW")
        val hk = locale("zh-HK")
        val ja = locale("ja")
        val ko = locale("ko")

        val twHistory = tw.getString(R.string.tab_history)
        assertTrue(twHistory.contains("歷") || twHistory == "歷史記錄")
        assertEquals(twHistory, hk.getString(R.string.tab_history))

        val jaMine = ja.getString(R.string.tab_mine)
        assertTrue(jaMine.contains("マイ") || jaMine.contains("設定"))
        assertTrue(jaMine != "Me")

        val koHistory = ko.getString(R.string.tab_history)
        assertTrue(koHistory.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HANGUL_SYLLABLES })
        assertTrue(koHistory != "History")
        assertTrue(koHistory != "历史记录")

        val twFiles = tw.getString(R.string.wizard_source_files)
        val twFolders = tw.getString(R.string.wizard_source_files_hint)
        val twConvert = tw.getString(R.string.wizard_start_transcode)
        assertTrue(twFiles.contains("檔案"))
        assertTrue(twFolders.contains("資料夾"))
        assertTrue(twConvert.contains("轉檔"))

        for (ctx in listOf(tw, hk, ja, ko)) {
            assertEquals("LiteTrans", ctx.getString(R.string.app_name))
            assertEquals("English", ctx.getString(R.string.language_en))
            assertEquals("简体中文", ctx.getString(R.string.language_zh_cn))
            assertEquals("繁體中文", ctx.getString(R.string.language_zh_tw))
            assertEquals("日本語", ctx.getString(R.string.language_ja))
            assertEquals("한국어", ctx.getString(R.string.language_ko))
        }
    }

    private fun zhCn() = locale("zh-CN")

    private fun locale(tag: String) = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        },
    )
}
