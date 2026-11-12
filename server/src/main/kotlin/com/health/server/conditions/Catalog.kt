package com.health.server.conditions

import com.health.api.ConditionDefinition
import com.health.api.ConditionKind
import com.health.api.Fields
import com.health.api.Module

/**
 * The conditions and goals people can pick, and what each turns on. Plain, kind and never
 * gendered: anyone can have any of these. To add one, add an entry; the tests check that every
 * field and feature it names is real.
 */
object ConditionCatalog {
    private const val BLADDER = "Bladder and bowel"
    private const val PERIODS = "Periods and hormones"
    private const val NEURO = "Neurodivergence"
    private const val MENTAL = "Mental health"
    private const val CHRONIC = "Long-term conditions"
    private const val GOALS = "Goals"
    private const val LIFE = "Life"

    private val bladderCore = setOf(
        Fields.CONTINENCE_URGENCY, Fields.CONTINENCE_LEAK_SIZE, Fields.CONTINENCE_TRIGGERS, Fields.CONTINENCE_BOTHER, Fields.CONTINENCE_PELVIC_FLOOR,
    )
    private val protection = setOf(Fields.CONTINENCE_PROTECTION, Fields.CONTINENCE_SUPPLIES, Fields.CONTINENCE_CHANGES, Fields.CONTINENCE_SKIN)

    private fun c(
        id: String, name: String, group: String, about: String,
        modules: Set<Module>, fields: Set<String> = emptySet(),
        symptoms: List<String> = emptyList(), triggers: List<String> = emptyList(), aliases: List<String> = emptyList(),
        kind: ConditionKind = ConditionKind.CONDITION,
    ) = ConditionDefinition(id, name, kind, group, about, modules, fields, symptoms, triggers, aliases)

