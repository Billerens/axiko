package app.axiko.core

/** The last lines of a console, with marks so a caller can ask "what was printed since". */
class RingLog(private val capacity: Int) {
    private val items = ArrayDeque<String>()
    private var total = 0L

    @Synchronized
    fun add(line: String) {
        items.addLast(line)
        if (items.size > capacity) items.removeFirst()
        total++
    }

    @Synchronized
    fun mark(): Long = total

    @Synchronized
    fun since(mark: Long): List<String> {
        val count = (total - mark).coerceIn(0, items.size.toLong()).toInt()
        return items.toList().takeLast(count)
    }

    @Synchronized
    fun tail(count: Int): List<String> = items.toList().takeLast(count)

    @Synchronized
    fun clear() {
        items.clear()
    }
}
