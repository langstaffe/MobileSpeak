package dev.mobilespeak.mobilespeak

internal class BookmarkTransactions(
    private val read: () -> List<Bookmark>,
    private val save: (List<Bookmark>) -> Unit,
    private val publish: (List<Bookmark>) -> Unit,
) {
    @Synchronized
    fun <T> change(operation: (List<Bookmark>) -> Pair<List<Bookmark>, T>): T {
        val latest = read()
        val (next, result) = operation(latest)
        if (next != latest) {
            save(next)
            publish(next)
        }
        return result
    }
}
