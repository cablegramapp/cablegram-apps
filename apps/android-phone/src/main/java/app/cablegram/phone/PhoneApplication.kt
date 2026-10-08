package app.cablegram.phone

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow

class PhoneApplication : Application() {
    internal val foreground = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    foreground.value = true
                    if (PairingStore(this@PhoneApplication).tvs.isNotEmpty()) LanLibraryService.start(this@PhoneApplication)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) foreground.value = false
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
