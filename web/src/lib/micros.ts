import type { ExtraNutrient, FoodEntry, MicroAim, Micronutrient, NutrientTargets, Nutrients, NutritionTarget, TrendDay } from "./types";

/*
 * Vitamins, minerals, fibre and the finer fats, as the Food page, foods, Trends and the targets
 * settings show them (npm test covers this). Food.kt's Micronutrient and ExtraNutrient, mirrored:
 * keep them in step. Type imports only, so node can run it as it is.
 */

/** A nutrient's key: one of Nutrients' own (fibre, sugar, saturated fat, sodium) or a micronutrient. */
export type NutrientKey = ExtraNutrient | Micronutrient;

/** How the Food page groups them: fibre and sugar first, then fats, minerals and vitamins. */
export type NutrientGroup = "FIBER_SUGAR" | "FATS" | "MINERALS" | "VITAMINS";

export interface NutrientInfo {
  key: NutrientKey;
  label: string;
  unit: "g" | "mg" | "µg";
  group: NutrientGroup;
  aim: MicroAim;
}

const n = (key: NutrientKey, label: string, unit: NutrientInfo["unit"], group: NutrientGroup, aim: MicroAim = "AT_LEAST"): NutrientInfo => ({ key, label, unit, group, aim });

/** Every nutrient, in the order they show (each group in turn). */
export const NUTRIENTS: NutrientInfo[] = [
  n("FIBER", "Fibre", "g", "FIBER_SUGAR"),
  n("SUGAR", "Sugar", "g", "FIBER_SUGAR", "AT_MOST"),
  n("SATURATED_FAT", "Saturated fat", "g", "FATS", "AT_MOST"),
  n("TRANS_FAT", "Trans fat", "g", "FATS", "AT_MOST"),
  n("MONO_FAT", "Monounsaturated fat", "g", "FATS"),
  n("POLY_FAT", "Polyunsaturated fat", "g", "FATS"),
  n("OMEGA_3", "Omega-3", "g", "FATS"),
  n("CHOLESTEROL", "Cholesterol", "mg", "FATS", "AT_MOST"),
  n("SODIUM", "Sodium", "mg", "MINERALS", "AT_MOST"),
  n("POTASSIUM", "Potassium", "mg", "MINERALS"),
  n("CALCIUM", "Calcium", "mg", "MINERALS"),
  n("IRON", "Iron", "mg", "MINERALS"),
  n("MAGNESIUM", "Magnesium", "mg", "MINERALS"),
  n("ZINC", "Zinc", "mg", "MINERALS"),
  n("PHOSPHORUS", "Phosphorus", "mg", "MINERALS"),
  n("SELENIUM", "Selenium", "µg", "MINERALS"),
  n("IODINE", "Iodine", "µg", "MINERALS"),
  n("VITAMIN_A", "Vitamin A", "µg", "VITAMINS"),
  n("VITAMIN_C", "Vitamin C", "mg", "VITAMINS"),
  n("VITAMIN_D", "Vitamin D", "µg", "VITAMINS"),
  n("VITAMIN_E", "Vitamin E", "mg", "VITAMINS"),
  n("VITAMIN_K", "Vitamin K", "µg", "VITAMINS"),
  n("THIAMIN", "Thiamin (B1)", "mg", "VITAMINS"),
  n("RIBOFLAVIN", "Riboflavin (B2)", "mg", "VITAMINS"),
  n("NIACIN", "Niacin (B3)", "mg", "VITAMINS"),
  n("VITAMIN_B6", "Vitamin B6", "mg", "VITAMINS"),
  n("FOLATE", "Folate", "µg", "VITAMINS"),
  n("VITAMIN_B12", "Vitamin B12", "µg", "VITAMINS"),
];

export const GROUPS: { id: NutrientGroup; label: string }[] = [
  { id: "FIBER_SUGAR", label: "Fibre and sugar" },
  { id: "FATS", label: "Fats" },
  { id: "MINERALS", label: "Minerals" },
  { id: "VITAMINS", label: "Vitamins" },
];

/** The few the Food page shows before "Show all". */
export const MAIN_FEW: NutrientKey[] = ["FIBER", "SODIUM", "IRON", "CALCIUM", "VITAMIN_D", "POTASSIUM"];

