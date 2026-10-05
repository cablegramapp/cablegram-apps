package app.cablegram.phone

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.maestroRoot(): Modifier = semantics { testTagsAsResourceId = true }

fun Modifier.maestro(id: String): Modifier = testTag(id)

object MaestroIds {
    const val NAV_LIBRARY = "nav_library"
    const val NAV_BROWSE = "nav_browse"
    const val NAV_STORAGE = "nav_storage"
    const val NAV_REMOTE = "nav_remote"
    const val NAV_SETTINGS = "nav_settings"
    const val LIBRARY_TITLE = "library_title"
    const val LIBRARY_SEARCH = "library_search"
    const val BROWSE_TITLE = "browse_title"
    const val BROWSE_OPEN_FOLDER = "browse_open_folder"
    const val BROWSE_PHONE = "browse_phone"
    const val TELEGRAM_ENTRY = "telegram_entry"
    const val TELEGRAM_CONNECT = "telegram_connect"
    const val TELEGRAM_DISCONNECT = "telegram_disconnect"
    const val TELEGRAM_SHEET = "telegram_sheet"
    const val TELEGRAM_CONTINUE = "telegram_continue"
    const val TELEGRAM_OPEN = "telegram_open"
    const val TELEGRAM_RECREATE = "telegram_recreate"
    const val TELEGRAM_INPUT = "telegram_input"
    const val TELEGRAM_SUBMIT = "telegram_submit"
    const val BROWSE_FILTER_VIDEOS = "browse_filter_videos"
    const val BROWSE_FILTER_ALL = "browse_filter_all"
    const val BROWSE_BACK = "browse_back"
    const val BROWSE_ADD_FILES = "browse_add_files"
    const val PREPARE_CARD = "prepare_card"
    const val TITLE_PROMPT_FIELD = "title_prompt_field"
    const val TITLE_PROMPT_AI = "title_prompt_ai"
    const val TITLE_PROMPT_CONFIRM = "title_prompt_confirm"
    const val STORAGE_TITLE = "storage_title"
    const val STORAGE_MANAGE = "storage_manage_cloud"
    const val SETTINGS_TITLE = "settings_title"
    const val SETTINGS_ADD_TV = "settings_add_tv"
    const val SETTINGS_SIGN_OUT = "settings_sign_out"
    const val PAIRING_NOT_NOW = "pairing_not_now"
    const val PAIRING_ENTER_PIN = "pairing_enter_pin"
    const val DETAIL_BACK = "detail_back"
    const val DETAIL_SAVE_CLOUD = "detail_save_to_cloud"
    const val DETAIL_FIND_SUBTITLES = "detail_find_subtitles"
    const val SUBTITLES_BACK = "subtitles_back"
    const val SUBTITLES_SEARCHING = "subtitles_searching"
    const val SUBTITLES_USE = "subtitles_use"
    const val SUBTITLES_SEEK = "subtitles_seek"
    const val SUBTITLES_SKIP = "subtitles_skip"
    const val SUBTITLES_OTHERS = "subtitles_others"
    const val DETAIL_PLAY_TV = "detail_play_on_tv"
    const val CLOUD_CONTINUE = "cloud_continue"
    const val CLOUD_CONFIRM = "cloud_save_confirm"
    const val CLOUD_CLOSE = "cloud_close"
    const val CLOUD_CABLEGRAM = "cloud_cablegram_plan"
    const val HOUSEHOLD_NAME = "household_name"
    const val HOUSEHOLD_CONTINUE = "household_continue"
}
