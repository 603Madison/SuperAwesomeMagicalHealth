package com.health.server.modules

import com.health.api.BreathPacing
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationStep
import kotlin.math.roundToInt

/**
 * The guided meditations, written for this app: calm, plain and for anybody. Eyes open or closed,
 * any position, stop any time; nothing about what a body is or feels like, nothing gendered, and no
 * promises about health. Each comes in 5, 10 and 15 minutes: the longer ones add steps, and the
 * pauses (worked out here, so each lands on its length) carry most of the time.
 *
 * Ids are stable ("wind-down-10"): sessions keep them. Never rename one.
 */
object Meditations {
    /** How fast the apps read, for working out the pauses: a calm pace. */
    const val WORDS_PER_SECOND = 2.5

    /** The shortest silence after a step, so words never run into each other. */
    private const val MIN_PAUSE = 3

    val LENGTHS = listOf(5, 10, 15)

    /**
     * A step as written: [from] is the shortest length it's in (5, 10 or 15); [weight] is its share
     * of the silence; [rounds] the breaths of [breath] at each length, for the pacer.
     */
    private class Beat(
        val say: String,
        val from: Int = 5,
        val weight: Int = 2,
        val breath: BreathPacing? = null,
        val rounds: Map<Int, Int> = emptyMap(),
    )

    private fun opening(): List<Beat> = listOf(
        Beat("Welcome. This is some quiet time for you before sleep. Lie down or sit, whichever is comfortable, and change position any time you like.", weight = 1),
        Beat("You can close your eyes, or keep them open and let your gaze rest softly on one spot. You can stop at any time.", weight = 1),
        Beat("There's nothing to get right here. If your mind wanders, that's just what minds do. Come back whenever you notice.", from = 10),
        Beat("Take a moment to settle in, and let the rest of the world wait outside for a little while.", from = 15),
    )

