package com.health.app.ui.modules.meds

import com.health.api.DoseLog
import com.health.app.Container
import com.health.app.attempt
import kotlinx.coroutines.launch

/**
 * Marking a dose, the same wherever it's done (Today, Care, +, Medications): it's sent, then the
 * message that says so offers Undo, so a tap too many can be taken back. On the app's scope, so
 * leaving the screen can't stop it halfway.
 */
object Doses {
    fun mark(c: Container, log: DoseLog, message: String) {
        c.scope.launch {
            attempt { c.ui.logDose(log) }
                .onSuccess { saved ->
                    c.dataChanged()
                    val id = saved.id
                    if (id == null) c.tell(message) else c.tell(message, "Undo") { undo(c, id) }
                }
                .onFailure { c.tell(it.message ?: "That didn't save") }
        }
    }

    /** Takes a mark back off (Undo, or Remove in the history). */
    fun undo(c: Container, id: String, message: String = "Undone") {
        c.scope.launch {
            attempt { c.ui.deleteDose(id) }
                .onSuccess {
                    c.dataChanged()
                    c.tell(message)
                }
                .onFailure { c.tell(it.message ?: "Couldn't undo that") }
        }
    }
}
