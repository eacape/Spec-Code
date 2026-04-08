package demo.todo

data class TodoItem(
    val title: String,
    val done: Boolean = false,
    val overdue: Boolean = false,
)

class TodoFormatter {
    fun labelFor(item: TodoItem): String {
        val status = if (item.done) "[x]" else "[ ]"
        return "$status ${item.title}"
    }
}
