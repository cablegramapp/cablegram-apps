package app.cablegram.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions as GoogleReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider

class CastReceiverOptions : ReceiverOptionsProvider {
    override fun getOptions(context: Context): GoogleReceiverOptions =
        GoogleReceiverOptions.Builder(context).setStatusText("Cablegram").build()
}
