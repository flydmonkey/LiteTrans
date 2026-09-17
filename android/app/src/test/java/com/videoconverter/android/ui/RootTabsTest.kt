package com.videoconverter.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTabsTest {
    @Test
    fun tabLabelsMatchProductCopy() {
        assertEquals("视频转码", rootTabLabel(RootTab.Transcode))
        assertEquals("历史记录", rootTabLabel(RootTab.History))
        assertEquals("我的", rootTabLabel(RootTab.Mine))
    }

    @Test
    fun mineEntriesArePrivacyTermsAndAbout() {
        assertEquals(
            listOf(MinePage.Privacy, MinePage.Terms, MinePage.About),
            mineItems().map { it.page },
        )
        assertEquals("隐私协议", minePageTitle(MinePage.Privacy))
        assertEquals("使用条款", minePageTitle(MinePage.Terms))
        assertEquals("关于", minePageTitle(MinePage.About))
        assertEquals("我的", minePageTitle(MinePage.Root))
    }

    @Test
    fun legalCopyStaysLocalAndOffline() {
        assertTrue(minePageBody(MinePage.Privacy).contains("不会上传"))
        assertTrue(minePageBody(MinePage.Privacy).contains("不要求联网"))
        assertTrue(minePageBody(MinePage.Terms).contains("历史记录"))
        assertTrue(aboutBody("0.1.0").contains("0.1.0"))
        assertTrue(aboutBody("0.1.0").contains("不上传"))
        assertEquals("还没有转码记录", historyEmptyLabel())
    }

    @Test
    fun backConsumesMineDetailAndWizardStepsOnly() {
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Mine, MinePage.Privacy, WizardStep.Sources),
        )
        assertEquals(
            RootBack(RootTab.Transcode, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Format),
        )
        assertEquals(
            RootBack(RootTab.Transcode, MinePage.Root, WizardStep.Format),
            consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Output),
        )
        assertNull(consumeRootBack(RootTab.History, MinePage.Root, WizardStep.Sources))
        assertNull(consumeRootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources))
        assertNull(consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Sources))
    }

    @Test
    fun leavingMineResetsDetail() {
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.Privacy))
        assertEquals(MinePage.Privacy, minePageAfterLeavingTab(RootTab.Mine, MinePage.Privacy))
    }
}
