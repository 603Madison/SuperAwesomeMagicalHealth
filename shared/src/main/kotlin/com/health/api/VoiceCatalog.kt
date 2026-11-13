package com.health.api

import com.health.api.ExerciseKind.GLIDE
import com.health.api.ExerciseKind.GUIDED
import com.health.api.ExerciseKind.HOLD
import com.health.api.ExerciseKind.MATCH
import com.health.api.ExerciseKind.READ
import com.health.api.ExerciseKind.RESONANCE
import com.health.api.ExerciseKind.SAY
import com.health.api.ExerciseKind.SPEAK
import com.health.api.VoiceGoal.DEEPER
import com.health.api.VoiceGoal.EXPLORING
import com.health.api.VoiceGoal.LIGHTER
import com.health.api.VoiceMeter.INTONATION
import com.health.api.VoiceMeter.LOUDNESS
import com.health.api.VoiceMeter.PITCH
import com.health.api.VoiceMeter.WEIGHT
import kotlin.math.ceil

/**
 * The voice programme: what a good voice coach would teach, in order, for whichever direction the
 * person chooses (VoiceGoal). Served at GET /api/transition/voice/program and used directly by the
 * phone app, so it works offline.
 *
 * Most exercises suit every goal: their direction comes from the person's own target range and
 * resonance target (MATCH notes are semitones from the middle of the range; the meters show the
 * target). Where direction matters (brightening or darkening resonance, a lighter or fuller weight,
 * more melody or steadier lines), there are variants with `goals`, and EXPLORING gets both. Nothing
 * here assumes a direction from anything but the goal the person picked.
 *
 * Units come in order and each unlocks once the one before is done. The passages and prompts are
 * written for this programme.
 */
object VoiceCatalog {
    private val L_E = setOf(LIGHTER, EXPLORING)
    private val D_E = setOf(DEEPER, EXPLORING)

    // ---------------------------------------------------------------- Texts

    /** Read at the start and at each check-in, so the two compare fairly. */
    const val BASELINE_PASSAGE =
        "The morning was cool and bright, so I took the long way to the station. A small café on the corner was opening its shutters, " +
            "and the smell of fresh bread drifted across the road. I bought a coffee and a warm roll, found a seat by the window, and " +
            "watched the city wake up: buses sighing at the lights, a dog pulling its owner towards the park, and a cyclist ringing a " +
            "cheerful bell."

    const val BASELINE_PROMPT = "Talk about what you did yesterday, in as much or as little detail as you like."

    private const val PASSAGE_BALCONY =
        "Every Saturday, my neighbour waters the plants on our shared balcony. There are tomatoes, mint, a lemon tree that has never " +
            "grown a single lemon, and a sunflower taller than both of us. We've agreed the lemon tree is just shy. One day, we tell " +
            "each other, it will surprise us."

    private const val PASSAGE_BUS =
        "Rain had been falling since breakfast, so the bus was full of damp coats and steamed-up windows. Near the front, a small " +
            "child was counting every red car out loud. By the time we reached the bridge, the whole bus was helping. \"Forty-two,\" " +
            "someone called from the back. \"Forty-three,\" said the driver, without looking round. When the sun finally came out, " +
            "there was a cheer, and I'm still not sure whether it was for the weather or the cars."

    private const val PASSAGE_LIGHTHOUSE =
        "On the far side of the island stands an old lighthouse, painted white with a red band near the top. Nobody keeps it now; " +
            "the lamp turns by itself. But someone still tends the little garden at its foot. Every spring, rows of beans climb " +
            "their poles, and sweet peas lean on the low stone wall. Visitors leave notes in a tin box by the gate: thank-yous, " +
            "drawings, a recipe for plum jam. Nobody knows who the gardener is. Some say it's a retired sailor from the village; " +
            "others say it's the baker, who is always up before dawn. Whoever it is, they have a sense of humour. Last summer, a " +
            "scarecrow appeared in a captain's hat, holding a sign that read: Please do not feed the seagulls. They are already " +
            "quite full of themselves."

    private const val PASSAGE_PANCAKES =
        "Saturday pancakes are a tradition in our flat. Whoever wakes up first puts the kettle on and finds the big blue bowl. Into " +
            "it go two cups of flour, a pinch of salt, two eggs, and enough milk to make a batter as smooth as cream. Then the batter " +
            "rests while the pan heats, and somebody always asks whether we have any lemons left. We never do. So we make do with " +
            "honey, or jam, or a sprinkle of sugar, and argue cheerfully about which is best. The first pancake is always a " +
            "disaster. That's the rule. It goes to whoever is hungriest, and they eat it anyway."

    private val EVERYDAY_WORDS = listOf(
        "hello", "thanks", "yes", "no", "maybe", "sorry", "please", "okay", "lovely", "really", "morning", "evening", "Monday",
        "Friday", "weekend", "coffee", "water", "lunch", "dinner", "window", "phone", "email", "meeting", "number", "address",
        "seven", "eleven", "twenty", "hundred", "yellow", "purple", "orange", "music", "garden", "kitchen", "tomorrow",
        "yesterday", "perfect", "amazing", "honestly", "exactly", "anyway", "welcome", "goodbye",
    )

    private val EVERYDAY_PHRASES = listOf(
        "Hi, how are you?", "Thanks so much.", "No worries.", "See you later.", "I'm on my way.", "Can I get a coffee, please?",
        "Just a moment.", "That sounds great.", "Excuse me.", "Have a good one.", "Sorry, could you say that again?",
        "I'll have the same.", "Where's the nearest station?", "Is this seat free?", "It's nice to meet you.", "What time is it?",
        "Let me check.", "Me too.", "Not today, thanks.", "Could I pay by card?", "Happy birthday!", "Good morning!", "Take care.",
        "I'm fine, thanks.", "How was your weekend?", "Two tickets, please.", "Can you hear me okay?", "That's a good idea.",
        "I'm not sure yet.", "Talk soon.",
    )