    val ALL: List<ConditionDefinition> = listOf(
        // ---- Bladder and bowel
        c(
            "overactive-bladder", "Overactive bladder", BLADDER,
            "Sudden urges to go, going often, and sometimes leaking before you get there.",
            setOf(Module.CONTINENCE),
            bladderCore + protection + setOf(Fields.CONTINENCE_WARNING, Fields.CONTINENCE_AWARENESS, Fields.CONTINENCE_REACHED_TOILET, Fields.CONTINENCE_BLADDER_TRAINING, Fields.CONTINENCE_VOLUME),
            triggers = listOf("urge", "on the way", "key in the door", "running water", "cold", "caffeine", "alcohol"),
            aliases = listOf("OAB", "urge incontinence", "urgency"),
        ),
        c(
            "stress-incontinence", "Stress incontinence", BLADDER,
            "Leaks when coughing, sneezing, laughing, lifting or exercising.",
            setOf(Module.CONTINENCE),
            bladderCore + protection + setOf(Fields.CONTINENCE_ACTIVITY, Fields.CONTINENCE_PAD_WEIGHT),
            triggers = listOf("cough", "sneeze", "laugh", "lifting", "exercise", "jumping", "standing up"),
            aliases = listOf("SUI", "leaking when I cough"),
        ),
        c(
            "mixed-incontinence", "Mixed incontinence", BLADDER,
            "Both: leaks with a sudden urge, and with coughs, laughs or lifting.",
            setOf(Module.CONTINENCE),
            bladderCore + protection + setOf(Fields.CONTINENCE_ACTIVITY, Fields.CONTINENCE_WARNING, Fields.CONTINENCE_AWARENESS, Fields.CONTINENCE_BLADDER_TRAINING),
            triggers = listOf("urge", "cough", "sneeze", "laugh", "lifting", "exercise"),
        ),
        c(
            "nocturia", "Night-time trips and wet nights", BLADDER,
            "Waking to go in the night, or a wet bed.",
            setOf(Module.CONTINENCE, Module.SLEEP),
            setOf(Fields.CONTINENCE_VOLUME, Fields.CONTINENCE_CHANGES, Fields.CONTINENCE_PROTECTION, Fields.CONTINENCE_SUPPLIES, Fields.CONTINENCE_BOTHER),
            triggers = listOf("drinks late", "alcohol", "caffeine", "asleep"),
            aliases = listOf("nocturia", "bedwetting", "nocturnal enuresis"),
        ),
        c(
            "retention-catheter", "Retention, neurogenic bladder or catheter use", BLADDER,
            "Trouble emptying the bladder, and emptying with a catheter.",
            setOf(Module.CONTINENCE),
            setOf(Fields.CONTINENCE_CATHETER, Fields.CONTINENCE_VOLUME, Fields.CONTINENCE_UTI_SIGNS, Fields.CONTINENCE_SUPPLIES, Fields.CONTINENCE_PAIN, Fields.CONTINENCE_LOCATION),
            symptoms = listOf("burning", "cloudy urine", "fever"),
            aliases = listOf("ISC", "self-catheterisation", "overflow", "spina bifida", "MS bladder"),
        ),
        c(
            "bowel-incontinence", "Bowel leaks", BLADDER,
            "Leaks of stool or wind, or not always being able to hold on.",
            setOf(Module.CONTINENCE),
            setOf(Fields.CONTINENCE_BOWEL_ACCIDENTS, Fields.CONTINENCE_BRISTOL, Fields.CONTINENCE_URGENCY, Fields.CONTINENCE_AWARENESS, Fields.CONTINENCE_REACHED_TOILET) + protection,
            aliases = listOf("faecal incontinence", "fecal incontinence", "accidental bowel leakage"),
        ),
        c(
            "ibs", "Irritable bowel (IBS)", BLADDER,
            "Tummy pain, bloating, and bowels that change between loose and slow.",
            setOf(Module.CONTINENCE, Module.SYMPTOMS),
            setOf(Fields.CONTINENCE_BRISTOL, Fields.CONTINENCE_URGENCY, Fields.CONTINENCE_PAIN, Fields.CONTINENCE_TRIGGERS),
            symptoms = listOf("stomach ache", "bloating", "cramps", "nausea", "fatigue"),
            triggers = listOf("stress", "certain foods", "caffeine", "alcohol", "poor sleep"),
        ),
        c(
            "bladder-pain", "Bladder pain (interstitial cystitis)", BLADDER,
            "Bladder or pelvic pain, often with going often.",
            setOf(Module.CONTINENCE, Module.SYMPTOMS),
            setOf(Fields.CONTINENCE_PAIN, Fields.CONTINENCE_URGENCY, Fields.CONTINENCE_TRIGGERS, Fields.CONTINENCE_UTI_SIGNS, Fields.CONTINENCE_BOTHER),
            symptoms = listOf("pelvic pain", "bladder pain", "pain when the bladder fills"),
            triggers = listOf("acidic foods", "coffee", "alcohol", "stress", "sitting long"),
            aliases = listOf("IC", "painful bladder syndrome", "BPS"),
        ),
        c(
            "recurrent-uti", "Recurrent urine infections", BLADDER,
            "Urine infections that keep coming back.",
            setOf(Module.CONTINENCE),
            setOf(Fields.CONTINENCE_UTI_SIGNS, Fields.CONTINENCE_PAIN, Fields.CONTINENCE_VOLUME),
            symptoms = listOf("burning", "cloudy urine", "strong smell", "fever", "back pain"),
            aliases = listOf("UTI", "cystitis"),
        ),
        c(
            "after-prostate-surgery", "After prostate surgery or treatment", BLADDER,
            "Leaks and urgency while things settle after prostate treatment.",
            setOf(Module.CONTINENCE),
            bladderCore + protection + setOf(Fields.CONTINENCE_PAD_WEIGHT, Fields.CONTINENCE_ACTIVITY),
            aliases = listOf("prostatectomy", "radiotherapy"),
        ),
        c(
            "after-birth-bladder", "Bladder or bowel changes after a birth", BLADDER,
            "Leaks or urgency after pregnancy and birth: common, and very treatable.",
            setOf(Module.CONTINENCE),
            bladderCore + setOf(Fields.CONTINENCE_ACTIVITY, Fields.CONTINENCE_PROTECTION, Fields.CONTINENCE_BOWEL_ACCIDENTS),
            aliases = listOf("postpartum incontinence"),
        ),

        // ---- Periods and hormones
        c(
            "endometriosis", "Endometriosis", PERIODS,
            "Pain with periods and at other times, and much more.",
            setOf(Module.CYCLE, Module.SYMPTOMS),
            setOf(Fields.CYCLE_PAIN, Fields.CYCLE_PAIN_AREAS, Fields.CYCLE_SYMPTOM_SEVERITY, Fields.CYCLE_ENERGY, Fields.CYCLE_MOOD, Fields.CYCLE_CLOTS, Fields.CYCLE_PRODUCTS),
            symptoms = listOf("pelvic pain", "cramps", "back pain", "pain with sex", "painful bowel movements", "bloating", "fatigue", "nausea"),
            aliases = listOf("endo"),
        ),
        c(
            "adenomyosis", "Adenomyosis", PERIODS,
            "Heavy, painful periods.",
            setOf(Module.CYCLE),
            setOf(Fields.CYCLE_PAIN, Fields.CYCLE_PAIN_AREAS, Fields.CYCLE_PRODUCTS, Fields.CYCLE_CLOTS, Fields.CYCLE_SYMPTOM_SEVERITY),
            symptoms = listOf("cramps", "heavy bleeding", "pelvic pain", "bloating"),
        ),
        c(
            "pcos", "PCOS", PERIODS,
            "Irregular cycles, and often changes to skin, hair and weight.",
            setOf(Module.CYCLE, Module.BODY),
            setOf(Fields.CYCLE_LH, Fields.CYCLE_OVULATION_TEST, Fields.CYCLE_BBT, Fields.CYCLE_MUCUS, Fields.BODY_MEASUREMENTS),
            symptoms = listOf("acne", "hair growth", "hair thinning", "fatigue", "cravings"),
            aliases = listOf("polycystic ovary syndrome"),
        ),
        c(
            "pmdd", "PMDD", PERIODS,
            "Big changes in mood in the days before a period.",
            setOf(Module.CYCLE, Module.MIND),
            setOf(Fields.CYCLE_MOOD, Fields.CYCLE_SYMPTOM_SEVERITY, Fields.CYCLE_ENERGY),
            symptoms = listOf("low mood", "irritability", "anxiety", "mood swings", "trouble sleeping", "fatigue"),
            aliases = listOf("premenstrual dysphoric disorder", "PMS"),
        ),
        c(
            "heavy-periods", "Heavy periods", PERIODS,
            "Bleeding that's heavy, long, or gets in the way of life.",
            setOf(Module.CYCLE),
            setOf(Fields.CYCLE_PRODUCTS, Fields.CYCLE_CLOTS, Fields.CYCLE_COLOR, Fields.CYCLE_ENERGY),
            symptoms = listOf("fatigue", "dizziness", "cramps"),
            aliases = listOf("menorrhagia", "flooding"),
        ),
        c(
            "fibroids", "Fibroids", PERIODS,
            "Growths in the womb that can mean heavy or painful periods.",
            setOf(Module.CYCLE),
            setOf(Fields.CYCLE_PRODUCTS, Fields.CYCLE_CLOTS, Fields.CYCLE_PAIN, Fields.CYCLE_PAIN_AREAS),
            symptoms = listOf("heavy bleeding", "pelvic pressure", "back pain", "bloating"),
        ),
        c(
            "trying-to-conceive", "Trying to conceive", PERIODS,
            "Knowing your fertile days, with tests and temperatures.",
            setOf(Module.CYCLE),
            setOf(Fields.CYCLE_FERTILITY, Fields.CYCLE_BBT, Fields.CYCLE_LH, Fields.CYCLE_OVULATION_TEST, Fields.CYCLE_MUCUS, Fields.CYCLE_CERVIX, Fields.CYCLE_PREGNANCY_TEST, Fields.CYCLE_SEX),
            kind = ConditionKind.GOAL,
            aliases = listOf("TTC", "fertility"),
        ),

        // ---- Neurodivergence
        c(
            "autism", "Autism", NEURO,
            "Your senses, energy, overwhelm and what helps, in your words.",
            setOf(Module.NEURO, Module.MIND),
            setOf(
                Fields.NEURO_ABOUT_ME, Fields.NEURO_SENSORY, Fields.NEURO_OVERLOAD, Fields.NEURO_BURNOUT, Fields.NEURO_MASKING, Fields.NEURO_SOCIAL,
                Fields.NEURO_INTERESTS, Fields.NEURO_STIMS, Fields.NEURO_ROUTINES, Fields.NEURO_CARDS, Fields.NEURO_CALM, Fields.MIND_ENERGY_BUDGET,
            ),
            triggers = listOf("change of plans", "noise", "bright light", "crowds", "too many demands", "social", "hunger", "tired"),
            aliases = listOf("autistic", "ASD", "Asperger's"),
        ),
        c(
            "adhd", "ADHD", NEURO,
            "Focus, getting started, time, routines and how medication works for you.",
            setOf(Module.NEURO, Module.MIND),
            setOf(
                Fields.NEURO_ABOUT_ME, Fields.NEURO_FOCUS, Fields.NEURO_EXECUTIVE, Fields.NEURO_TIME, Fields.NEURO_ROUTINES, Fields.NEURO_MED_EFFECT,
                Fields.NEURO_INTERESTS, Fields.NEURO_SOCIAL, Fields.MIND_ENERGY_BUDGET,
            ),
            aliases = listOf("ADD", "attention deficit"),
        ),
        c(
            "audhd", "AuDHD (autism and ADHD)", NEURO,
            "Both together, with everything from each.",
            setOf(Module.NEURO, Module.MIND),
            setOf(
                Fields.NEURO_ABOUT_ME, Fields.NEURO_SENSORY, Fields.NEURO_OVERLOAD, Fields.NEURO_BURNOUT, Fields.NEURO_MASKING, Fields.NEURO_SOCIAL,
                Fields.NEURO_INTERESTS, Fields.NEURO_STIMS, Fields.NEURO_ROUTINES, Fields.NEURO_CARDS, Fields.NEURO_CALM, Fields.NEURO_FOCUS,
                Fields.NEURO_EXECUTIVE, Fields.NEURO_TIME, Fields.NEURO_MED_EFFECT, Fields.MIND_ENERGY_BUDGET,
            ),
        ),
        c(
            "sensory-processing", "Sensory processing differences", NEURO,
            "Senses that are more (or less) intense, and what helps.",
            setOf(Module.NEURO),
            setOf(Fields.NEURO_ABOUT_ME, Fields.NEURO_SENSORY, Fields.NEURO_OVERLOAD, Fields.NEURO_CALM, Fields.NEURO_STIMS),
            aliases = listOf("SPD", "sensory sensitivity"),
        ),

        // ---- Mental health
        c("depression", "Depression", MENTAL, "Mood over time, what helps, and support when you need it.", setOf(Module.MIND), setOf(Fields.MIND_ENERGY_BUDGET), aliases = listOf("low mood")),
        c("anxiety", "Anxiety", MENTAL, "Worry and anxiety over time, calming tools, and what sets it off.", setOf(Module.MIND), triggers = listOf("caffeine", "poor sleep", "social", "work", "health worries"), aliases = listOf("GAD", "panic")),
        c("bipolar", "Bipolar", MENTAL, "Mood, energy and sleep together, to notice changes early.", setOf(Module.MIND, Module.SLEEP), setOf(Fields.MIND_ENERGY_BUDGET), aliases = listOf("bipolar disorder")),
        c("ptsd", "PTSD", MENTAL, "Mood, sleep and what sets things off, with calming tools close by.", setOf(Module.MIND, Module.SLEEP), aliases = listOf("trauma", "C-PTSD")),
        c("ocd", "OCD", MENTAL, "Anxiety over time, and what helps.", setOf(Module.MIND), aliases = listOf("obsessive-compulsive")),
        // No food or calorie features here: numbers about food can make recovery harder.
        c(
            "eating-disorder", "Eating disorder recovery", MENTAL,
            "Mood and support, without calorie numbers.",
            setOf(Module.MIND),
            aliases = listOf("anorexia", "bulimia", "ARFID", "binge eating"),
        ),

        // ---- Long-term conditions
        c(
            "migraine", "Migraine", CHRONIC,
            "Attacks, what helps, and what goes with them (sleep, your cycle, air pressure).",
            setOf(Module.SYMPTOMS),
            symptoms = listOf("headache", "migraine", "nausea", "light sensitivity", "sound sensitivity", "aura"),
            triggers = listOf("poor sleep", "stress", "alcohol", "skipped meal", "screens", "weather", "period"),
        ),
        c(
            "chronic-pain", "Chronic pain", CHRONIC,
            "Pain over time, energy, and what helps.",
            setOf(Module.SYMPTOMS, Module.MIND),
            setOf(Fields.MIND_ENERGY_BUDGET),
            symptoms = listOf("back pain", "joint pain", "muscle pain", "fatigue"),
        ),
        c(
            "fibromyalgia", "Fibromyalgia", CHRONIC,
            "Pain, fatigue and sleep, and pacing your energy.",
            setOf(Module.SYMPTOMS, Module.MIND, Module.SLEEP),
            setOf(Fields.MIND_ENERGY_BUDGET),
            symptoms = listOf("widespread pain", "fatigue", "brain fog", "stiffness", "trouble sleeping"),
        ),
        c(
            "me-cfs", "ME/CFS", CHRONIC,
            "Pacing your energy, and noticing crashes and what comes before them.",
            setOf(Module.SYMPTOMS, Module.MIND),
            setOf(Fields.MIND_ENERGY_BUDGET),
            symptoms = listOf("fatigue", "crash after activity", "brain fog", "unrefreshing sleep", "dizziness"),
            aliases = listOf("chronic fatigue syndrome", "myalgic encephalomyelitis"),
        ),
        c(
            "long-covid", "Long COVID", CHRONIC,
            "Energy, symptoms and heart rate over time.",
            setOf(Module.SYMPTOMS, Module.MIND, Module.VITALS),
            setOf(Fields.MIND_ENERGY_BUDGET),
            symptoms = listOf("fatigue", "breathlessness", "brain fog", "racing heart", "crash after activity"),
        ),
        c(
            "pots", "POTS", CHRONIC,
            "Heart rate, blood pressure, fluids and symptoms when standing.",
            setOf(Module.SYMPTOMS, Module.VITALS),
            symptoms = listOf("dizziness", "racing heart", "fatigue", "brain fog", "fainting"),
            triggers = listOf("standing", "heat", "dehydration", "after meals", "period"),
            aliases = listOf("postural tachycardia", "dysautonomia"),
        ),
        c("hypertension", "High blood pressure", CHRONIC, "Readings over time against your target.", setOf(Module.VITALS, Module.MEDICATIONS), aliases = listOf("hypertension", "HBP")),
        c("type-1-diabetes", "Type 1 diabetes", CHRONIC, "Glucose, time in range and food together.", setOf(Module.VITALS, Module.NUTRITION, Module.MEDICATIONS), aliases = listOf("T1D")),
        c("type-2-diabetes", "Type 2 diabetes", CHRONIC, "Glucose, food and activity together.", setOf(Module.VITALS, Module.NUTRITION, Module.MEDICATIONS, Module.ACTIVITY), aliases = listOf("T2D", "prediabetes")),
        c("thyroid", "Thyroid condition", CHRONIC, "Medication, lab results and how you feel.", setOf(Module.MEDICATIONS, Module.HORMONES, Module.SYMPTOMS), aliases = listOf("hypothyroidism", "hyperthyroidism", "Hashimoto's", "Graves'")),

        // ---- Goals
        c("lose-weight", "Lose weight", GOALS, "Food, activity and your weight trend, gently.", setOf(Module.NUTRITION, Module.BODY, Module.ACTIVITY), setOf(Fields.BODY_MEASUREMENTS), kind = ConditionKind.GOAL),
        c("gain-weight", "Gain weight", GOALS, "Food and your weight trend.", setOf(Module.NUTRITION, Module.BODY), setOf(Fields.BODY_MEASUREMENTS), kind = ConditionKind.GOAL),
        c("build-muscle", "Build muscle", GOALS, "Workouts, protein and measurements.", setOf(Module.ACTIVITY, Module.NUTRITION, Module.BODY), setOf(Fields.BODY_MEASUREMENTS), kind = ConditionKind.GOAL),
        c("sleep-better", "Sleep better", GOALS, "Your nights, and what goes with good ones.", setOf(Module.SLEEP, Module.HABITS), kind = ConditionKind.GOAL),
        c("drink-less", "Drink less alcohol", GOALS, "Drinks over time, alcohol-free days, and how nights go.", setOf(Module.NUTRITION, Module.HABITS), kind = ConditionKind.GOAL),
        c("less-caffeine", "Less caffeine", GOALS, "Caffeine over the day, and how it goes with sleep.", setOf(Module.NUTRITION, Module.SLEEP), kind = ConditionKind.GOAL),
        c("move-more", "Move more", GOALS, "Steps, workouts and gentle streaks.", setOf(Module.ACTIVITY, Module.HABITS), kind = ConditionKind.GOAL),

        // ---- Life
        c(
            "transition", "Transition", LIFE,
            "Milestones, changes, voice, hormones and photos, at your pace.",
            setOf(Module.TRANSITION, Module.HORMONES, Module.MEDICATIONS, Module.APPOINTMENTS),
            setOf(Fields.TRANSITION_MILESTONES, Fields.TRANSITION_CHANGES, Fields.TRANSITION_MOMENTS, Fields.TRANSITION_VOICE, Fields.TRANSITION_HAIR, Fields.TRANSITION_MEASUREMENTS),
            kind = ConditionKind.LIFE,
            aliases = listOf("trans", "nonbinary", "gender-affirming care", "HRT"),
        ),
        c("pregnancy", "Pregnancy", LIFE, "Weeks, movements, contractions and appointments.", setOf(Module.CYCLE, Module.APPOINTMENTS, Module.VITALS), setOf(Fields.CYCLE_PREGNANCY_TEST), kind = ConditionKind.LIFE),
        c(
            "after-a-birth", "After a birth", LIFE,
            "Feeds, recovery, mood and the pelvic floor.",
            setOf(Module.CYCLE, Module.MIND, Module.CONTINENCE),
            setOf(Fields.CONTINENCE_PELVIC_FLOOR, Fields.CONTINENCE_LEAK_SIZE),
            kind = ConditionKind.LIFE,
            aliases = listOf("postpartum", "postnatal"),
        ),
        c(
            "menopause", "Perimenopause and menopause", LIFE,
            "Hot flashes, sleep, mood and changing cycles.",
            setOf(Module.CYCLE, Module.SLEEP, Module.MIND),
            setOf(Fields.CYCLE_SYMPTOM_SEVERITY, Fields.CYCLE_MOOD),
            symptoms = listOf("hot flashes", "night sweats", "trouble sleeping", "brain fog", "joint pain", "low mood", "anxiety"),
            kind = ConditionKind.LIFE,
            aliases = listOf("perimenopause", "menopause", "HRT"),
        ),
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String): ConditionDefinition? = byId[id]
}
