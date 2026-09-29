package app.cablegram.phone

import android.util.Log

object PairLog {
    const val TAG = "CablegramPair"

    fun pinTail(pin: String?): String {
        val value = pin.orEmpty()
        if (value.length < 3) return "pin=?"
        return "pin=…${value.takeLast(3)}"
    }

    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String, error: Throwable? = null) =
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    fun e(message: String, error: Throwable? = null) =
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
}
