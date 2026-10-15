package io.github.lj5201lj.xianzhun

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageChoiceTest {
    @Test fun recognizesSupportedLanguageAndRegionTags() {
        assertEquals(LanguageChoice.SIMPLIFIED_CHINESE, LanguageChoice.fromTag("zh-Hans-CN"))
        assertEquals(LanguageChoice.SIMPLIFIED_CHINESE, LanguageChoice.fromTag("zh-CN"))
        assertEquals(LanguageChoice.ENGLISH, LanguageChoice.fromTag("en-US"))
        assertEquals(LanguageChoice.ENGLISH, LanguageChoice.fromTag("en-GB"))
    }

    @Test fun missingOrUnsupportedStoredPreferenceFollowsSystem() {
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.fromTag(""))
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.fromTag("not-a-language"))
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.fromTag("de-DE"))
    }
}
