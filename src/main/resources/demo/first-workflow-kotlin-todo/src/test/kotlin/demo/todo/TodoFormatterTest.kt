package demo.todo

import kotlin.test.Test
import kotlin.test.assertEquals

class TodoFormatterTest {
    private val formatter = TodoFormatter()

    @Test
    fun `formats completed items with done marker`() {
        val item = TodoItem(
            title = "Write release note",
            done = true,
            overdue = false,
        )

        assertEquals("[x] Write release note", formatter.labelFor(item))
    }
}