    private val SENTENCES = listOf(
        "I'm going to the shops; do you need anything?",
        "The train was ten minutes late again this morning.",
        "Could you pass me the salt, please?",
        "We should go for a walk while the sun's out.",
        "My phone's nearly out of battery.",
        "I've been meaning to call you all week.",
        "What did you think of the film?",
        "There's a new bakery opening on the high street.",
        "If it rains tomorrow, we'll stay in and play cards.",
        "Honestly, that was the best soup I've ever had.",
        "I'd like to make an appointment for next Tuesday.",
        "Can you believe how quickly the year has gone?",
        "Let's meet by the fountain at half past two.",
        "I'm sorry, I didn't quite catch your name.",
        "The cat knocked a glass off the table again.",
        "Would you rather have tea or coffee?",
        "I'm learning to bake bread, and it's going surprisingly well.",
        "We're out of milk, so I'll pick some up on the way home.",
        "Thanks for waiting; I'll be with you in a minute.",
        "That's the funniest thing I've heard all day!",
    )

    /** The same lines for more melody and for steadier lines: only how they're said differs. */
    private val EXPRESSIVE = listOf(
        "That sounds great.", "I'll see you at six.", "Really? Tell me more.", "What a day it's been.", "Let's get started.",
        "I can't believe it's Friday already.", "Thanks so much for your help.", "I'll call you back later.",
        "That's such a good idea.", "We'll sort it out tomorrow.",
    )

    private val PROMPTS = listOf(
        "Describe your idea of a perfect lazy Sunday.",
        "Talk about a meal you love, and how it's made.",
        "Describe the view from a window you know well.",
        "Tell the story of a small thing that made you smile recently.",
        "Explain how to play a game you enjoy.",
        "Describe a place you'd love to visit, and why.",
        "Talk about a song, show or book you've enjoyed lately.",
        "Give directions from your front door to somewhere nearby.",
        "Describe what's on the table or desk in front of you.",
        "If you could learn any skill overnight, what would it be?",
        "Present today's weather as if you were on the radio.",
        "Describe an animal you like to someone who's never seen one.",
    )

    private val PHONE_PROMPTS = listOf(
        "Call a restaurant to book a table for two at seven.",
        "Leave a voicemail for a friend about plans for the weekend.",
        "Ring a shop to ask whether something is in stock.",
        "Call to move an appointment to next week.",
        "Order a takeaway: choose a few dishes and a drink.",
        "Ask a library whether a book you'd like is in.",
        "Answer the phone and take a message for someone.",
    )

    // ---------------------------------------------------------------- Exercises