    private val SCRIPTS: Map<MeditationKind, Pair<String, List<Beat>>> = mapOf(
        MeditationKind.WIND_DOWN to ("Setting the day down, softening, and settling for sleep." to listOf(
            Beat("Let yourself arrive. Notice where you're resting, and let the bed or chair take a little more of your weight."),
            Beat("Notice the sounds around you, near ones and far ones. You don't need to do anything with them. Just let them be there.", from = 10),
            Beat("If something from today comes to mind, you can say to it, quietly: yes, that happened. Then set it down for now. It will still be there tomorrow, if it's needed."),
            Beat("You might picture putting the day on a shelf, or into a drawer, and closing it gently. You're done with it for tonight.", from = 15),
            Beat("Let your breathing be however it is. You don't have to change it. It may slow a little on its own.", weight = 3),
            Beat("If it's comfortable, let your forehead smooth out and your jaw loosen. Let your shoulders drop, just a little.", from = 10),
            Beat("Wherever you're holding on, see if you can let go by a tiny amount. A tiny amount is plenty.", from = 10, weight = 3),
            Beat("Each time you breathe out, let yourself sink a little further into whatever is holding you up.", from = 15, weight = 3),
            Beat("If thoughts come, let them drift past like clouds. You don't need to follow them anywhere.", from = 15, weight = 3),
            Beat("You've given yourself some quiet minutes. That's enough. Stay here as long as you like, and let sleep come when it's ready.", weight = 3),
        )),
        MeditationKind.BODY_SCAN to ("Noticing each part of you in turn, without needing to change a thing." to listOf(
            Beat("We'll move our attention slowly through the body. You don't need to feel anything in particular, or change anything. If a part of you is hard to feel, or you'd rather not rest there, skip it and move on.", weight = 1),
            Beat("Start at the top of your head. Notice whatever is there: warmth, coolness, tingling, or nothing much at all. Nothing much is fine too."),
            Beat("Move down to your face: around your eyes, your cheeks, your mouth. Let them soften, if they'd like to.", from = 10),
            Beat("Now your neck and shoulders. They often hold the day. Let them be heavy for a moment."),
            Beat("Notice your arms, from the shoulders down, as far as they go. Let them rest.", from = 10),
            Beat("Bring your attention to the middle of your body: your chest, your belly, your back. Notice the gentle movement as you breathe, or simply the weight resting down.", weight = 3),
            Beat("Notice where your back meets whatever you're lying or sitting on. Let it hold you.", from = 15),
            Beat("Now your hips, and the lower part of your body. Let them be heavy.", from = 10),
            Beat("Move down through your legs, as far as they go, and let them settle."),
            Beat("Now notice your body as a whole: resting, breathing, held.", from = 15, weight = 3),
            Beat("If any place still feels tight, breathe towards it kindly, without needing it to change.", from = 15, weight = 3),
            Beat("That's all of you, noticed, from top to bottom. Let your attention spread out and go soft. Stay here, and drift whenever you're ready.", weight = 3),
        )),
        MeditationKind.BREATHING to ("Easy breaths, a little longer out than in, with a pacer to follow." to listOf(
            Beat("We'll breathe slowly together, a little longer out than in. Breathe through your nose or your mouth, whichever is easier.", weight = 1),
            Beat("If the count feels too long, breathe at your own pace and just follow the shape. If slow breathing ever feels uncomfortable, let it go and breathe as you normally do. You're always in charge.", weight = 1),
            Beat("Breathe in for four, and out for six. Follow the circle as it grows and shrinks.", weight = 1, breath = PACE, rounds = mapOf(5 to 8, 10 to 12, 15 to 16)),
            Beat("Now let your breathing go back to its own rhythm. Notice how it feels.", weight = 3),
            Beat("If following your breath doesn't suit you, rest your attention on a sound in the room instead. Otherwise, notice the cool air coming in and the warm air going out.", from = 10, weight = 3),
            Beat("Let's take a few more slow breaths. In for four, and gently out for six.", from = 10, weight = 1, breath = PACE, rounds = mapOf(10 to 10, 15 to 12)),
            Beat("Let each breath out be like setting something down.", from = 15, weight = 3),
            Beat("One last round, slow and easy. In for four, out for six.", from = 15, weight = 1, breath = PACE, rounds = mapOf(15 to 10)),
            Beat("That's all for now. Let your breath be easy and unhurried. Stay as long as you like, and let sleep find you.", weight = 3),
        )),
        MeditationKind.DRIFTING_OFF to ("A quiet evening garden to drift into sleep with." to listOf(
            Beat("This one is a slow, quiet picture to drift off with. If imagining isn't your thing, let the words wash past. You don't need to follow them.", weight = 1),
            Beat("Picture somewhere calm at the end of a long, warm day. A garden, perhaps, with the light fading and the air soft."),
            Beat("The paths here are wide and level. You can wander, or rest on a bench, or simply be there. There's no hurry, and nowhere you need to be.", from = 10),
            Beat("Small lanterns come on, one by one, along the edges of the paths. Their light is gentle and golden."),
            Beat("Somewhere nearby, water trickles slowly over smooth stones.", from = 10),
            Beat("There's a scent you like in the air. Cut grass, or rain, or flowers, or just the cool of the evening.", from = 15),
            Beat("You find a comfortable place to rest. It's just the right size, and it holds you well.", from = 10),
            Beat("Above you, the sky turns from blue to deep blue, and the first stars appear, faint and far away.", from = 15, weight = 3),
            Beat("The garden is safe and quiet. It looks after itself. There's nothing to do here but rest.", weight = 3),
            Beat("The lanterns glow a little lower. The water keeps its slow song. Everything is settling for the night.", from = 15, weight = 3),
            Beat("Stay in the garden as long as you like. Let the picture fade whenever it wants to, and let sleep come.", weight = 4),
        )),
        MeditationKind.GRATITUDE to ("Looking back gently for something from today that was OK." to listOf(
            Beat("In a moment we'll look back gently over today, for something that was OK, or good, even something small. If today was hard and nothing comes, that's alright. You can simply rest.", weight = 1),
            Beat("Settle in, and let your breath be easy."),
            Beat("Bring to mind one small thing from today that was OK. A taste, a sound, a warm drink, a moment of quiet. It doesn't need to be big.", weight = 3),
            Beat("Stay with it for a moment. Where were you? What did it look like, or sound like, or feel like?", from = 10, weight = 3),
            Beat("Perhaps a second thing comes. Something someone did, or something you did for yourself.", from = 10, weight = 3),
            Beat("Or something that's simply steady: whatever is holding you up right now, the quiet of this moment, the next breath arriving on its own.", from = 15, weight = 3),
            Beat("Notice how it feels to remember these things. If warmth comes, let it spread. If nothing much comes, that's fine too.", from = 15, weight = 3),
            Beat("If you'd like, say a quiet thank you, to someone, to something, or to yourself.", from = 10, weight = 2),
            Beat("Let those things rest with you as you settle for the night. There's nothing else to do now. Let sleep come when it's ready.", weight = 3),
        )),
    )

