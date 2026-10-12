package app.cablegram

/** A ready movie waits for one signature cycle; callbacks from older attempts cannot release it. */
internal class SignaturePlaybackGate<T> {
    private var attempt: String? = null
    private var completed = false
    private var pending: T? = null

    fun begin(id: String) {
        attempt = id
        completed = false
        pending = null
    }

    fun ready(id: String, value: T): T? {
        if (id != attempt) return null
        if (completed) return value
        pending = value
        return null
    }

    fun complete(id: String): T? {
        if (id != attempt) return null
        completed = true
        return pending.also { pending = null }
    }

    fun cancel() {
        attempt = null
        pending = null
        completed = false
    }
}
