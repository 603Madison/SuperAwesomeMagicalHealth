package com.health.api

import kotlinx.serialization.Serializable

/*
 * Conditions and goals: each person says what they live with or want to keep track of (overactive
 * bladder, endometriosis, autism, ADHD, transition, losing weight…), and the apps show the features
 * and the fields within them that fit, and nothing else. Every field can still be shown or hidden by
 * hand in each feature's settings. Conditions are private: they're kept encrypted.
 */

@Serializable
enum class ConditionKind(val label: String) { CONDITION("Conditions"), GOAL("Goals"), LIFE("Life") }

/** One entry in the server's catalog (GET /api/conditions/catalog). */
@Serializable
data class ConditionDefinition(
    val id: String,
    val name: String,
    val kind: ConditionKind,
    /** A group to list it under: "Bladder and bowel", "Periods and hormones", "Neurodivergence"… */
    val group: String,
    val about: String,
    /** Features it turns on. */
    val modules: Set<Module> = emptySet(),
    /** Fields it shows ([Fields]). */
    val fields: Set<String> = emptySet(),
    /** Symptoms and triggers worth offering first in pickers. */
    val symptoms: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
    /** Other names people search by: "OAB", "urge incontinence". */
    val aliases: List<String> = emptyList(),
)

@Serializable
enum class ConditionStatus(val label: String) {
    DIAGNOSED("Diagnosed"), SELF_IDENTIFIED("Self-identified"), SUSPECTED("Wondering"), MANAGING("Managing"), PAST("In the past"),
}

/** A condition or goal this person has picked, from the catalog ([id]) or their own ("custom:…"). */
@Serializable
data class MyCondition(
    val id: String,
    val name: String,
    val kind: ConditionKind = ConditionKind.CONDITION,
    val status: ConditionStatus? = null,
    /** "YYYY-MM" or "YYYY-MM-DD". */
    val since: String? = null,
    val notes: String? = null,
    /** For their own: the features and fields it should turn on. */
    val modules: Set<Module> = emptySet(),
    val fields: Set<String> = emptySet(),
)

@Serializable
data class MyConditions(val conditions: List<MyCondition> = emptyList())

/**
 * Which fields show (GET /api/fields): the defaults, plus what the person's conditions bring, plus
 * what they've shown by hand ([show]), less what they've hidden ([hide]).
 */
@Serializable
data class FieldLayout(
    val effective: Set<String>,
    val show: Set<String> = emptySet(),
    val hide: Set<String> = emptySet(),
    /** Each field's reason for showing, for the settings page: "default", "you", or a condition's name. */
    val because: Map<String, String> = emptyMap(),
    /**
     * Whether amounts of food show at all (calories, macros, targets): not for people in eating disorder
     * recovery, where numbers about food can make things harder. The apps still log them, quietly.
     */
    val foodNumbers: Boolean = true,
)

/** What the person changes: PUT /api/fields. */
@Serializable
data class FieldChoices(val show: Set<String> = emptySet(), val hide: Set<String> = emptySet())

/** A field the person adds to a feature's log form themselves. */
@Serializable
data class CustomField(
    val key: String,
    val label: String,
    val type: CustomFieldType = CustomFieldType.TEXT,
    /** For CHOICE and MULTI. */
    val options: List<String> = emptyList(),
    /** For SCALE: the range (default 0–10). */
    val min: Int = 0,
    val max: Int = 10,
    val unit: String? = null,
)

@Serializable
enum class CustomFieldType { TEXT, NUMBER, SCALE, CHOICE, MULTI, YES_NO }

/** The field keys every app shares. Feature defaults are in [Fields.DEFAULTS]. */
object Fields {
    // Bladder and bowel
    const val CONTINENCE_URGENCY = "continence.urgency"
    const val CONTINENCE_VOLUME = "continence.volume"
    const val CONTINENCE_LEAK_SIZE = "continence.leakSize"
    const val CONTINENCE_TRIGGERS = "continence.triggers"
    const val CONTINENCE_ACTIVITY = "continence.activity"
    const val CONTINENCE_AWARENESS = "continence.awareness"
    const val CONTINENCE_REACHED_TOILET = "continence.reachedToilet"
    const val CONTINENCE_WARNING = "continence.warning"
    const val CONTINENCE_BRISTOL = "continence.bristol"
    const val CONTINENCE_BOWEL_ACCIDENTS = "continence.bowelAccidents"
    const val CONTINENCE_PROTECTION = "continence.protection"
    const val CONTINENCE_PAD_WEIGHT = "continence.padWeight"
    const val CONTINENCE_CHANGES = "continence.changes"
    const val CONTINENCE_SKIN = "continence.skin"
    const val CONTINENCE_UTI_SIGNS = "continence.utiSigns"
    const val CONTINENCE_CATHETER = "continence.catheter"
    const val CONTINENCE_LOCATION = "continence.location"
    const val CONTINENCE_PAIN = "continence.pain"
    const val CONTINENCE_SUPPLIES = "continence.supplies"
    const val CONTINENCE_BOTHER = "continence.bother"
    const val CONTINENCE_BLADDER_TRAINING = "continence.bladderTraining"
    const val CONTINENCE_PELVIC_FLOOR = "continence.pelvicFloor"

