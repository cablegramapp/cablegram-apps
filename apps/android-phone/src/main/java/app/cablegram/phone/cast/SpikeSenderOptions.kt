package app.cablegram.phone.cast

import android.content.Context
import app.cablegram.phone.BuildConfig
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class SpikeSenderOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(BuildConfig.CABLEGRAM_CAST_APP_ID)
        .setLaunchOptions(LaunchOptions.Builder().setAndroidReceiverCompatible(true).build())
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
