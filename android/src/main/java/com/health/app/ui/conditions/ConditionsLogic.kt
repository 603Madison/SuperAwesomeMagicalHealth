package com.health.app.ui.conditions

import com.health.api.ConditionDefinition
import com.health.api.ConditionKind
import com.health.api.FieldChoices
import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import java.text.Normalizer
import java.util.Locale

/**
 * Conditions and goals, and which fields show, in plain Kotlin: searching the catalog, picking,
 * adding one's own, and what a field's switch means for the choices sent to the server. The
 * screens only draw what these say, so it's all tested without a phone.
 */
object ConditionsLogic {
    /** Lower case, without accents or punctuation, for matching "Crohn’s" with "crohns". */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Whether [def] matches [query] by its name or any of its other names (every word somewhere). */
    fun matches(def: ConditionDefinition, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystacks = (listOf(def.name, def.group) + def.aliases).map(::fold)
        return words.all { w -> haystacks.any { it.contains(w) } }
    }

    /** The catalog's entries matching [query], in catalog order. */
    fun search(catalog: List<ConditionDefinition>, query: String): List<ConditionDefinition> = catalog.filter { matches(it, query) }

    /** Entries under their groups, the groups in the catalog's own order. */
    fun grouped(list: List<ConditionDefinition>): List<Pair<String, List<ConditionDefinition>>> =
        list.groupBy { it.group }.toList()

    /** Picks [def] (with no status yet), or takes it away if it's picked. */
    fun toggle(mine: MyConditions, def: ConditionDefinition): MyConditions =
        if (mine.conditions.any { it.id == def.id }) MyConditions(mine.conditions.filterNot { it.id == def.id })
        else MyConditions(mine.conditions + MyCondition(def.id, def.name, def.kind))

    /** Replaces the pick with the same id (or adds it). */
    fun put(mine: MyConditions, pick: MyCondition): MyConditions =
        if (mine.conditions.any { it.id == pick.id }) MyConditions(mine.conditions.map { if (it.id == pick.id) pick else it })
        else MyConditions(mine.conditions + pick)

    fun remove(mine: MyConditions, id: String): MyConditions = MyConditions(mine.conditions.filterNot { it.id == id })

    /** A new id for the person's own condition: "custom:" and its name, unique among [taken]. */
    fun customId(name: String, taken: Set<String>): String {
        val slug = fold(name).replace(' ', '-').take(60).ifEmpty { "mine" }
        var id = "custom:$slug"
        var n = 2
        while (id in taken) id = "custom:$slug-${n++}"
        return id
    }

    /** "2024-05" or "2024-05-31", as the server takes it; blank is fine (not known). */
    fun validSince(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val m = Regex("""(\d{4})-(\d{2})(?:-(\d{2}))?""").matchEntire(t) ?: return false
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        return month in 1..12 && (day == null || day in 1..31)
    }

    /** The features a pick turns on: a catalog entry's, or the person's own choice. */
    fun modulesOf(pick: MyCondition, catalog: List<ConditionDefinition>): Set<Module> =
        catalog.firstOrNull { it.id == pick.id }?.modules ?: pick.modules

    /** The fields a pick brings. */
    fun fieldsOf(pick: MyCondition, catalog: List<ConditionDefinition>): Set<String> =
        catalog.firstOrNull { it.id == pick.id }?.fields ?: pick.fields

    /** What saving [mine] would turn on that's off now, in the order features are listed. */
    fun turnsOn(mine: MyConditions, catalog: List<ConditionDefinition>, on: Set<Module>): List<Module> {
        val needed = mine.conditions.flatMap { modulesOf(it, catalog) }.toSet()
        return Module.entries.filter { it in needed && it !in on }
    }

    /** "Bladder & bowel", "Bladder & bowel and Cycle", "Mind, Cycle and Habits". */
    fun sayList(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    // ---------------------------------------------------------------- Fields

    /** Every field under its feature, the features in the order [Fields.ALL] lists them. */
    fun fieldGroups(): List<Pair<Module, List<Pair<String, String>>>> =
        Fields.ALL.entries.groupBy({ it.value.first }, { it.key to it.value.second }).toList()

    /** The fields that show without the person's own choices: the defaults and what their conditions bring. */
    fun brought(mine: MyConditions, catalog: List<ConditionDefinition>): Set<String> =
        Fields.DEFAULTS + mine.conditions.flatMap { fieldsOf(it, catalog) }

    /**
     * The choices after turning [key] on or off: only what differs from what [brought] would
     * show anyway is kept, so a condition's field turned back on still says it's for that condition.
     */
    fun choose(choices: FieldChoices, key: String, on: Boolean, brought: Set<String>): FieldChoices = if (on) {
        FieldChoices(show = if (key in brought) choices.show - key else choices.show + key, hide = choices.hide - key)
    } else {
        FieldChoices(show = choices.show - key, hide = if (key in brought) choices.hide + key else choices.hide - key)
    }

    /** Why a field shows (or doesn't), for the Fields page. */
    fun reason(layout: FieldLayout?, key: String): String {
        if (layout == null) return if (key in Fields.DEFAULTS) "On for everyone" else "Off"
        if (key !in layout.effective) return if (key in layout.hide) "You turned this off" else "Off"
        return when (val why = layout.because[key]) {
            null, "default" -> "On for everyone"
            "you" -> "You turned this on"
            else -> "Because of $why"
        }
    }

    /** How a kind is headed in the picker. */
    fun kindTitle(kind: ConditionKind): String = when (kind) {
        ConditionKind.CONDITION -> "Conditions"
        ConditionKind.GOAL -> "Goals"
        ConditionKind.LIFE -> "Life"
    }
}
