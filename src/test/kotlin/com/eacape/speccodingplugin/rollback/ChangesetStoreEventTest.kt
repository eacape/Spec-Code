package com.eacape.speccodingplugin.rollback

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ChangesetStoreEventTest {

    @Test
    fun `save delete and clear should publish changeset events`() {
        val tempRoot = Files.createTempDirectory("changeset-store-event-")
        try {
            val events = mutableListOf<ChangesetChangedEvent>()
            val publisher = object : ChangesetChangedListener {
                override fun onChanged(event: ChangesetChangedEvent) {
                    events += event
                }
            }
            val messageBus = mockk<MessageBus>(relaxed = true)
            every { messageBus.syncPublisher(ChangesetChangedListener.TOPIC) } returns publisher

            val project = mockk<Project>(relaxed = true)
            every { project.basePath } returns tempRoot.toString()
            every { project.messageBus } returns messageBus

            val store = ChangesetStore(project)
            val c1 = changeset("c1")
            val c2 = changeset("c2")

            store.save(c1)
            store.delete("c1")
            store.save(c2)
            store.clear()

            assertEquals(
                listOf(
                    ChangesetChangedEvent.Action.SAVED,
                    ChangesetChangedEvent.Action.DELETED,
                    ChangesetChangedEvent.Action.SAVED,
                    ChangesetChangedEvent.Action.CLEARED,
                ),
                events.map { it.action },
            )
            assertEquals("c1", events[0].changesetId)
            assertEquals("c1", events[1].changesetId)
            assertEquals("c2", events[2].changesetId)
            assertEquals(null, events[3].changesetId)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun changeset(id: String): Changeset {
        return Changeset(
            id = id,
            description = "test-$id",
            changes = emptyList(),
        )
    }
}
