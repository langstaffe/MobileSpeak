package dev.mobilespeak.mobilespeak

// Owned by coreExecutor. Null output IDs are accepted only before the first identified request.
internal class ConnectionGate {
    var id: String? = null
        private set
    private var cancelled = false
    fun begin(id: String) { this.id = id; cancelled = false }
    fun cancel() { cancelled = true }
    fun accepts(outputId: String?, status: String? = null): Boolean =
        outputId == id && (!cancelled || status == "disconnected")
}
