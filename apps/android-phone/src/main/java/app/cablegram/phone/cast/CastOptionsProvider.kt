package app.cablegram.phone.cast

import android.content.Context
import app.cablegram.phone.BuildConfig
import app.cablegram.phone.PhoneActivity
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(BuildConfig.CABLEGRAM_CAST_APP_ID)
        .setCastMediaOptions(CastMediaOptions.Builder()
            .setNotificationOptions(NotificationOptions.Builder()
                .setTargetActivityClassName(PhoneActivity::class.java.name)
                .setActions(listOf(MediaIntentReceiver.ACTION_REWIND,
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK, MediaIntentReceiver.ACTION_FORWARD,
                    MediaIntentReceiver.ACTION_STOP_CASTING), intArrayOf(1, 3))
                .setSkipStepMs(10_000).build()).build())
        .setLaunchOptions(LaunchOptions.Builder().setAndroidReceiverCompatible(true).build()).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