    val EXERCISES: List<VoiceExercise> = listOf(
        // ---- 1. Looking after your voice
        VoiceExercise(
            "posture", "Easy posture", VoiceSkill.HEALTH, GUIDED,
            listOf(
                "Sit or stand tall but soft, with your feet flat on the floor.",
                "Roll your shoulders up, back and down, and let them rest.",
                "Let your head float, chin level, as if a string lifts the top of your head.",
                "Unclench your jaw and let your tongue rest in the floor of your mouth.",
            ),
            seconds = 60,
            tip = "Come back to this easy posture whenever you notice tension creeping in.",
        ),
        VoiceExercise(
            "breath-low", "Low, easy breath", VoiceSkill.WARM_UP, GUIDED,
            listOf(
                "Rest one hand on your belly and one on your chest.",
                "Breathe in through your nose for a count of four, letting your belly and lower ribs widen.",
                "Breathe out on a soft 'sss' for a count of eight.",
                "Keep your shoulders and upper chest still. Repeat six times.",
            ),
            seconds = 90,
            tip = "No need to fill right up: easy, low breaths are what your voice likes.",
        ),
        VoiceExercise(
            "jaw-release", "Jaw, tongue and neck release", VoiceSkill.WARM_UP, GUIDED,
            listOf(
                "Massage the hinge of your jaw, just in front of your ears, in small circles.",
                "Let your jaw drop open, loose, and waggle it gently from side to side.",
                "Stick your tongue out gently, then let it rest. Do this three times.",
                "Tip each ear slowly towards its shoulder, and breathe.",
                "Finish with a big, lazy yawn.",
            ),
            seconds = 90,
            tip = "A loose jaw and tongue make everything else easier.",
        ),
        VoiceExercise(
            "hydration", "Water and your voice", VoiceSkill.HEALTH, GUIDED,
            listOf(
                "Take a few sips of water now.",
                "Keep a bottle nearby while you practise and through the day: your vocal folds are kept moist from the inside, over hours.",
                "When you feel the urge to clear your throat, swallow or sip instead.",
            ),
            seconds = 30,
            tip = "Breathing in steam, from a mug of hot water or the shower, can soothe a dry or tired voice.",
        ),
        VoiceExercise(
            "strain-check", "Easy, not strained", VoiceSkill.HEALTH, GUIDED,
            listOf(
                "Say 'hello' at a comfortable, chatty loudness.",
                "Notice your throat and neck: is there any squeeze, scratch, tickle or ache?",
                "An easy voice feels free. Strain feels tight or effortful, or leaves you sore afterwards.",
                "Signs to stop for the day: pain, hoarseness, or a voice that tires or cracks more than usual.",
                "Stopping early is part of training well, never a failure.",
            ),
            seconds = 60,
            tip = "If you feel strain during a session, say so when you finish: the trainer will suggest rest and start gently next time.",
        ),
        VoiceExercise(
            "baseline", "Your starting recording", VoiceSkill.HEALTH, SPEAK,
            listOf(
                "Use your everyday voice, the one you'd use right now without trying.",
                "First, read the passage aloud at a relaxed pace.",
                "Then talk freely about the prompt for about 30 seconds.",
                "This isn't a test. It's simply where you're starting from, so later you can hear how far you've come.",
                "You don't have to listen back today if you'd rather not.",
            ),
            seconds = 120,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = listOf(BASELINE_PASSAGE, BASELINE_PROMPT),
            tip = "Your plan can start from this recording. You can change your target range and resonance whenever you like.",
        ),

        // ---- 2. Warm-ups
        VoiceExercise(
            "lip-trills", "Lip trills", VoiceSkill.WARM_UP, HOLD,
            listOf(
                "Close your lips loosely and blow through them so they flutter, like a motorboat.",
                "Add a gentle voice to the flutter.",
                "Hold one comfortable note for a breath, then rest.",
                "Repeat four or five times.",
            ),
            seconds = 60,
            meters = listOf(PITCH),
            tip = "If your lips won't flutter, press your fingertips lightly into your cheeks beside your mouth.",
        ),
        VoiceExercise(
            "trill-glides", "Trill glides", VoiceSkill.WARM_UP, GLIDE,
            listOf(
                "Start a lip trill on a comfortable note.",
                "Slide it slowly up a little, then down, like a gentle siren.",
                "Make each slide a little wider, but only as far as feels easy.",
            ),
            seconds = 60,
            meters = listOf(PITCH),
            tip = "Keep the air steady and the flutter going: that's what keeps the slide smooth.",
        ),
        VoiceExercise(
            "hum-chew", "Humming and chewing", VoiceSkill.WARM_UP, HOLD,
            listOf(
                "Close your lips and hum 'mm' on an easy, speaking-level note.",
                "Chew slowly while you hum, as if on a big piece of toffee.",
                "Feel the buzz on your lips and around your nose.",
                "Now and then, open the hum into 'mah', keeping the buzz.",
            ),
            seconds = 60,
            meters = listOf(PITCH, LOUDNESS),
            tip = "That buzz at the front is an easy, efficient place to make sound from.",
        ),
        VoiceExercise(
            "gentle-sirens", "Gentle sirens", VoiceSkill.WARM_UP, GLIDE,
            listOf(
                "Say 'ng', as at the end of 'sing', with the back of your tongue raised.",
                "Slide from a low note up to a high one and back down, smoothly.",
                "Start small, and widen the slide a little each time.",
                "If it flips or cracks on the way, that's fine. Keep it easy.",
            ),
            seconds = 60,
            meters = listOf(PITCH),
            tip = "Quiet sirens are best. If a note feels tight, don't reach for it.",
        ),
        VoiceExercise(
            "straw", "Straw phonation", VoiceSkill.WARM_UP, GLIDE,
            listOf(
                "Hold a drinking straw lightly between your lips.",
                "Hum through the straw, so all the air goes through it and none through your nose.",
                "Hold a few comfortable notes, then slide up and down.",
                "Try a simple tune you like, still through the straw.",
            ),
            seconds = 90,
            meters = listOf(PITCH),
            tip = "You can put the straw in a glass with a little water and keep the bubbles gentle. The back-pressure helps your voice work with less effort.",
        ),
        VoiceExercise(
            "cool-down", "Cool down", VoiceSkill.WARM_UP, GUIDED,
            listOf(
                "Do a big, lazy yawn and let it go on a soft sigh, from high to low.",
                "Hum gently on a few easy notes.",
                "Roll your shoulders, and sip some water.",
            ),
            seconds = 45,
            tip = "Cooling down helps your voice settle back into everyday use.",
        ),

        // ---- 3. Pitch
        VoiceExercise(
            "range-explore", "Exploring your range", VoiceSkill.PITCH, GLIDE,
            listOf(
                "Hum or trill, and slide down to the lowest note that still feels easy.",
                "Then slide up to the highest note that still feels easy.",
                "Don't push at either end: comfortable is the point.",
                "Do this three times, and notice where your voice feels happiest.",
            ),
            seconds = 90,
            meters = listOf(PITCH),
            tip = "The trainer shows your lowest and highest easy notes. Your target range sits comfortably between them.",
        ),
        VoiceExercise(
            "range-lighter", "Choosing your range", VoiceSkill.PITCH, GUIDED,
            listOf(
                "Many people aiming for a lighter voice settle somewhere around 165 to 220 Hz when they speak.",
                "Start a few notes above where you speak now, not at the top of your range. You can move it later.",
                "Pitch is only part of the picture: resonance and weight often matter more to how a voice is heard.",
                "Set your target range in your plan, then carry on.",
            ),
            seconds = 60,
            goals = setOf(LIGHTER),
            tip = "Aiming too high tends to tire you and sound strained. Comfortable and sustainable is the goal.",
        ),
        VoiceExercise(
            "range-deeper", "Choosing your range", VoiceSkill.PITCH, GUIDED,
            listOf(
                "Many people aiming for a deeper voice speak somewhere around 85 to 150 Hz.",
                "Training alone can lower your speaking pitch only so far without strain: aim for the lower part of your comfortable range, not below it.",
                "Resonance and weight often do more for a deeper, fuller sound than pitch does.",
                "If hormones are part of your path, your range may move over the months. Check it again from time to time.",
                "Set your target range in your plan, then carry on.",
            ),
            seconds = 60,
            goals = setOf(DEEPER),
            tip = "Avoid pressing down into a creaky, rattly sound (vocal fry): it can feel deep, but it tires the voice.",
        ),
        VoiceExercise(
            "range-exploring", "Choosing your range", VoiceSkill.PITCH, GUIDED,
            listOf(
                "There's no right number. Pick a range of four or five notes that feels comfortable to try.",
                "You might spend a week a little higher, then a week a little lower, and notice how each feels.",
                "Resonance and weight shape how a voice is heard as much as pitch does.",
                "Set your target range in your plan, and change it whenever you like.",
            ),
            seconds = 60,
            goals = setOf(EXPLORING),
            tip = "What feels like you counts for more than any number.",
        ),
        VoiceExercise(
            "match-centre", "Matching the middle", VoiceSkill.PITCH, MATCH,
            listOf(
                "Listen to each tone, then hum it back on 'mm'.",
                "Hold it until the meter settles, then rest.",
                "Close is good: within a note counts.",
            ),
            seconds = 90,
            meters = listOf(PITCH),
            notes = listOf(0, 0, 1, 0, -1, 0, 2, 0, -2, 0),
            tip = "If you can't tell whether you're above or below, slide slowly until the meter shows a match.",
        ),
        VoiceExercise(
            "match-steps", "Stepping through your range", VoiceSkill.PITCH, MATCH,
            listOf(
                "Listen, then sing each tone back on 'mah' or 'mee'.",
                "Move smoothly from one note to the next, without pushing.",
                "Rest between rounds.",
            ),
            seconds = 120,
            meters = listOf(PITCH),
            notes = listOf(-2, 0, 2, 4, 2, 0, -2, 0),
            tip = "Light and easy beats loud: loudness won't help you find the note.",
        ),
        VoiceExercise(
            "hold-notes", "Holding steady notes", VoiceSkill.PITCH, HOLD,
            listOf(
                "Pick a note in the middle of your target range.",
                "Hold it on 'ee' for about five seconds, steady and easy.",
                "Then on 'oo', then on 'ah'. Rest between each.",
                "Go round twice more.",
            ),
            seconds = 90,
            meters = listOf(PITCH, LOUDNESS),
            tip = "Steady and relaxed matters more than long. Stop before you run out of air.",
        ),
        VoiceExercise(
            "glide-into-range", "Gliding into your range", VoiceSkill.PITCH, GLIDE,
            listOf(
                "Hum on the note where your voice usually rests.",
                "Slide smoothly into your target range, and land in the middle of it.",
                "Hold it for a moment, then open the hum into 'mah'.",
                "Rest, and repeat five times.",
            ),
            seconds = 90,
            meters = listOf(PITCH),
            tip = "Gliding in, rather than jumping, helps your voice learn the way there.",
        ),
        VoiceExercise(
            "pitch-words", "Single words in your range", VoiceSkill.PITCH, SAY,
            listOf(
                "Find your target with a short hum.",
                "Say each word starting on that note, then let it move naturally, as speech does.",
                "If a word drops out of your range, hum and try it again.",
            ),
            seconds = 120,
            meters = listOf(PITCH),
            texts = listOf(
                "hi", "yes", "okay", "mmm", "morning", "lovely", "hello", "many", "money", "lemon", "yellow", "Monday", "mellow",
                "moon", "name", "nine", "eleven", "never", "almost", "only", "really", "window", "wonderful", "evening",
            ),
            tip = "Words that start with m, n, l, w or y are easiest to land, because you can hum straight into them.",
        ),

        // ---- 4. Resonance
        VoiceExercise(
            "dog-pant", "Small dog, big dog", VoiceSkill.RESONANCE, GUIDED,
            listOf(
                "If it's comfortable, rest a finger lightly on the front of your throat, on your voice box.",
                "Pant silently like a small dog: quick, light breaths. Feel the voice box rise.",
                "Now pant like a big dog: slow, deep breaths. Feel it lower.",
                "Switch slowly between the two a few times.",
            ),
            seconds = 60,
            tip = "Keep it silent and easy. This is about feeling the movement, not making a sound. No finger needed if you'd rather not.",
        ),
        VoiceExercise(
            "larynx-feel", "Feeling your voice box move", VoiceSkill.RESONANCE, GUIDED,
            listOf(
                "Swallow, and notice your voice box rise, then settle. A finger resting on it helps, if that's comfortable.",
                "Start a yawn, and feel it drop.",
                "Whisper 'ee', then 'oo'. It sits a little higher on 'ee' and lower on 'oo'.",
                "How high your voice box sits, and the shape of your mouth, change how bright or dark you sound.",
            ),
            seconds = 60,
            tip = "In time you'll move it by feel and by sound alone.",
        ),
        VoiceExercise(
            "whisper-siren", "Whisper siren", VoiceSkill.RESONANCE, RESONANCE,
            listOf(
                "Whisper a long 'ee', with no voice at all.",
                "Slide the whisper up, like a whistle rising. Feel your voice box lift and the sound brighten.",
                "At the top, keep that shape and say a soft, voiced 'ee'.",
                "Watch the resonance meter, and aim for your target.",
                "Rest, then repeat four or five times.",
            ),
            seconds = 120,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            goals = L_E,
            tip = "Whispering should be soft and easy. If it feels scratchy, make it quieter.",
        ),
        VoiceExercise(
            "forward-ee", "Bright and forward", VoiceSkill.RESONANCE, RESONANCE,
            listOf(
                "Rest the tip of your tongue behind your lower front teeth.",
                "Say 'ee', with the middle of your tongue high and forward, and a hint of a smile.",
                "Feel the buzz just behind your front teeth.",
                "Keep that brightness as you move through 'ee, ay, eh, ah'.",
                "Watch the resonance meter, and aim for your target.",
            ),
            seconds = 120,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            goals = L_E,
            tip = "Think of the sound as small and close to the front of your mouth. Bright, not pinched.",
        ),
        VoiceExercise(
            "yawn-space", "Yawn space", VoiceSkill.RESONANCE, RESONANCE,
            listOf(
                "Start a yawn, and notice the space it opens at the back of your mouth and throat.",
                "Keep some of that space, without the full yawn, and sigh out on a gentle 'oh'.",
                "Hold 'oh', 'aw' and 'oo' in that roomy shape.",
                "Watch the resonance meter, and aim for your target.",
                "Rest, then repeat.",
            ),
            seconds = 120,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            goals = D_E,
            tip = "Roomy, not pressed: pushing your tongue down to darken the sound makes it tight and muffled.",
        ),
        VoiceExercise(
            "big-dog-voice", "Big dog, with voice", VoiceSkill.RESONANCE, RESONANCE,
            listOf(
                "Pant slowly like a big dog, and feel your voice box settle low.",
                "Keep that low, open shape and add an easy, voiced 'huh'.",
                "Round your lips a little and move through 'huh, hoh, hoo'.",
                "Watch the resonance meter, and aim for your target.",
            ),
            seconds = 120,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            goals = D_E,
            tip = "Your throat should feel open and relaxed. If it feels like you're holding something down, ease off.",
        ),
        VoiceExercise(
            "mm-hmm", "'Mm-hmm' into words", VoiceSkill.RESONANCE, SAY,
            listOf(
                "Say a relaxed, agreeing 'mm-hmm', the way you'd answer a friend.",
                "Shape it towards your target: brighter and lighter, or darker and fuller.",
                "Carry that same feeling straight into the word that follows.",
                "If it slips, go back to 'mm-hmm' and try again.",
            ),
            seconds = 90,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            texts = listOf(
                "mm-hmm", "mm-hmm, yes", "mm-hmm, hi", "mm-hmm, okay", "mm-hmm, me too", "mm-hmm, nine", "mm-hmm, Monday",
                "mm-hmm, really", "mm-hmm, I know", "mm-hmm, of course", "mm-hmm, hello", "mm-hmm, no worries",
            ),
            tip = "'Mm-hmm' is a natural, easy sound: a handy way to set your voice before you speak, any time of day.",
        ),
        VoiceExercise(
            "vowel-hold", "Holding resonance on vowels", VoiceSkill.RESONANCE, RESONANCE,
            listOf(
                "Find your target with a short 'mm-hmm'.",
                "Hold 'ee' for three or four seconds, keeping the resonance meter near your target.",
                "Then 'ay', 'ah', 'oh' and 'oo', one at a time.",
                "Rest, and go round twice more.",
            ),
            seconds = 120,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            tip = "'Ee' reads brighter and 'oo' darker for everyone. Aim to keep each vowel nearer your target than where it starts.",
        ),
        VoiceExercise(
            "resonance-words", "Resonance into words", VoiceSkill.RESONANCE, SAY,
            listOf(
                "Set your resonance with 'mm-hmm' or a held vowel.",
                "Say each word, keeping the same shape and feeling.",
                "Glance at the meter after each word, not during it.",
            ),
            seconds = 120,
            meters = listOf(VoiceMeter.RESONANCE, PITCH),
            texts = listOf(
                "me", "see", "tea", "need", "day", "say", "may", "far", "car", "go", "show", "no", "moon", "you", "two", "hello",
                "yellow", "really", "money", "evening", "music", "window", "morning", "lovely",
            ),
            tip = "Words with 'oo' and 'oh' pull darker and words with 'ee' brighter. Notice which ones help you, and which need care.",
        ),

        // ---- 5. Vocal weight
        VoiceExercise(
            "breathy-to-clear", "Breathy to clear", VoiceSkill.WEIGHT, HOLD,
            listOf(
                "Say 'hah' with lots of air, like a sigh.",
                "Say it again a little clearer, with a little less air.",
                "Keep going in four or five steps, until the sound is clear but not pushed.",
                "Watch the weight meter as you go, then try the ladder back the other way.",
            ),
            seconds = 120,
            meters = listOf(WEIGHT, LOUDNESS),
            tip = "Clear isn't the same as loud or heavy. Keep the loudness about the same on every step.",
        ),
        VoiceExercise(
            "weight-ladder", "Light, middle, full", VoiceSkill.WEIGHT, SAY,
            listOf(
                "Count 'one, two, three' very lightly, then in the middle, then fully.",
                "Watch where each lands on the weight meter.",
                "Try the next line, aiming for the setting closest to your goal.",
            ),
            seconds = 90,
            meters = listOf(WEIGHT),
            texts = listOf("one, two, three", "four, five, six", "seven, eight, nine", "Monday, Tuesday, Wednesday", "red, orange, yellow"),
            tip = "Being able to move between light and full is the skill. Your goal decides where you spend your time.",
        ),
        VoiceExercise(
            "owl-hoots", "Light hoots", VoiceSkill.WEIGHT, GLIDE,
            listOf(
                "Hoot like a soft owl, 'hoo', on a high, light note.",
                "Slide gently down into your target range, keeping the light, thin feeling.",
                "Land on 'hoo-ee' or 'hello' at the bottom of the slide.",
                "Rest, and repeat five times.",
            ),
            seconds = 90,
            meters = listOf(WEIGHT, PITCH),
            goals = L_E,
            tip = "Notice how little effort the high hoot takes. That lightness is what you're carrying down.",
        ),
        VoiceExercise(
            "light-onsets", "Lighter onsets", VoiceSkill.WEIGHT, SAY,
            listOf(
                "Begin each word as if a tiny 'h' leads in: a soft, gentle start.",
                "Let it clear, keeping it light, as if you're talking to someone close by.",
                "Watch the weight meter stay on the lighter side.",
            ),
            seconds = 120,
            meters = listOf(WEIGHT, PITCH),
            texts = listOf(
                "hello", "hi there", "how are you", "happy", "here you go", "who's that", "have a nice day", "home", "hope so",
                "honestly", "always", "every day", "open", "easy", "only", "early", "evening", "I think so", "oh, okay", "anyway",
            ),
            goals = L_E,
            tip = "Lighter doesn't mean quieter or whispery: aim for clear and gentle.",
        ),
        VoiceExercise(
            "chest-buzz", "Chest buzz", VoiceSkill.WEIGHT, HOLD,
            listOf(
                "Hum 'mm' low in your comfortable range, with a hand resting on your chest or collarbone if that's comfortable.",
                "Feel the vibration there.",
                "Open the hum into 'mah' and 'maw', keeping the buzz.",
                "Rest, and repeat four times.",
            ),
            seconds = 90,
            meters = listOf(WEIGHT, PITCH),
            goals = D_E,
            tip = "Stay out of vocal fry (a crackly, rattly sound): it can feel deep, but it tires the voice.",
        ),
        VoiceExercise(
            "full-onsets", "Fuller onsets", VoiceSkill.WEIGHT, SAY,
            listOf(
                "If it's comfortable, rest a hand on your chest or collarbone.",
                "Start each word clearly and firmly, and feel the buzz there.",
                "Firm, never pressed: no squeeze, grit or hard knock at the start.",
                "Watch the weight meter stay on the fuller side.",
            ),
            seconds = 120,
            meters = listOf(WEIGHT, LOUDNESS),
            texts = listOf(
                "good", "great", "go ahead", "got it", "back soon", "big plans", "done", "deal", "day off", "bold", "buy one",
                "good morning", "all good", "absolutely", "on my way", "over here", "every day", "I'm ready", "no problem", "my turn",
            ),
            goals = D_E,
            tip = "Fuller comes from your vocal folds meeting more fully, not from pushing. If your throat tires, go lighter for a while.",
        ),

        // ---- 6. Intonation
        VoiceExercise(
            "melody-hums", "Melody on a hum", VoiceSkill.INTONATION, SAY,
            listOf(
                "Hum the tune of the phrase without words, the way it would sound said aloud.",
                "Then say it, keeping the same tune.",
                "Notice where it rises and where it falls.",
            ),
            seconds = 120,
            meters = listOf(INTONATION, PITCH),
            texts = listOf(
                "How are you?", "That's amazing!", "Are you sure?", "I'll see you later.", "What a lovely day.", "Oh, really?",
                "Not again!", "Thank you so much.",
            ),
            tip = "Speech has a melody of its own. Humming it first makes it easier to hear.",
        ),
        VoiceExercise(
            "question-statement", "Questions and statements", VoiceSkill.INTONATION, SAY,
            listOf(
                "Say each line as written: a statement falls at the end, and a yes-or-no question usually rises.",
                "Let the change happen on the last important word.",
                "Keep it relaxed: the movement can be small and still clear.",
            ),
            seconds = 120,
            meters = listOf(INTONATION, PITCH),
            texts = listOf(
                "It's raining.", "It's raining?", "You've finished.", "You've finished?", "That's the last one.", "That's the last one?",
                "We're meeting at noon.", "We're meeting at noon?", "They said yes.", "They said yes?", "Where are you going?",
                "What time is it?",
            ),
            tip = "Questions that start with what, where, when or how often fall at the end, like statements.",
        ),
        VoiceExercise(
            "emphasis", "Moving the emphasis", VoiceSkill.INTONATION, SAY,
            listOf(
                "Say each sentence a few times, stressing a different word each time.",
                "Notice how the meaning changes.",
                "Use whatever mix of pitch, length and loudness sounds like you.",
            ),
            seconds = 120,
            meters = listOf(INTONATION, PITCH, LOUDNESS),
            texts = listOf(
                "I left my keys on the table.", "We could go to the beach on Sunday.", "Can you bring the blue folder?",
                "I said nine o'clock, not ten.", "That's not what I ordered.", "They made the whole cake themselves.",
            ),
            tip = "Stress usually lands on the word that's new or important.",
        ),
        VoiceExercise(
            "melodic-speech", "More melody", VoiceSkill.INTONATION, SAY,
            listOf(
                "Let your voice glide up on the important word, then float back down.",
                "Use smooth glides rather than jumps.",
                "Keep the ends of statements light, rather than dropping heavily.",
            ),
            seconds = 120,
            meters = listOf(INTONATION, PITCH),
            texts = EXPRESSIVE,
            goals = L_E,
            tip = "Natural, not sing-song. A little more movement than feels normal to you often sounds just right to others.",
        ),
        VoiceExercise(
            "steady-speech", "Steadier lines", VoiceSkill.INTONATION, SAY,
            listOf(
                "Keep your pitch fairly steady, with smaller rises and falls.",
                "Give important words weight by lengthening them a little, or with a touch more loudness.",
                "Let statements settle gently downwards at the end.",
            ),
            seconds = 120,
            meters = listOf(INTONATION, PITCH, LOUDNESS),
            texts = EXPRESSIVE,
            goals = D_E,
            tip = "Steady isn't flat: keep some life in it. The intonation meter shows a calmer, more even line.",
        ),

        // ---- 7. Into everyday speech
        VoiceExercise(
            "everyday-words", "Everyday words", VoiceSkill.SPEECH, SAY,
            listOf(
                "Set your voice with a quick 'mm-hmm'.",
                "Say each word with your pitch, resonance and weight together.",
                "If one slips, reset and say it again. There's no rush.",
            ),
            seconds = 150,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT),
            texts = EVERYDAY_WORDS,
            tip = "Right first, then quicker. A few words said well beat many that slip.",
        ),
        VoiceExercise(
            "everyday-phrases", "Everyday phrases", VoiceSkill.SPEECH, SAY,
            listOf(
                "Read each phrase silently first, then say it as you would to a real person.",
                "Keep pitch, resonance and weight together through the whole phrase.",
                "Let the melody move naturally.",
            ),
            seconds = 180,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = EVERYDAY_PHRASES,
            tip = "Imagine who you're saying it to. It makes the phrase sound, and feel, real.",
        ),
        VoiceExercise(
            "sentences", "Sentences", VoiceSkill.SPEECH, SAY,
            listOf(
                "Take a breath before each sentence, and say it in one easy flow.",
                "Notice where your voice slips: often at the end, or on certain sounds.",
                "Say any that slipped once more, then move on.",
            ),
            seconds = 180,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = SENTENCES,
            tip = "The ends of sentences often drift back towards your old habits. Give them a little extra care.",
        ),
        VoiceExercise(
            "read-short", "Reading a short passage", VoiceSkill.SPEECH, READ,
            listOf(
                "Read the passage through once silently.",
                "Read it aloud at a relaxed pace, pausing at commas and full stops.",
                "Breathe at the pauses, and reset your voice if it slips.",
            ),
            seconds = 120,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = listOf(PASSAGE_BALCONY, PASSAGE_BUS),
            tip = "Pauses are your friend: each one is a chance to reset.",
        ),
        VoiceExercise(
            "read-long", "Reading a longer passage", VoiceSkill.SPEECH, READ,
            listOf(
                "Read the passage aloud at a comfortable pace.",
                "Pause and breathe at the full stops.",
                "Halfway through, check in: is your voice still where you set it? Reset if not.",
            ),
            seconds = 180,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = listOf(PASSAGE_LIGHTHOUSE, PASSAGE_PANCAKES),
            tip = "Reading as if to a friend, rather than to a room, keeps it easy and natural.",
        ),
        VoiceExercise(
            "speak-prompts", "Speaking freely", VoiceSkill.SPEECH, SPEAK,
            listOf(
                "Set your voice with 'mm-hmm', then start talking about the prompt.",
                "Talk for a minute or so. Pauses are fine.",
                "If you notice your voice slipping, pause, reset and carry on.",
                "Try two or three prompts.",
            ),
            seconds = 240,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = PROMPTS,
            tip = "Talking freely is the hardest part, because your attention goes to what you're saying. It gets easier with practice.",
        ),
        VoiceExercise(
            "phone-practice", "On the phone", VoiceSkill.SPEECH, SPEAK,
            listOf(
                "Hold your phone to your ear, or just imagine the call.",
                "Play out the call in the prompt, from the greeting to what you need.",
                "Phones flatten voices a little: keep your resonance and melody a touch clearer than feels needed.",
            ),
            seconds = 240,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = PHONE_PROMPTS,
            tip = "Calls can feel like a big deal. Practising them here, where nobody's listening, takes the edge off.",
        ),