    // Cycle
    const val CYCLE_FLOW = "cycle.flow"
    const val CYCLE_PRODUCTS = "cycle.products"
    const val CYCLE_CLOTS = "cycle.clots"
    const val CYCLE_COLOR = "cycle.color"
    const val CYCLE_PAIN = "cycle.pain"
    const val CYCLE_PAIN_AREAS = "cycle.painAreas"
    const val CYCLE_SYMPTOM_SEVERITY = "cycle.symptomSeverity"
    const val CYCLE_MOOD = "cycle.mood"
    const val CYCLE_ENERGY = "cycle.energy"
    const val CYCLE_LIBIDO = "cycle.libido"
    const val CYCLE_SEX = "cycle.sex"
    const val CYCLE_MUCUS = "cycle.mucus"
    const val CYCLE_CERVIX = "cycle.cervix"
    const val CYCLE_OVULATION_TEST = "cycle.ovulationTest"
    const val CYCLE_LH = "cycle.lh"
    const val CYCLE_PREGNANCY_TEST = "cycle.pregnancyTest"
    const val CYCLE_BBT = "cycle.bbt"
    const val CYCLE_FERTILITY = "cycle.fertility"
    const val CYCLE_DISCHARGE = "cycle.discharge"

    // Mind
    const val MIND_ENERGY_BUDGET = "mind.energyBudget"

    // Autism and ADHD
    const val NEURO_ABOUT_ME = "neuro.aboutMe"
    const val NEURO_SENSORY = "neuro.sensory"
    const val NEURO_OVERLOAD = "neuro.overload"
    const val NEURO_BURNOUT = "neuro.burnout"
    const val NEURO_MASKING = "neuro.masking"
    const val NEURO_SOCIAL = "neuro.social"
    const val NEURO_INTERESTS = "neuro.interests"
    const val NEURO_STIMS = "neuro.stims"
    const val NEURO_FOCUS = "neuro.focus"
    const val NEURO_EXECUTIVE = "neuro.executive"
    const val NEURO_TIME = "neuro.timeBlindness"
    const val NEURO_ROUTINES = "neuro.routines"
    const val NEURO_CARDS = "neuro.cards"
    const val NEURO_CALM = "neuro.calmMode"
    const val NEURO_MED_EFFECT = "neuro.medEffect"

    // Transition
    const val TRANSITION_MILESTONES = "transition.milestones"
    const val TRANSITION_CHANGES = "transition.changes"
    const val TRANSITION_VOICE = "transition.voice"
    const val TRANSITION_HAIR = "transition.hairRemoval"
    const val TRANSITION_MOMENTS = "transition.moments"
    const val TRANSITION_MEASUREMENTS = "transition.measurements"

    // Body
    const val BODY_MEASUREMENTS = "body.measurements"

    // Food: water (and the water goal) can be left out; drinks still count caffeine and alcohol.
    const val NUTRITION_WATER = "nutrition.water"

    /** On for everyone who has the feature, until hidden. */
    val DEFAULTS: Set<String> = setOf(
        CONTINENCE_URGENCY, CONTINENCE_VOLUME, CONTINENCE_LEAK_SIZE, CONTINENCE_TRIGGERS, CONTINENCE_BRISTOL, CONTINENCE_PELVIC_FLOOR,
        CYCLE_FLOW, CYCLE_PAIN, CYCLE_SYMPTOM_SEVERITY, CYCLE_MUCUS, CYCLE_OVULATION_TEST, CYCLE_BBT, CYCLE_FERTILITY,
        NEURO_ABOUT_ME, NEURO_CALM, NEURO_CARDS,
        TRANSITION_MILESTONES, TRANSITION_CHANGES, TRANSITION_MOMENTS,
        NUTRITION_WATER,
    )

