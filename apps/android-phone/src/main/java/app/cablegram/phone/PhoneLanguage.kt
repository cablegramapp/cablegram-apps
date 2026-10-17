package app.cablegram.phone

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

internal fun phoneUiLanguage(preferredTags: List<String>, supported: Set<String>): String =
    preferredTags.map { Locale.forLanguageTag(it).language }.firstOrNull { it in supported } ?: "en"

/**
 * The tag reported to the server: the device preference this app shows, with its region kept, or the
 * first preference when none is bundled, so TVs that ship more translations than this phone can use it.
 */
internal fun phoneLanguageTag(preferredTags: List<String>, supported: Set<String>): String =
    preferredTags.firstOrNull { Locale.forLanguageTag(it).language in supported }
        ?: preferredTags.firstOrNull { Locale.forLanguageTag(it).language.isNotEmpty() }
        ?: "en"

/**
 * The language the account owner's profile chose on the profile settings page. It outranks the device
 * language for this app, and is kept on disk so notifications and a cold start use it before profiles load.
 */
internal object PhoneLanguageOverride {
    private const val KEY = "language_override"
    private var prefs: android.content.SharedPreferences? = null
    var tag: String? by mutableStateOf(null)
        private set

    fun init(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences("cablegram_phone_language", android.content.Context.MODE_PRIVATE)
        tag = prefs?.getString(KEY, null)
    }

    fun set(value: String?) {
        val next = value?.takeIf { it.isNotBlank() }
        if (next == tag) return
        tag = next
        prefs?.edit()?.apply { if (next == null) remove(KEY) else putString(KEY, next) }?.apply()
    }
}

/** Language preference order for this app's own text: the profile override first, then the device list. */
internal fun appLanguageTags(override: String?, deviceTags: List<String>): List<String> =
    listOfNotNull(override) + deviceTags

internal fun Configuration.languageTags(): List<String> = (0 until locales.size()).map { locales[it].toLanguageTag() }

/** Background notifications use the same language selection as the foreground app. */
internal fun android.content.Context.deviceLanguageResources(): android.content.res.Resources {
    val current = resources.configuration
    val language = phoneUiLanguage(appLanguageTags(PhoneLanguageOverride.tag, current.languageTags()), resources.getStringArray(R.array.ui_languages).toSet())
    val configuration = Configuration(current).apply { setLocales(LocaleList.forLanguageTags(language)) }
    return createConfigurationContext(configuration).resources
}

@Composable
internal fun PhoneLanguage(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val deviceConfiguration = LocalConfiguration.current
    val supported = base.resources.getStringArray(R.array.ui_languages).toSet()
    val language = phoneUiLanguage(appLanguageTags(PhoneLanguageOverride.tag, deviceConfiguration.languageTags()), supported)
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
