package com.eacape.speccodingplugin.rollback

import com.intellij.util.messages.Topic

data class ChangesetChangedEvent(
    val action: Action,
    val changesetId: String? = null,
) {
    enum class Action {
        SAVED,
        DELETED,
        CLEARED,
    }
}

interface ChangesetChangedListener {
    fun onChanged(event: ChangesetChangedEvent) {}

    companion object {
        val TOPIC: Topic<ChangesetChangedListener> = Topic.create(
            "SpecCoding.ChangesetChanged",
            ChangesetChangedListener::class.java,
        )
    }
}
