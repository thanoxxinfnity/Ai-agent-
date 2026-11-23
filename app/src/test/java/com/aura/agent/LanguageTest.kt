package com.aura.agent

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Extras
import com.aura.agent.ui.AppLocale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val HI_TITLE = "आपका डेटा आपके ही फ़ोन में रहता है।"
private const val EN_TITLE = "Your data stays on your device."

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrivacyLoginUiTest : UiBase() {
    @Test fun the_privacy_box_sits_above_the_sign_in_button_and_nothing_is_cut_off() {
        assertTrue("login screen", text("Sign in with GitHub"))
        assertTrue(text(EN_TITLE))
        val box = rule.onNodeWithTag("privacy-box").getUnclippedBoundsInRoot()
        val button = rule.onNodeWithTag("login-button").getUnclippedBoundsInRoot()
        val scroller = rule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("privacy-box"))).getUnclippedBoundsInRoot()
        assertTrue("the privacy box starts (${box.top}) above the button (${button.top})", box.top < button.top)
        assertTrue("the area holding the privacy text ends (${scroller.bottom}) before the button starts (${button.top})", scroller.bottom <= button.top)
        // the sign-in button and Skip stay pinned under the scrolling text, so a small phone never cuts them off
        rule.onNodeWithTag("login-button").assertIsDisplayed()
        rule.onNodeWithText("Skip for now").assertIsDisplayed()
        // and the privacy text itself scrolls
        rule.onNodeWithTag("privacy-box").assertExists()
        rule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("privacy-box"))).assertExists()
        // all the promised text is there, with the extra honest lines
        for (t in listOf("There is no data backup.", "stored encrypted on this phone only", "never saved on our servers", "only lets the AI work with the repositories you choose", "you can disconnect at any time", "Nothing is pushed to GitHub unless you say yes.", "Chat text goes only to the AI provider you chose."))
            assertTrue(t, rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("GitHub is for pushing code only", rule.onAllNodesWithText("GitHub is connected only so the AI can push code there", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LanguageSwitchUiTest : UiBase() {
    @Test fun hindi_shows_at_once_english_comes_back_and_the_choice_survives_a_restart() {
        assertTrue(text(EN_TITLE)); assertTrue(text("Skip for now"))
        rule.runOnUiThread { vm().extras.setLanguage("hi") }; rule.waitForIdle()
        assertTrue("Hindi title", text(HI_TITLE)); assertTrue("Hindi skip", text("अभी छोड़ें")); assertTrue("Hindi button", text("GitHub से साइन इन करें"))
        assertFalse("English is gone", text(EN_TITLE) || rule.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty())
        // the exact Hindi body text
        for (t in listOf("डेटा का बैकअप नहीं होता।", "आपकी चैट, मेमोरी, API कुंजियाँ और टोकन केवल इसी फ़ोन में एन्क्रिप्टेड रूप में रखे जाते हैं। वे हमारे किसी सर्वर पर सेव नहीं होते।",
            "GitHub से जुड़ना केवल इसलिए है कि AI आपके चुने हुए रिपॉज़िटरी को पढ़ और बदल सके तथा बिल्ड/APK बना सके। AI को इससे अधिक कुछ नहीं मिलता, और आप जब चाहें Disconnect करके इसे हटा सकते हैं।",
            "GitHub पर कुछ भी तभी जाता है जब आप हाँ कहें।", "चैट का टेक्स्ट केवल उसी AI प्रदाता के पास जाता है जिसे आपने स्वयं चुना है।"))
            assertTrue(t, rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty())
        // a restart keeps Hindi
        rule.activityRule.scenario.recreate(); rule.waitForIdle()
        assertTrue("Hindi after the screen is rebuilt", text(HI_TITLE))
        assertEquals("hi", Extras(ApplicationProvider.getApplicationContext<Context>()).language.value)
        // and back to English
        rule.runOnUiThread { vm().extras.setLanguage("en") }; rule.waitForIdle()
        assertTrue(text(EN_TITLE)); assertTrue(text("Sign in with GitHub")); assertFalse(text(HI_TITLE))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LanguageSettingsUiTest : UiBase() {
    @Test fun the_language_picker_in_settings_switches_titles_and_the_chat_screen() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        assertTrue(text("What should we build today?")); assertTrue(text("Message Aura, type @ for skills"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        assertTrue(text("Settings")); assertTrue(text("Model providers"))
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("lang-hi")); rule.waitForIdle()
        assertTrue(text("English")); assertTrue(text("हिन्दी")); rule.onNodeWithTag("lang-en").assertExists()
        rule.onNodeWithTag("lang-hi").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertEquals("hi", vm().extras.language.value)
        assertTrue("settings title", text("सेटिंग्स")); assertTrue("section", text("भाषा"))
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("privacy-body")); rule.waitForIdle()
        assertTrue("short privacy text in Settings > GitHub", text("डेटा का बैकअप नहीं होता।"))
        assertTrue(text(HI_TITLE))
        rule.runOnUiThread { vm().go(Route.Chat) }; rule.waitForIdle()
        assertTrue("chat screen in Hindi", text("आज हम क्या बनाएँ?")); assertTrue(text("Aura को संदेश लिखें"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("lang-en")); rule.waitForIdle()
        rule.onNodeWithTag("lang-en").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertTrue(text("Model providers")); assertFalse(text("सेटिंग्स"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LanguageResourcesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun every_string_resolves_in_both_languages_and_is_never_blank() {
        val en = AppLocale.wrap(ctx, "en"); val hi = AppLocale.wrap(ctx, "hi")
        val ours = listOf("app_name", "login_", "skip_", "sign_", "signed_", "github_", "device_", "cancel", "privacy_", "settings_", "sec_", "add_provider", "lang_", "language_", "chat_", "connect_", "open_", "choose_", "message_", "photos_", "files", "new_chat", "search_", "recent", "rename", "delete")
        val fields = R.string::class.java.fields.filter { f -> ours.any { f.name.startsWith(it) } }
        assertTrue("found ${fields.size} strings", fields.size > 45)
        for (f in fields) {
            val id = f.getInt(null)
            assertTrue("en ${f.name}", en.getString(id).isNotBlank()); assertTrue("hi ${f.name}", hi.getString(id).isNotBlank())
        }
    }

    @Test fun a_string_missing_in_hindi_falls_back_to_english() {
        val hi = AppLocale.wrap(ctx, "hi")
        assertEquals("Aura", hi.getString(R.string.app_name))   // app_name has no Hindi entry on purpose
        assertEquals("English", hi.getString(R.string.lang_english))
        assertEquals("हिन्दी", AppLocale.wrap(ctx, "en").getString(R.string.lang_hindi))
    }

    @Test fun the_default_language_is_english_and_unknown_codes_become_english() {
        val e = Extras(ctx); e.setLanguage("en"); assertEquals("en", Extras(ctx).language.value)
        e.setLanguage("fr"); assertEquals("en", Extras(ctx).language.value)
        e.setLanguage("hi"); assertEquals("hi", Extras(ctx).language.value); e.setLanguage("en")
        assertEquals("English", AppLocale.aiName("en")); assertEquals("Hindi (Devanagari script)", AppLocale.aiName("hi"))
    }

    @Test fun the_hindi_privacy_title_matches_the_agreed_text() {
        val hi = AppLocale.wrap(ctx, "hi")
        assertEquals(HI_TITLE, hi.getString(R.string.privacy_title)); assertEquals(EN_TITLE, AppLocale.wrap(ctx, "en").getString(R.string.privacy_title))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReplyLanguageTest {
    @Test fun the_model_is_told_the_selected_language_and_that_hinglish_stays_hinglish() {
        val m = Mock().start(); val app = Env.setup(m)
        app.extras.setLanguage("en"); Env.run(app, "hello")
        assertTrue(m.mainRequests.last().contains("selected app language is English; reply in that language unless the user writes in another one (Hinglish stays Hinglish)"))
        app.extras.setLanguage("hi"); Env.run(app, "namaste")
        assertTrue(m.mainRequests.last().contains("selected app language is Hindi (Devanagari script); reply in that language unless the user writes in another one (Hinglish stays Hinglish)"))
        app.extras.setLanguage("en")
    }
}