        // ---- 8. Keeping it going
        VoiceExercise(
            "checkin", "Checking in", VoiceSkill.SPEECH, SPEAK,
            listOf(
                "Read the passage from your starting recording, in your practice voice.",
                "Then talk freely about the prompt for about 30 seconds.",
                "Look back at your starting point to see how far you've come.",
                "Be kind to yourself: notice what has changed, not only what hasn't yet.",
            ),
            seconds = 120,
            meters = listOf(PITCH, VoiceMeter.RESONANCE, WEIGHT, INTONATION),
            texts = listOf(BASELINE_PASSAGE, BASELINE_PROMPT),
            tip = "Check in every few weeks rather than every day: change is easier to see over time.",
        ),
        VoiceExercise(
            "listening", "A listening day", VoiceSkill.HEALTH, GUIDED,
            listOf(
                "Find a voice you enjoy listening to: a podcast, a show, a friend's voice note.",
                "Listen for its melody: where does it rise and fall?",
                "Listen for how bright or dark it is, and how light or full.",
                "Notice one thing you'd like to borrow. No need to use your voice today.",
            ),
            seconds = 180,
            tip = "Listening is practice too, and it's just right for rest days.",
        ),
    )

    private val BY_ID: Map<String, VoiceExercise> = EXERCISES.associateBy { it.id }

    // ---------------------------------------------------------------- Lessons

    val UNITS: List<VoiceUnit> = listOf(
        VoiceUnit(1, "Looking after your voice", "Practising safely, and a recording of where you're starting from."),
        VoiceUnit(2, "Warm-ups", "Easy ways to wake your voice up before every session."),
        VoiceUnit(3, "Pitch", "Finding a range that suits you, and landing in it."),
        VoiceUnit(4, "Resonance", "Brighter or darker: for many people, the heart of it."),
        VoiceUnit(5, "Vocal weight", "Lighter or fuller: how much buzz is in your voice."),
        VoiceUnit(6, "Intonation", "The melody of speech: rises, falls and emphasis."),
        VoiceUnit(7, "Into everyday speech", "From words to phrases and sentences, reading aloud and talking freely."),
        VoiceUnit(8, "Keeping it going", "A daily routine, rest days, and checking in on how far you've come."),
    )

    /** Active time plus a little for reading the steps and resting, for the longest path through the lesson. */
    private fun minutes(ids: List<String>, goals: Set<VoiceGoal>): Int =
        (goals.ifEmpty { VoiceGoal.entries.toSet() }).maxOf { g ->
            ceil(ids.mapNotNull { BY_ID[it] }.filter { suits(it, g) }.sumOf { it.seconds } * 1.2 / 60).toInt()
        }

    private fun lesson(id: String, unit: Int, title: String, about: String, skill: VoiceSkill, ids: List<String>, goals: Set<VoiceGoal> = emptySet()) =
        VoiceLesson(id, unit, title, about, skill, ids, minutes(ids, goals), goals)

    val LESSONS: List<VoiceLesson> = listOf(
        // 1. Looking after your voice
        lesson("care", 1, "Looking after your voice", "How to practise safely: posture, breath, water, and telling easy from strained.", VoiceSkill.HEALTH,
            listOf("posture", "breath-low", "jaw-release", "hydration", "strain-check")),
        lesson("starting-point", 1, "Your starting point", "A short recording of your everyday voice, to set your plan and to look back on later.", VoiceSkill.HEALTH,
            listOf("posture", "lip-trills", "baseline")),
        // 2. Warm-ups
        lesson("warm-trills", 2, "Trills and hums", "Easy warm-ups that get breath and voice working together.", VoiceSkill.WARM_UP,
            listOf("breath-low", "lip-trills", "trill-glides", "hum-chew", "cool-down")),
        lesson("warm-sirens", 2, "Sirens and straws", "Gentle slides and straw work that stretch your range with very little effort.", VoiceSkill.WARM_UP,
            listOf("jaw-release", "gentle-sirens", "straw", "hum-chew", "cool-down")),
        // 3. Pitch
        lesson("pitch-range", 3, "Finding your range", "Discover your easy low and high notes, and choose a range to aim for.", VoiceSkill.PITCH,
            listOf("lip-trills", "range-explore", "range-lighter", "range-deeper", "range-exploring", "hold-notes")),
        lesson("pitch-match", 3, "Matching notes", "Match tones in your target range, and hold them steady.", VoiceSkill.PITCH,
            listOf("trill-glides", "match-centre", "match-steps", "hold-notes")),
        lesson("pitch-glide", 3, "Gliding into your range", "Slide from where you usually speak into your range, then carry it into single words.", VoiceSkill.PITCH,
            listOf("gentle-sirens", "glide-into-range", "match-steps", "pitch-words")),
        // 4. Resonance
        lesson("res-feel", 4, "Feeling resonance", "Get to know how your voice box and mouth make a sound brighter or darker.", VoiceSkill.RESONANCE,
            listOf("jaw-release", "dog-pant", "larynx-feel", "vowel-hold")),
        lesson("res-bright", 4, "Brightening", "Whisper sirens and a forward 'ee' to find a brighter, smaller-sounding resonance.", VoiceSkill.RESONANCE,
            listOf("lip-trills", "dog-pant", "whisper-siren", "forward-ee", "mm-hmm"), L_E),
        lesson("res-dark", 4, "Darker and more open", "Yawn space and big-dog panting to find a darker, roomier resonance.", VoiceSkill.RESONANCE,
            listOf("lip-trills", "dog-pant", "yawn-space", "big-dog-voice", "mm-hmm"), D_E),
        lesson("res-vowels", 4, "Resonance on vowels", "Hold each vowel near your resonance target, with the meter to guide you.", VoiceSkill.RESONANCE,
            listOf("hum-chew", "whisper-siren", "yawn-space", "mm-hmm", "vowel-hold")),
        lesson("res-words", 4, "Resonance into words", "Carry your resonance from 'mm-hmm' and vowels into words.", VoiceSkill.RESONANCE,
            listOf("gentle-sirens", "mm-hmm", "vowel-hold", "resonance-words")),
        // 5. Vocal weight
        lesson("weight-feel", 5, "Light and full", "Feel the difference between a lighter and a fuller voice, and get to know the weight meter.", VoiceSkill.WEIGHT,
            listOf("lip-trills", "breathy-to-clear", "weight-ladder")),
        lesson("weight-light", 5, "A lighter voice", "Light hoots and soft onsets for a lighter, gentler sound.", VoiceSkill.WEIGHT,
            listOf("hum-chew", "owl-hoots", "breathy-to-clear", "light-onsets"), L_E),
        lesson("weight-full", 5, "A fuller voice", "Chest buzz and firmer onsets for a fuller sound, without pushing.", VoiceSkill.WEIGHT,
            listOf("hum-chew", "chest-buzz", "breathy-to-clear", "full-onsets"), D_E),
        lesson("weight-together", 5, "Pitch, resonance and weight together", "Put all three together, from 'mm-hmm' into words.", VoiceSkill.WEIGHT,
            listOf("mm-hmm", "weight-ladder", "light-onsets", "full-onsets", "resonance-words")),
        // 6. Intonation
        lesson("into-melody", 6, "Speech melody", "Hear and shape the tune of speech: rises, falls, questions and statements.", VoiceSkill.INTONATION,
            listOf("trill-glides", "melody-hums", "question-statement")),
        lesson("into-emphasis", 6, "Emphasis and expression", "Move the emphasis around, and find a way of speaking that feels like you.", VoiceSkill.INTONATION,
            listOf("gentle-sirens", "emphasis", "melodic-speech", "steady-speech")),
        // 7. Into everyday speech
        lesson("speech-words", 7, "Everyday words", "Your voice, one word at a time.", VoiceSkill.SPEECH,
            listOf("lip-trills", "mm-hmm", "everyday-words")),
        lesson("speech-phrases", 7, "Everyday phrases", "Short phrases you use every day.", VoiceSkill.SPEECH,
            listOf("hum-chew", "mm-hmm", "everyday-phrases")),
        lesson("speech-sentences", 7, "Sentences", "Longer sentences, keeping your voice with you to the end.", VoiceSkill.SPEECH,
            listOf("gentle-sirens", "mm-hmm", "sentences", "emphasis")),
        lesson("speech-reading", 7, "Reading aloud", "Read passages aloud, resetting whenever you need to.", VoiceSkill.SPEECH,
            listOf("lip-trills", "mm-hmm", "read-short", "read-long")),
        lesson("speech-free", 7, "Speaking freely", "Talk freely to a prompt: the step closest to everyday life.", VoiceSkill.SPEECH,
            listOf("hum-chew", "mm-hmm", "speak-prompts", "cool-down")),
        lesson("speech-phone", 7, "On the phone", "Practise calls where nobody's listening, so real ones feel easier.", VoiceSkill.SPEECH,
            listOf("gentle-sirens", "mm-hmm", "phone-practice", "cool-down")),
        // 8. Keeping it going
        lesson("keep-daily", 8, "Your daily routine", "A short routine to keep your voice going, on any practice day.", VoiceSkill.SPEECH,
            listOf("lip-trills", "gentle-sirens", "mm-hmm", "vowel-hold", "everyday-phrases", "cool-down")),
        lesson("keep-checkin", 8, "Checking in", "Read your starting passage again and see how far you've come. Worth doing every few weeks.", VoiceSkill.SPEECH,
            listOf("lip-trills", "hum-chew", "checkin")),
        lesson("keep-rest", 8, "Rest days", "What a day off can look like: rest, listen, and let your voice recover.", VoiceSkill.HEALTH,
            listOf("breath-low", "jaw-release", "listening")),
    )

    private val LESSONS_BY_ID: Map<String, VoiceLesson> = LESSONS.associateBy { it.id }

    // ---------------------------------------------------------------- Health and the daily routine

    val HEALTH: List<String> = listOf(
        "Warm up first, every time: a few minutes of lip trills, hums and gentle sirens.",
        "Stop if it hurts, feels scratchy or tight, or you start to sound hoarse. Rest, and come back another day, more gently.",
        "Short and regular beats long and rare: 10 to 15 minutes most days does more than an hour once a week.",
        "Take rest days. Your voice gets stronger while it rests, as muscles do.",
        "Drink water through the day. Your vocal folds are kept moist from the inside, so the hours before you practise count most.",
        "Clear your throat less: a sip of water or a gentle swallow does the job more kindly.",
        "Shouting, loud places and long calls tire any voice. Go easy on practice after them.",
        "Progress isn't a straight line. Tired, unwell or stressed voices have off days; it doesn't mean you've gone backwards.",
        "This isn't medical advice. A speech-language pathologist (SLP), ideally one experienced in gender-affirming voice care, is worth seeing if hoarseness lasts more than a couple of weeks, if speaking hurts, or if you lose your voice. Or simply if you'd like someone alongside you.",
    )

    val DAILY: List<String> = listOf(
        "Settle (1 minute): shoulders down, jaw loose, a few low breaths.",
        "Warm up (2 minutes): lip trills and gentle sirens.",
        "Find your voice (2 minutes): 'mm-hmm' into your target, then hold a few vowels.",
        "Carry it (4 minutes): words and phrases, or a page from your current lesson.",
        "Use it (1 minute): say something real in your practice voice, like a voice note or a chat with a pet.",
        "Cool down: a gentle yawn-sigh and a sip of water.",
    )

    val PROGRAM: VoiceProgram = VoiceProgram(LESSONS, EXERCISES, HEALTH, DAILY, UNITS)

    // ---------------------------------------------------------------- Finding your way

    fun exercise(id: String): VoiceExercise? = BY_ID[id]

    fun lesson(id: String): VoiceLesson? = LESSONS_BY_ID[id]

    fun suits(exercise: VoiceExercise, goal: VoiceGoal): Boolean = exercise.goals.isEmpty() || goal in exercise.goals

    fun suits(lesson: VoiceLesson, goal: VoiceGoal): Boolean = lesson.goals.isEmpty() || goal in lesson.goals

    /** The lessons for [goal], in order. */
    fun lessonsFor(goal: VoiceGoal): List<VoiceLesson> = LESSONS.filter { suits(it, goal) }

    /** What the person does in [lesson], for [goal]. */
    fun exercisesFor(lesson: VoiceLesson, goal: VoiceGoal): List<VoiceExercise> = lesson.exerciseIds.mapNotNull { BY_ID[it] }.filter { suits(it, goal) }

    /** The first lesson for [goal] not yet [done], in order (null once all are done). */
    fun nextLesson(goal: VoiceGoal, done: Set<String>): VoiceLesson? = lessonsFor(goal).firstOrNull { it.id !in done }
}
