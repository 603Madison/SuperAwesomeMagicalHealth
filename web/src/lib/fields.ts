import { create } from "zustand";
import { api } from "./api";
import type { FieldLayout, Module } from "./types";

/*
 * The field keys every app shares (shared/…/Conditions.kt, `Fields`): which parts of each log form
 * show. The server works out the effective set from the defaults, the person's conditions and
 * goals, and what they've shown or hidden by hand (GET /api/fields).
 */

export const F = {
  CONTINENCE_URGENCY: "continence.urgency",
  CONTINENCE_VOLUME: "continence.volume",
  CONTINENCE_LEAK_SIZE: "continence.leakSize",
  CONTINENCE_TRIGGERS: "continence.triggers",
  CONTINENCE_ACTIVITY: "continence.activity",
  CONTINENCE_AWARENESS: "continence.awareness",
  CONTINENCE_REACHED_TOILET: "continence.reachedToilet",
  CONTINENCE_WARNING: "continence.warning",
  CONTINENCE_BRISTOL: "continence.bristol",
  CONTINENCE_BOWEL_ACCIDENTS: "continence.bowelAccidents",
  CONTINENCE_PROTECTION: "continence.protection",
  CONTINENCE_PAD_WEIGHT: "continence.padWeight",
  CONTINENCE_CHANGES: "continence.changes",
  CONTINENCE_SKIN: "continence.skin",
  CONTINENCE_UTI_SIGNS: "continence.utiSigns",
  CONTINENCE_CATHETER: "continence.catheter",
  CONTINENCE_LOCATION: "continence.location",
  CONTINENCE_PAIN: "continence.pain",
  CONTINENCE_SUPPLIES: "continence.supplies",
  CONTINENCE_BOTHER: "continence.bother",
  CONTINENCE_BLADDER_TRAINING: "continence.bladderTraining",
  CONTINENCE_PELVIC_FLOOR: "continence.pelvicFloor",

  CYCLE_FLOW: "cycle.flow",
  CYCLE_PRODUCTS: "cycle.products",
  CYCLE_CLOTS: "cycle.clots",
  CYCLE_COLOR: "cycle.color",
  CYCLE_PAIN: "cycle.pain",
  CYCLE_PAIN_AREAS: "cycle.painAreas",
  CYCLE_SYMPTOM_SEVERITY: "cycle.symptomSeverity",
  CYCLE_MOOD: "cycle.mood",
  CYCLE_ENERGY: "cycle.energy",
  CYCLE_LIBIDO: "cycle.libido",
  CYCLE_SEX: "cycle.sex",
  CYCLE_MUCUS: "cycle.mucus",
  CYCLE_CERVIX: "cycle.cervix",
  CYCLE_OVULATION_TEST: "cycle.ovulationTest",
  CYCLE_LH: "cycle.lh",
  CYCLE_PREGNANCY_TEST: "cycle.pregnancyTest",
  CYCLE_BBT: "cycle.bbt",
  CYCLE_FERTILITY: "cycle.fertility",
  CYCLE_DISCHARGE: "cycle.discharge",

  MIND_ENERGY_BUDGET: "mind.energyBudget",

  NEURO_ABOUT_ME: "neuro.aboutMe",
  NEURO_SENSORY: "neuro.sensory",
  NEURO_OVERLOAD: "neuro.overload",
  NEURO_BURNOUT: "neuro.burnout",
  NEURO_MASKING: "neuro.masking",
  NEURO_SOCIAL: "neuro.social",
  NEURO_INTERESTS: "neuro.interests",
  NEURO_STIMS: "neuro.stims",
  NEURO_FOCUS: "neuro.focus",
  NEURO_EXECUTIVE: "neuro.executive",
  NEURO_TIME: "neuro.timeBlindness",
  NEURO_ROUTINES: "neuro.routines",
  NEURO_CARDS: "neuro.cards",
  NEURO_CALM: "neuro.calmMode",
  NEURO_MED_EFFECT: "neuro.medEffect",

  TRANSITION_MILESTONES: "transition.milestones",
  TRANSITION_CHANGES: "transition.changes",
  TRANSITION_VOICE: "transition.voice",
  TRANSITION_HAIR: "transition.hairRemoval",
  TRANSITION_MOMENTS: "transition.moments",
  TRANSITION_MEASUREMENTS: "transition.measurements",

  BODY_MEASUREMENTS: "body.measurements",
  NUTRITION_WATER: "nutrition.water",
} as const;

