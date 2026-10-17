package app.cablegram.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** Resolve tags before comparison so regional variants of one translation agree. */
internal fun tvUiLanguage(
    profileLanguages: List<String>,
    selectedLanguage: String?,
    supportedLanguages: Set<String> = setOf("en"),
): String {
    fun base(tag: String) = Locale.forLanguageTag(tag).language.takeIf { it in supportedLanguages } ?: "en"
    if (selectedLanguage != null) return base(selectedLanguage)
    return profileLanguages.map(::base).distinct().singleOrNull() ?: "en"
}

/** Scope resources to this composition; never change the process-wide default locale. */
@Composable
fun TvLanguage(languageTag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val deviceConfiguration = LocalConfiguration.current
    val configuration = remember(languageTag, deviceConfiguration) {
        Configuration(deviceConfiguration).apply { setLocales(LocaleList.forLanguageTags(languageTag)) }
    }
    // LocalActivity and player key handling must still resolve the hosting Activity.
    val context = remember(base, configuration) {
        android.view.ContextThemeWrapper(base, base.theme).apply { applyOverrideConfiguration(configuration) }
    }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides if (configuration.layoutDirection == 1) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

/** Numeric keypads and transport controls retain physical left/right ordering in RTL locales. */
@Composable
internal fun LtrControls(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
