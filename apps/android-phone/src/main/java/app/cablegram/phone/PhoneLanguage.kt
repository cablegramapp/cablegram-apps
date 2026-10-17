package app.cablegram.phone

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

internal fun phoneUiLanguage(preferredTags: List<String>, supported: Set<String>): String =
    preferredTags.map { Locale.forLanguageTag(it).language }.firstOrNull { it in supported } ?: "en"

/** Background notifications use the same language selection as the foreground app. */
internal fun android.content.Context.deviceLanguageResources(): android.content.res.Resources {
    val current = resources.configuration
    val locales = current.locales
    val language = phoneUiLanguage(
        (0 until locales.size()).map { locales[it].toLanguageTag() },
        resources.getStringArray(R.array.ui_languages).toSet(),
    )
    val configuration = Configuration(current).apply { setLocales(LocaleList.forLanguageTags(language)) }
    return createConfigurationContext(configuration).resources
}

@Composable
internal fun PhoneLanguage(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val deviceConfiguration = LocalConfiguration.current
    val supported = base.resources.getStringArray(R.array.ui_languages).toSet()
    val locales = deviceConfiguration.locales
    val language = phoneUiLanguage((0 until locales.size()).map { locales[it].toLanguageTag() }, supported)
    val configuration = remember(deviceConfiguration, language) {
        Configuration(deviceConfiguration).apply { setLocales(LocaleList.forLanguageTags(language)) }
    }
    // Keep the Activity in the context chain for credential prompts and activity launches.
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