    /** Every field, for settings pages: key to its feature and label. */
    val ALL: Map<String, Pair<Module, String>> = linkedMapOf(
        CONTINENCE_URGENCY to (Module.CONTINENCE to "How urgent it was"),
        CONTINENCE_VOLUME to (Module.CONTINENCE to "How much (ml)"),
        CONTINENCE_LEAK_SIZE to (Module.CONTINENCE to "How big a leak"),
        CONTINENCE_TRIGGERS to (Module.CONTINENCE to "What set it off"),
        CONTINENCE_ACTIVITY to (Module.CONTINENCE to "What you were doing"),
        CONTINENCE_AWARENESS to (Module.CONTINENCE to "Whether you felt it"),
        CONTINENCE_REACHED_TOILET to (Module.CONTINENCE to "Whether you reached the toilet"),
        CONTINENCE_WARNING to (Module.CONTINENCE to "How much warning you had"),
        CONTINENCE_BRISTOL to (Module.CONTINENCE to "Stool type (Bristol scale)"),
        CONTINENCE_BOWEL_ACCIDENTS to (Module.CONTINENCE to "Bowel accidents"),
        CONTINENCE_PROTECTION to (Module.CONTINENCE to "What you were wearing, and how full it was"),
        CONTINENCE_PAD_WEIGHT to (Module.CONTINENCE to "Pad weight (grams)"),
        CONTINENCE_CHANGES to (Module.CONTINENCE to "Clothes or bedding changed"),
        CONTINENCE_SKIN to (Module.CONTINENCE to "Skin comfort"),
        CONTINENCE_UTI_SIGNS to (Module.CONTINENCE to "Signs of a urine infection"),
        CONTINENCE_CATHETER to (Module.CONTINENCE to "Catheter use"),
        CONTINENCE_LOCATION to (Module.CONTINENCE to "Where you were"),
        CONTINENCE_PAIN to (Module.CONTINENCE to "Pain"),
        CONTINENCE_SUPPLIES to (Module.CONTINENCE to "Supplies on hand"),
        CONTINENCE_BOTHER to (Module.CONTINENCE to "How much it bothers you (weekly)"),
        CONTINENCE_BLADDER_TRAINING to (Module.CONTINENCE to "Bladder training"),
        CONTINENCE_PELVIC_FLOOR to (Module.CONTINENCE to "Pelvic floor exercises"),
        CYCLE_FLOW to (Module.CYCLE to "Bleeding"),
        CYCLE_PRODUCTS to (Module.CYCLE to "Period products used"),
        CYCLE_CLOTS to (Module.CYCLE to "Clots"),
        CYCLE_COLOR to (Module.CYCLE to "Colour"),
        CYCLE_PAIN to (Module.CYCLE to "Pain"),
        CYCLE_PAIN_AREAS to (Module.CYCLE to "Where it hurts"),
        CYCLE_SYMPTOM_SEVERITY to (Module.CYCLE to "How bad each symptom was"),
        CYCLE_MOOD to (Module.CYCLE to "Mood"),
        CYCLE_ENERGY to (Module.CYCLE to "Energy"),
        CYCLE_LIBIDO to (Module.CYCLE to "Sex drive"),
        CYCLE_SEX to (Module.CYCLE to "Sex"),
        CYCLE_MUCUS to (Module.CYCLE to "Cervical mucus"),
        CYCLE_CERVIX to (Module.CYCLE to "Cervix position"),
        CYCLE_OVULATION_TEST to (Module.CYCLE to "Ovulation tests"),
        CYCLE_LH to (Module.CYCLE to "LH level"),
        CYCLE_PREGNANCY_TEST to (Module.CYCLE to "Pregnancy tests"),
        CYCLE_BBT to (Module.CYCLE to "Basal temperature"),
        CYCLE_FERTILITY to (Module.CYCLE to "Fertile window and ovulation"),
        CYCLE_DISCHARGE to (Module.CYCLE to "Discharge"),
        MIND_ENERGY_BUDGET to (Module.MIND to "Energy budget (spoons)"),
        NEURO_ABOUT_ME to (Module.NEURO to "About me"),
        NEURO_SENSORY to (Module.NEURO to "Sensory profile and overload"),
        NEURO_OVERLOAD to (Module.NEURO to "Meltdowns and shutdowns"),
        NEURO_BURNOUT to (Module.NEURO to "Burnout"),
        NEURO_MASKING to (Module.NEURO to "Masking"),
        NEURO_SOCIAL to (Module.NEURO to "Social battery"),
        NEURO_INTERESTS to (Module.NEURO to "Special interests"),
        NEURO_STIMS to (Module.NEURO to "Stims that help"),
        NEURO_FOCUS to (Module.NEURO to "Focus and body doubling"),
        NEURO_EXECUTIVE to (Module.NEURO to "Executive function check-in"),
        NEURO_TIME to (Module.NEURO to "Time estimates"),
        NEURO_ROUTINES to (Module.NEURO to "Routines"),
        NEURO_CARDS to (Module.NEURO to "Communication cards"),
        NEURO_CALM to (Module.NEURO to "Calm mode"),
        NEURO_MED_EFFECT to (Module.NEURO to "How medication worked"),
        TRANSITION_MILESTONES to (Module.TRANSITION to "Milestones"),
        TRANSITION_CHANGES to (Module.TRANSITION to "Changes you notice"),
        TRANSITION_VOICE to (Module.TRANSITION to "Voice training"),
        TRANSITION_HAIR to (Module.TRANSITION to "Hair removal"),
        TRANSITION_MOMENTS to (Module.TRANSITION to "Euphoria and dysphoria"),
        TRANSITION_MEASUREMENTS to (Module.TRANSITION to "Body measurements"),
        BODY_MEASUREMENTS to (Module.BODY to "Body measurements"),
        NUTRITION_WATER to (Module.NUTRITION to "Water"),
    )
}
