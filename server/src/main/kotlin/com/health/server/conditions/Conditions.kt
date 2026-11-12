package com.health.server.conditions

import com.health.api.FieldChoices
import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.modules.UserSettingsStore

/**
 * The conditions and goals each person picks (kept sealed), the features they turn on, and which
 * fields each feature shows: the defaults, plus what the conditions bring, plus what the person
 * shows by hand, less what they hide.
 */
class ConditionsService(private val settings: UserSettingsStore, private val profiles: ProfileStore) {
    suspend fun get(userId: String): MyConditions = settings.get(userId, KEY, MyConditions.serializer()) ?: MyConditions()

    /** Saves the list, and turns on the features it needs (never off: the person does that). */
    suspend fun put(userId: String, input: MyConditions): MyConditions {
        if (input.conditions.size > 80) throw ApiError.badRequest("Up to 80 conditions and goals")
        val clean = input.conditions.map { c -> clean(c) }.distinctBy { it.id }
        val saved = settings.put(userId, KEY, MyConditions(clean), MyConditions.serializer())
        val needed = clean.flatMap { modulesOf(it) }.toSet()
        val profile = profiles.get(userId)
        if (!profile.modules.containsAll(needed)) profiles.put(userId, profile.copy(modules = profile.modules + needed))
        return saved
    }

    suspend fun choices(userId: String): FieldChoices = settings.get(userId, FIELDS_KEY, FieldChoices.serializer()) ?: FieldChoices()

    suspend fun putChoices(userId: String, input: FieldChoices): FieldLayout {
        val unknown = (input.show + input.hide).filter { it !in Fields.ALL }
        if (unknown.isNotEmpty()) throw ApiError.badRequest("Unknown fields: ${unknown.take(5).joinToString()}")
        // A field shown and hidden at once is hidden.
        settings.put(userId, FIELDS_KEY, FieldChoices(input.show - input.hide, input.hide), FieldChoices.serializer())
        return layout(userId)
    }

    suspend fun layout(userId: String): FieldLayout = layout(get(userId), choices(userId))

    /** Whether [field] shows for this person. */
    suspend fun shows(userId: String, field: String): Boolean = field in layout(userId).effective

    private fun clean(c: MyCondition): MyCondition {
        val known = ConditionCatalog.byId(c.id)
        if (known == null && !c.id.startsWith("custom:")) throw ApiError.badRequest("No condition called ${c.id.take(60)}")
        val name = (known?.name ?: c.name).trim().take(80)
        if (name.isEmpty()) throw ApiError.badRequest("Give it a name")
        val unknownFields = c.fields.filter { it !in Fields.ALL }
        if (unknownFields.isNotEmpty()) throw ApiError.badRequest("Unknown fields: ${unknownFields.take(5).joinToString()}")
        c.since?.let { if (!Regex("""\d{4}-\d{2}(-\d{2})?""").matches(it)) throw ApiError.badRequest("Since looks like 2024-05 or 2024-05-31") }
        return c.copy(
            id = c.id.trim().take(80),
            name = name,
            kind = known?.kind ?: c.kind,
            notes = c.notes?.trim()?.take(2000)?.ifBlank { null },
            // A catalog entry's features and fields come from the catalog; a custom one's from the person.
            modules = if (known != null) emptySet() else c.modules,
            fields = if (known != null) emptySet() else c.fields,
        )
    }

    companion object {
        const val KEY = "conditions"
        const val FIELDS_KEY = "fields"

        fun modulesOf(c: MyCondition): Set<Module> = ConditionCatalog.byId(c.id)?.modules ?: c.modules
        fun fieldsOf(c: MyCondition): Set<String> = ConditionCatalog.byId(c.id)?.fields ?: c.fields

        /** The layout, worked out: pure, for tests. */
        fun layout(conditions: MyConditions, choices: FieldChoices): FieldLayout {
            val because = linkedMapOf<String, String>()
            Fields.DEFAULTS.forEach { because[it] = "default" }
            conditions.conditions.forEach { c -> fieldsOf(c).forEach { f -> because.putIfAbsent(f, c.name) } }
            choices.show.forEach { because[it] = "you" }
            choices.hide.forEach { because.remove(it) }
            return FieldLayout(effective = because.keys.toSet(), show = choices.show, hide = choices.hide, because = because)
        }
    }
}
