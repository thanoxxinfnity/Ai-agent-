package com.aura.agent.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.MainVM
import com.aura.agent.R
import java.util.Locale

/** The app language, applied by giving the whole UI a context with that locale: it switches at once and needs no restart. */
object AppLocale {
    /** Keeps the whole context chain (so the Activity is still found, for example by the file picker) and only swaps the resources. */
    private class LocaleContext(base: Context, private val res: android.content.res.Resources) : android.content.ContextWrapper(base) {
        override fun getResources(): android.content.res.Resources = res
    }

    fun wrap(base: Context, lang: String): Context {
        val cfg = Configuration(base.resources.configuration)
        val locale = Locale(if (lang == "hi") "hi" else "en")
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return LocaleContext(base, base.createConfigurationContext(cfg).resources)
    }

    /** Name of the language for the AI ("English" or "Hindi (Devanagari script)"). */
    fun aiName(lang: String) = if (lang == "hi") "Hindi (Devanagari script)" else "English"
}

/** Gives [content] the language the user picked. Strings missing in that language fall back to English by themselves. */
@Composable
fun WithAppLanguage(vm: MainVM, content: @Composable () -> Unit) {
    val lang by vm.extras.language.collectAsState()
    val base = LocalContext.current
    val baseCfg = LocalConfiguration.current
    val localized = remember(base, lang) { AppLocale.wrap(base, lang) }
    // keep the real screen size, density and insets: only the language changes
    val cfg = remember(baseCfg, lang) { Configuration(baseCfg).also { c -> val l = Locale(if (lang == "hi") "hi" else "en"); c.setLocale(l); c.setLayoutDirection(l) } }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides cfg) { content() }
}

/** The privacy promise. Full on the login screen, shorter in Settings > GitHub. */
@Composable
fun PrivacyBox(compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(18.dp)).padding(18.dp).testTag("privacy-box"),
    ) {
        Text(stringResource(R.string.privacy_title), color = A.text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = if (compact) 17.sp else 20.sp, lineHeight = if (compact) 24.sp else 27.sp, modifier = Modifier.testTag("privacy-title"))
        Spacer(Modifier.height(8.dp))
        if (compact) {
            Text(stringResource(R.string.privacy_short), color = A.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.testTag("privacy-body"))
        } else {
            Text(stringResource(R.string.privacy_nobackup), color = A.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp, modifier = Modifier.testTag("privacy-nobackup"))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.privacy_p1), color = A.dim, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.privacy_p2), color = A.dim, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.privacy_p3), color = A.dim, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.privacy_provider), color = A.dim, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag("privacy-provider"))
        }
    }
}

/** Settings > Language. The names are never translated, so anyone can find their own language. */
@Composable
fun LanguageSection(vm: MainVM) {
    val lang by vm.extras.language.collectAsState()
    Text(stringResource(R.string.language_hint), color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    listOf("en" to R.string.lang_english, "hi" to R.string.lang_hindi).forEach { (code, label) ->
        val on = lang == code
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, if (on) A.accent else A.border, RoundedCornerShape(16.dp))
                .clickable { vm.extras.setLanguage(code) }.padding(start = 10.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag("lang-$code"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(on, { vm.extras.setLanguage(code) }, colors = RadioButtonDefaults.colors(selectedColor = A.accent, unselectedColor = A.dim))
            Text(stringResource(label), color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