    private val PACE get() = BreathPacing(inSeconds = 4, holdSeconds = 0, outSeconds = 6, rounds = 1)

    private val TITLES = mapOf(
        MeditationKind.WIND_DOWN to "Wind down",
        MeditationKind.BODY_SCAN to "Body scan",
        MeditationKind.BREATHING to "Slow breathing",
        MeditationKind.DRIFTING_OFF to "Drifting off",
        MeditationKind.GRATITUDE to "Gratitude",
    )

    /** Every meditation: each kind in 5, 10 and 15 minutes, in that order. */
    val ALL: List<Meditation> = MeditationKind.entries.flatMap { kind ->
        val (about, beats) = SCRIPTS.getValue(kind)
        LENGTHS.map { minutes -> build(kind, about, opening() + beats, minutes) }
    }

    private val BY_ID = ALL.associateBy { it.id }

    fun byId(id: String?): Meditation? = id?.let { BY_ID[it] }

    /** The id for [kind] at [minutes]: "wind-down-10". */
    fun idOf(kind: MeditationKind, minutes: Int) = "${kind.name.lowercase().replace('_', '-')}-$minutes"

    /** About how long [text] takes to read aloud, in seconds. */
    fun speakingSeconds(text: String): Double = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } / WORDS_PER_SECOND

    /** How long a step's pacer runs, in seconds. */
    fun pacerSeconds(step: MeditationStep): Int = step.breath?.let { b -> b.rounds * (b.inSeconds + b.holdSeconds + b.outSeconds) } ?: 0

    /** About how long [meditation] takes: its words read at a calm pace, its pacer and its pauses, in seconds. */
    fun seconds(meditation: Meditation): Double = meditation.steps.sumOf { speakingSeconds(it.say) + pacerSeconds(it) + it.pauseSeconds }

    /** The steps for [minutes]: the silence left once the words and the pacer are read is shared out by weight. */
    private fun build(kind: MeditationKind, about: String, all: List<Beat>, minutes: Int): Meditation {
        val beats = all.filter { it.from <= minutes }
        val steps = beats.map { b -> MeditationStep(b.say, 0, b.breath?.copy(rounds = b.rounds.getValue(minutes))) }
        val busy = steps.sumOf { speakingSeconds(it.say) + pacerSeconds(it) }
        val silence = minutes * 60 - busy
        val weights = beats.sumOf { it.weight }.toDouble()
        check(silence >= MIN_PAUSE * beats.size) { "${idOf(kind, minutes)} has more words than fit" }
        val paused = steps.zip(beats) { s, b -> s.copy(pauseSeconds = maxOf(MIN_PAUSE, (silence * b.weight / weights).roundToInt())) }
        return Meditation(idOf(kind, minutes), TITLES.getValue(kind), about, minutes, kind, paused)
    }
}
