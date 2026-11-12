package com.health.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.health.api.Fields

/**
 * The fields to show in forms: what the person's conditions bring, plus what they've turned on,
 * less what they've hidden (GET /api/fields), or what's shared while viewing someone's data.
 * Before it loads, the defaults. Use the keys in [Fields], e.g. `Fields.CONTINENCE_URGENCY`.
 */
@Composable
fun shownFields(): Set<String> {
    val layout by appContainer().fields.collectAsState()
    return layout?.effective ?: Fields.DEFAULTS
}

/** Whether a form shows [field] (a key in [Fields]). */
@Composable
fun showsField(field: String): Boolean = field in shownFields()