/** On for everyone who has the feature, until hidden (the server's Fields.DEFAULTS). */
export const DEFAULT_FIELDS: string[] = [
  F.CONTINENCE_URGENCY, F.CONTINENCE_VOLUME, F.CONTINENCE_LEAK_SIZE, F.CONTINENCE_TRIGGERS, F.CONTINENCE_BRISTOL, F.CONTINENCE_PELVIC_FLOOR,
  F.CYCLE_FLOW, F.CYCLE_PAIN, F.CYCLE_SYMPTOM_SEVERITY, F.CYCLE_MUCUS, F.CYCLE_OVULATION_TEST, F.CYCLE_BBT, F.CYCLE_FERTILITY,
  F.NEURO_ABOUT_ME, F.NEURO_CALM, F.NEURO_CARDS,
  F.TRANSITION_MILESTONES, F.TRANSITION_CHANGES, F.TRANSITION_MOMENTS,
  F.NUTRITION_WATER,
];

/** Every field, in the order settings pages list them: its feature and label. */
export const ALL_FIELDS: { key: string; module: Module; label: string }[] = [
  { key: F.CONTINENCE_URGENCY, module: "CONTINENCE", label: "How urgent it was" },
  { key: F.CONTINENCE_VOLUME, module: "CONTINENCE", label: "How much (ml)" },
  { key: F.CONTINENCE_LEAK_SIZE, module: "CONTINENCE", label: "How big a leak" },
  { key: F.CONTINENCE_TRIGGERS, module: "CONTINENCE", label: "What set it off" },
  { key: F.CONTINENCE_ACTIVITY, module: "CONTINENCE", label: "What you were doing" },
  { key: F.CONTINENCE_AWARENESS, module: "CONTINENCE", label: "Whether you felt it" },
  { key: F.CONTINENCE_REACHED_TOILET, module: "CONTINENCE", label: "Whether you reached the toilet" },
  { key: F.CONTINENCE_WARNING, module: "CONTINENCE", label: "How much warning you had" },
  { key: F.CONTINENCE_BRISTOL, module: "CONTINENCE", label: "Stool type (Bristol scale)" },
  { key: F.CONTINENCE_BOWEL_ACCIDENTS, module: "CONTINENCE", label: "Bowel accidents" },
  { key: F.CONTINENCE_PROTECTION, module: "CONTINENCE", label: "What you were wearing, and how full it was" },
  { key: F.CONTINENCE_PAD_WEIGHT, module: "CONTINENCE", label: "Pad weight (grams)" },
  { key: F.CONTINENCE_CHANGES, module: "CONTINENCE", label: "Clothes or bedding changed" },
  { key: F.CONTINENCE_SKIN, module: "CONTINENCE", label: "Skin comfort" },
  { key: F.CONTINENCE_UTI_SIGNS, module: "CONTINENCE", label: "Signs of a urine infection" },
  { key: F.CONTINENCE_CATHETER, module: "CONTINENCE", label: "Catheter use" },
  { key: F.CONTINENCE_LOCATION, module: "CONTINENCE", label: "Where you were" },
  { key: F.CONTINENCE_PAIN, module: "CONTINENCE", label: "Pain" },
  { key: F.CONTINENCE_SUPPLIES, module: "CONTINENCE", label: "Supplies on hand" },
  { key: F.CONTINENCE_BOTHER, module: "CONTINENCE", label: "How much it bothers you (weekly)" },
  { key: F.CONTINENCE_BLADDER_TRAINING, module: "CONTINENCE", label: "Bladder training" },
  { key: F.CONTINENCE_PELVIC_FLOOR, module: "CONTINENCE", label: "Pelvic floor exercises" },
  { key: F.CYCLE_FLOW, module: "CYCLE", label: "Bleeding" },
  { key: F.CYCLE_PRODUCTS, module: "CYCLE", label: "Period products used" },
  { key: F.CYCLE_CLOTS, module: "CYCLE", label: "Clots" },
  { key: F.CYCLE_COLOR, module: "CYCLE", label: "Colour" },
  { key: F.CYCLE_PAIN, module: "CYCLE", label: "Pain" },
  { key: F.CYCLE_PAIN_AREAS, module: "CYCLE", label: "Where it hurts" },
  { key: F.CYCLE_SYMPTOM_SEVERITY, module: "CYCLE", label: "How bad each symptom was" },
  { key: F.CYCLE_MOOD, module: "CYCLE", label: "Mood" },
  { key: F.CYCLE_ENERGY, module: "CYCLE", label: "Energy" },
  { key: F.CYCLE_LIBIDO, module: "CYCLE", label: "Sex drive" },
  { key: F.CYCLE_SEX, module: "CYCLE", label: "Sex" },
  { key: F.CYCLE_MUCUS, module: "CYCLE", label: "Cervical mucus" },
  { key: F.CYCLE_CERVIX, module: "CYCLE", label: "Cervix position" },
  { key: F.CYCLE_OVULATION_TEST, module: "CYCLE", label: "Ovulation tests" },
  { key: F.CYCLE_LH, module: "CYCLE", label: "LH level" },
  { key: F.CYCLE_PREGNANCY_TEST, module: "CYCLE", label: "Pregnancy tests" },
  { key: F.CYCLE_BBT, module: "CYCLE", label: "Basal temperature" },
  { key: F.CYCLE_FERTILITY, module: "CYCLE", label: "Fertile window and ovulation" },
  { key: F.CYCLE_DISCHARGE, module: "CYCLE", label: "Discharge" },
  { key: F.MIND_ENERGY_BUDGET, module: "MIND", label: "Energy budget (spoons)" },
  { key: F.NEURO_ABOUT_ME, module: "NEURO", label: "About me" },
  { key: F.NEURO_SENSORY, module: "NEURO", label: "Sensory profile and overload" },
  { key: F.NEURO_OVERLOAD, module: "NEURO", label: "Meltdowns and shutdowns" },
  { key: F.NEURO_BURNOUT, module: "NEURO", label: "Burnout" },
  { key: F.NEURO_MASKING, module: "NEURO", label: "Masking" },
  { key: F.NEURO_SOCIAL, module: "NEURO", label: "Social battery" },
  { key: F.NEURO_INTERESTS, module: "NEURO", label: "Special interests" },
  { key: F.NEURO_STIMS, module: "NEURO", label: "Stims that help" },
  { key: F.NEURO_FOCUS, module: "NEURO", label: "Focus and body doubling" },
  { key: F.NEURO_EXECUTIVE, module: "NEURO", label: "Executive function check-in" },
  { key: F.NEURO_TIME, module: "NEURO", label: "Time estimates" },
  { key: F.NEURO_ROUTINES, module: "NEURO", label: "Routines" },
  { key: F.NEURO_CARDS, module: "NEURO", label: "Communication cards" },
  { key: F.NEURO_CALM, module: "NEURO", label: "Calm mode" },
  { key: F.NEURO_MED_EFFECT, module: "NEURO", label: "How medication worked" },
  { key: F.TRANSITION_MILESTONES, module: "TRANSITION", label: "Milestones" },
  { key: F.TRANSITION_CHANGES, module: "TRANSITION", label: "Changes you notice" },
  { key: F.TRANSITION_VOICE, module: "TRANSITION", label: "Voice training" },
  { key: F.TRANSITION_HAIR, module: "TRANSITION", label: "Hair removal" },
  { key: F.TRANSITION_MOMENTS, module: "TRANSITION", label: "Euphoria and dysphoria" },
  { key: F.TRANSITION_MEASUREMENTS, module: "TRANSITION", label: "Body measurements" },
  { key: F.BODY_MEASUREMENTS, module: "BODY", label: "Body measurements" },
  { key: F.NUTRITION_WATER, module: "NUTRITION", label: "Water" },
];

