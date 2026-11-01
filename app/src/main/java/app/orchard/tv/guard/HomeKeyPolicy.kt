package app.orchard.tv.guard

/** On modern Android, opt-in Home capture must not filter navigation in another app. */
internal object HomeKeyPolicy {
    fun wantsFilter(sdk: Int, aerialInFront: Boolean, captureHome: Boolean, stockPassThrough: Boolean,
        aerialResumed: Boolean = false): Boolean =
        !stockPassThrough && (sdk <= 30 || aerialInFront || captureHome && aerialResumed)
}