const EXTRAS: ExtraNutrient[] = ["FIBER", "SUGAR", "SATURATED_FAT", "SODIUM"];

export const infoOf = (key: NutrientKey): NutrientInfo => NUTRIENTS.find((x) => x.key === key)!;

const isExtra = (key: NutrientKey): key is ExtraNutrient => (EXTRAS as string[]).includes(key);

/** How much of [key] there is in [n], or undefined where it isn't known (never 0 for unknown). */
export function amountOf(n: Nutrients | TrendDay | undefined, key: NutrientKey): number | undefined {
  if (!n) return undefined;
  if (isExtra(key)) {
    const v = key === "FIBER" ? n.fiber : key === "SUGAR" ? n.sugar : key === "SATURATED_FAT" ? n.saturatedFat : n.sodiumMg;
    return v ?? undefined;
  }
  return n.micros?.[key] ?? undefined;
}

export interface Target {
  amount: number;
  upper?: number;
  custom: boolean;
}

/** The day's target for [key], where there is one. */
export function targetOf(t: NutritionTarget | undefined, key: NutrientKey): Target | undefined {
  if (!t) return undefined;
  if (isExtra(key)) {
    const amount = key === "FIBER" ? t.fiberG : key === "SUGAR" ? t.sugarG : key === "SATURATED_FAT" ? t.saturatedFatG : t.sodiumMg;
    return amount != null ? { amount, custom: false } : undefined;
  }
  const m = t.micros?.find((x) => x.micro === key);
  return m ? { amount: m.amount, upper: m.upper, custom: m.custom } : undefined;
}

/** "4.2", "135", "0.63", "1,200": enough figures to mean something, few enough to read. */
export function amountText(v: number): string {
  const digits = v >= 100 ? 0 : v >= 10 ? 1 : v >= 1 ? 1 : 2;
  return v.toLocaleString(undefined, { maximumFractionDigits: digits });
}

/** "4.2 mg". */
export const withUnit = (v: number, unit: string) => `${amountText(v)} ${unit}`;

/** Whether an entry carried any vitamin or mineral (the micronutrients; fibre and the like don't count here). */
export const carriesMicros = (e: FoodEntry) => Object.values(e.nutrients.micros ?? {}).some((v) => v != null);

/** "Vitamins and minerals came with 3 of 7 foods today": how complete the day's numbers are; null with nothing logged. */
export function coverageText(entries: FoodEntry[], isToday: boolean): string | null {
  if (entries.length === 0) return null;
  const carrying = entries.filter(carriesMicros).length;
  const when = isToday ? "today" : "that day";
  if (carrying === 0) return `None of the foods ${when} came with vitamins or minerals. Ones found by search or barcode often do.`;
  if (carrying === entries.length) return entries.length === 1 ? `Vitamins and minerals came with the one food ${when}.` : `Vitamins and minerals came with all ${entries.length} foods ${when}.`;
  return `Vitamins and minerals came with ${carrying} of ${entries.length} foods ${when}. Only those count.`;
}

export type RowState = "none" | "plain" | "under-target" | "met" | "under-limit" | "over-limit";

export interface Row {
  info: NutrientInfo;
  amount?: number;
  target?: Target;
  state: RowState;
  /** The bar: how far towards the target (or the limit), 0–1; undefined without one. */
  fraction?: number;
  /** "4.2 of 18 mg", "1,450 mg, under your limit", "No data". */
  text: string;
  /** A gentle note: above the upper limit, or over a limit. */
  note?: string;
}

/** One nutrient's row: its amount against its target, in words. */
export function row(key: NutrientKey, totals: Nutrients | undefined, target: NutritionTarget | undefined): Row {
  const info = infoOf(key);
  const amount = amountOf(totals, key);
  const t = targetOf(target, key);
  if (amount == null) return { info, target: t, state: "none", text: "No data" };
  const fraction = t && t.amount > 0 ? Math.min(1, amount / t.amount) : undefined;
  if (!t) return { info, amount, state: "plain", text: withUnit(amount, info.unit) };
  if (info.aim === "AT_MOST") {
    return amount <= t.amount
      ? { info, amount, target: t, fraction, state: "under-limit", text: `${withUnit(amount, info.unit)}, under your limit` }
      : { info, amount, target: t, fraction, state: "over-limit", text: `${withUnit(amount, info.unit)} of ${withUnit(t.amount, info.unit)}`, note: "A little over your limit today" };
  }
  const note = t.upper != null && amount > t.upper ? `Above the ${withUnit(t.upper, info.unit)} upper limit: worth a look if it happens often` : undefined;
  return { info, amount, target: t, fraction, state: amount >= t.amount ? "met" : "under-target", text: `${amountText(amount)} of ${withUnit(t.amount, info.unit)}`, note };
}