export const fieldLabel = (key: string) => ALL_FIELDS.find((f) => f.key === key)?.label ?? key;

/** The person's field layout, loaded once and kept for every page; null until loaded. */
interface FieldStore {
  layout: FieldLayout | null;
  loading: boolean;
}

export const useFieldStore = create<FieldStore>(() => ({ layout: null, loading: false }));

let pending: Promise<void> | null = null;

/** Loads (or reloads) the layout; pages call it through [useFields]. */
export function loadFields(force = false): Promise<void> {
  if (pending && !force) return pending;
  useFieldStore.setState({ loading: true });
  pending = api.fields()
    .then((layout) => useFieldStore.setState({ layout, loading: false }))
    // An older server without /api/fields: the defaults show, so nothing disappears.
    .catch(() => useFieldStore.setState({ layout: { effective: DEFAULT_FIELDS, show: [], hide: [], because: {} }, loading: false }));
  return pending;
}

export function setFieldLayout(layout: FieldLayout) {
  useFieldStore.setState({ layout });
}

/** A check for whether a field shows: `has(F.CYCLE_CLOTS)`. Loads the layout the first time. */
export function useFields(): { has: (key: string) => boolean; layout: FieldLayout | null } {
  const layout = useFieldStore((s) => s.layout);
  if (!layout && !pending) void loadFields();
  const effective = layout?.effective ?? DEFAULT_FIELDS;
  return { has: (key: string) => effective.includes(key), layout };
}

/** Shows or hides one field by hand, keeping the person's other choices (PUT /api/fields). */
export async function setField(key: string, on: boolean): Promise<void> {
  const layout = useFieldStore.getState().layout ?? (await loadFields(), useFieldStore.getState().layout);
  const show = new Set(layout?.show ?? []);
  const hide = new Set(layout?.hide ?? []);
  show.delete(key);
  hide.delete(key);
  if (on) show.add(key); else hide.add(key);
  setFieldLayout(await api.saveFields({ show: [...show], hide: [...hide] }));
}

/**
 * Whether water shows (Food's `nutrition.water` field, on by default): the quick button, the goal,
 * the water card and chart, the water drink and habit, and the watch's water. Off, drinks still
 * count caffeine and alcohol.
 */
export function useWater(modules: Module[]): boolean {
  const { has } = useFields();
  return modules.includes("NUTRITION") && has(F.NUTRITION_WATER);
}