/** The Food page's rows: the main few, or every nutrient by group. */
export function rows(totals: Nutrients | undefined, target: NutritionTarget | undefined, all: boolean): Row[] {
  return (all ? NUTRIENTS.map((x) => x.key) : MAIN_FEW).map((key) => row(key, totals, target));
}

/** What a food carries, in a line: "Fibre 2.7 g · Iron 4.2 mg · Calcium 135 mg" (the main few first), or null. */
export function carriedText(n: Nutrients, limit = 99): string | null {
  const order = [...MAIN_FEW.map(infoOf), ...NUTRIENTS.filter((x) => !MAIN_FEW.includes(x.key))];
  const parts = order.map((x) => {
    const v = amountOf(n, x.key);
    return v == null ? null : `${x.label} ${withUnit(v, x.unit)}`;
  }).filter((x): x is string => x != null);
  if (parts.length === 0) return null;
  return parts.length > limit ? `${parts.slice(0, limit).join(" · ")} · and ${parts.length - limit} more` : parts.join(" · ");
}

/** [n] times [factor], micros and all (a serving's amount for a number of servings). */
export function scaled(n: Nutrients, factor: number): Nutrients {
  const opt = (v: number | undefined) => (v == null ? undefined : v * factor);
  return {
    calories: n.calories * factor, protein: n.protein * factor, carbs: n.carbs * factor, fat: n.fat * factor,
    fiber: opt(n.fiber), sugar: opt(n.sugar), saturatedFat: opt(n.saturatedFat), sodiumMg: opt(n.sodiumMg),
    micros: n.micros ? Object.fromEntries(Object.entries(n.micros).map(([k, v]) => [k, (v ?? 0) * factor])) : undefined,
  };
}

/**
 * Typed values ("More nutrients": key to text) as Nutrients' fields: blank ones left out, never
 * sent as 0. An error names the first that isn't a number of nothing or more.
 */
export function typedNutrients(values: Partial<Record<NutrientKey, string>>): { extras: Pick<Nutrients, "fiber" | "sugar" | "saturatedFat" | "sodiumMg">; micros: Nutrients["micros"]; error?: string } {
  const extras: Pick<Nutrients, "fiber" | "sugar" | "saturatedFat" | "sodiumMg"> = {};
  const micros: Partial<Record<Micronutrient, number>> = {};
  for (const info of NUTRIENTS) {
    const text = values[info.key]?.trim().replace(",", ".");
    if (!text) continue;
    const v = Number(text);
    if (!Number.isFinite(v) || v < 0) return { extras, micros, error: `${info.label} needs a number of 0 or more` };
    if (info.key === "FIBER") extras.fiber = v;
    else if (info.key === "SUGAR") extras.sugar = v;
    else if (info.key === "SATURATED_FAT") extras.saturatedFat = v;
    else if (info.key === "SODIUM") extras.sodiumMg = v;
    else micros[info.key as Micronutrient] = v;
  }
  return { extras, micros: Object.keys(micros).length ? micros : undefined };
}

/** A nutrient's daily intake for a chart: undefined on days nothing carried it (a gap, not a zero). */
export const trendValue = (d: TrendDay, key: NutrientKey) => amountOf(d, key);

/** The targets settings' line for one nutrient: its reference (with the upper limit) or that there isn't one. */
export function referenceText(item: NutrientTargets["items"][number]): string {
  const info = infoOf(item.key);
  if (item.reference == null) return "No reference intake";
  const head = info.aim === "AT_MOST" ? `Limit ${withUnit(item.reference, info.unit)}` : `Reference ${withUnit(item.reference, info.unit)}`;
  return item.upper != null ? `${head} · upper limit ${withUnit(item.upper, info.unit)}` : head;
}
