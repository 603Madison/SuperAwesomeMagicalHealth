import type { CompareKey, ComparedMetric, ComparedNight, DeviceComparison, DeviceKind, RecentDevice, SleepSession, Units } from "./types";

/*
 * Two devices side by side (GET /api/devices/compare, pages/Compare.tsx), the parts that don't need
 * React (npm test covers them): each device's short name, each value in its unit, how far apart they
 * read in plain words, and each night's span and rows. Type imports only, so node can run it as it is.
 */

/** The page's sections in the default order. Their ids are saved (layoutKey("compare")): never rename one. */
export const COMPARE_SECTIONS = ["summary", "trend", "nights"] as const;
export type CompareSection = (typeof COMPARE_SECTIONS)[number];

/** The spans offered, in nights; the server takes 1–90. */
export const COMPARE_SPANS = [7, 14, 30] as const;
export const COMPARE_DEFAULT_NIGHTS = 14;

/** The nights asked for in the address (?days=), within what the server takes. */
export function nightsParam(value: string | null): number {
  const n = value == null || value.trim() === "" ? Number.NaN : Number(value);
  if (!Number.isFinite(n)) return COMPARE_DEFAULT_NIGHTS;
  return Math.min(90, Math.max(1, Math.round(n)));
}

export type CompareUnit = "min" | "clock" | "bpm" | "ms" | "%" | "/min" | "°C" | "steps";

/** Each thing compared: its name and unit, as the server's CompareKey has them, in the server's order. */
export const COMPARE_KEYS: Record<CompareKey, { label: string; unit: CompareUnit }> = {
  ASLEEP: { label: "Time asleep", unit: "min" },
  DEEP: { label: "Deep sleep", unit: "min" },
  LIGHT: { label: "Light sleep", unit: "min" },
  REM: { label: "REM sleep", unit: "min" },
  AWAKE: { label: "Awake in the night", unit: "min" },
  BEDTIME: { label: "Fell asleep", unit: "clock" },
  WAKE: { label: "Woke up", unit: "clock" },
  NIGHT_HEART_RATE: { label: "Heart rate overnight", unit: "bpm" },
  LOWEST_HEART_RATE: { label: "Lowest heart rate overnight", unit: "bpm" },
  HRV: { label: "HRV overnight", unit: "ms" },
  SPO2: { label: "Blood oxygen overnight", unit: "%" },
  RESPIRATORY_RATE: { label: "Breathing rate overnight", unit: "/min" },
  SKIN_TEMPERATURE: { label: "Skin temperature overnight", unit: "°C" },
  DAY_HEART_RATE: { label: "Heart rate in the day", unit: "bpm" },
  STEPS: { label: "Steps", unit: "steps" },
};

const ORDER = Object.keys(COMPARE_KEYS) as CompareKey[];

/** Whether this version knows [key] (a newer server's are left out). */
export const knownKey = (key: string): key is CompareKey => Object.prototype.hasOwnProperty.call(COMPARE_KEYS, key);

/** Counted by the day rather than the night. */
const BY_DAY: CompareKey[] = ["STEPS", "DAY_HEART_RATE"];

/** Nights (or days) before the page says how two devices usually differ, as the server's DeviceComparison.ENOUGH. */
export const ENOUGH = 3;

/** "1 day", "9 nights". */
function counted(key: CompareKey, n: number, locale?: string): string {
  const word = BY_DAY.includes(key) ? (n === 1 ? "day" : "days") : n === 1 ? "night" : "nights";
  return `${n.toLocaleString(locale)} ${word}`;
}

/** "the one hour", "the 5 hours": the hours both devices were counting steps. */
function hoursBoth(n: number, locale?: string): string {
  return n === 1 ? "the one hour" : `the ${n.toLocaleString(locale)} hours`;
}

// ---- Which device is which ----

export type Placement = "ring" | "watch" | "strap" | "phone";

const CHEST_WORDS = ["strap", "chest", "hrm", "tickr"];
/** Strap models: Polar H7, H9 and H10, Coospo H6 and H808S, Magene H64 and the like. */
const STRAP_MODEL = /^h[0-9]{1,3}s?$/;
const WRIST_WORDS = ["watch", "band", "pebble", "garmin", "fitbit", "amazfit", "bip", "gtr", "gts", "huawei", "polar", "suunto", "withings", "bangle", "pinetime"];

/** Where a device is worn, the way the server tells (Combine.placement): by its kind, or by its name. */
export function placement(kind: DeviceKind | undefined, name: string): Placement | null {
  switch (kind) {
    case "COLMI_RING": return "ring";
    case "PEBBLE": case "GARMIN": return "watch";
    case "HEART_RATE_STRAP": return "strap";
    case "PHONE": return "phone";
  }
  const words = name.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
  // Before the wrist: "Polar H10" is a strap, though Polar make watches too.
  if (words.some((w) => CHEST_WORDS.includes(w) || STRAP_MODEL.test(w))) return "strap";
  if (words.some((w) => w === "ring" || w === "oura" || w === "colmi")) return "ring";
  if (words.some((w) => WRIST_WORDS.includes(w))) return "watch";
  if (words.includes("phone")) return "phone";
  return null;
}

/** What the page calls each device: "ring" and "strap" where that tells them apart, otherwise their names. */
export interface ShortNames {
  a: string;
  b: string;
  /** Words ("ring", "strap"): said with "the". Names: as they are. */
  words: boolean;
}

export function shortNames(a?: Pick<RecentDevice, "name" | "kind">, b?: Pick<RecentDevice, "name" | "kind">): ShortNames {
  const pa = a ? placement(a.kind, a.name) : null;
  const pb = b ? placement(b.kind, b.name) : null;
  if (pa && pb && pa !== pb) return { a: pa, b: pb, words: true };
  const na = a?.name.trim() || "The first device";
  const nb = b?.name.trim() || "The second device";
  return na === nb ? { a: `${na} (1)`, b: `${nb} (2)`, words: false } : { a: na, b: nb, words: false };
}

/** A device in a sentence: "the ring" ("The ring" to start one), or its name. */
export function called(names: ShortNames, which: "a" | "b", start = false): string {
  const name = names[which];
  if (!names.words) return name;
  return `${start ? "The" : "the"} ${name}`;
}

/** A device on its own, as a heading or a column: "Ring", or its name. */
export function title(names: ShortNames, which: "a" | "b"): string {
  const name = names[which];
  return names.words ? name.charAt(0).toUpperCase() + name.slice(1) : name;
}

// ---- Values in their units ----

/** How the page writes times and numbers: the person's clock, units and (in tests) locale. */
export interface CompareFormat {
  /** A moment as the person's clock shows it ("11:42 PM"). */
  time: (ms: number) => string;
  /** When it was 6 pm on the evening before [date] (YYYY-MM-DD), where the person is. */
  evening: (date: string) => number;
  units?: Units;
  locale?: string;
}

const MINUS = "−";

function number(v: number, places: number, locale?: string): string {
  return v.toLocaleString(locale, { minimumFractionDigits: places, maximumFractionDigits: places });
}

/** No-break space: a number stays with its unit when a narrow column wraps ("7 h" / "12 min"). */
const NB = " ";

/** "7 h 12 min", "45 min", "8 h". */
export function duration(minutes: number): string {
  const m = Math.round(Math.abs(minutes));
  if (m < 60) return `${m}${NB}min`;
  const h = Math.floor(m / 60);
  return m % 60 === 0 ? `${h}${NB}h` : `${h}${NB}h ${m % 60}${NB}min`;
}

/** The decimals a unit is written with. */
function decimals(unit: CompareUnit): number {
  return unit === "%" || unit === "/min" || unit === "°C" ? 1 : 0;
}

const fahrenheit = (fmt: CompareFormat) => fmt.units === "IMPERIAL";

/** A temperature (or, with [difference], a change in one) in the person's units. */
function degrees(celsius: number, fmt: CompareFormat, difference: boolean): number {
  if (!fahrenheit(fmt)) return celsius;
  return difference ? celsius * 1.8 : celsius * 1.8 + 32;
}

const degreeUnit = (fmt: CompareFormat) => (fahrenheit(fmt) ? "°F" : "°C");

/**
 * A value with its unit: "7 h 12 min", "11:42 PM", "58 bpm", "96.4%", "14.2 per min", "34.1 °C",
 * "8,240 steps". A time is minutes after 6 pm on the evening before [date].
 */
export function valueText(key: CompareKey, v: number, fmt: CompareFormat, date: string): string {
  const unit = COMPARE_KEYS[key].unit;
  switch (unit) {
    case "min": return duration(v);
    case "clock": return fmt.time(fmt.evening(date) + Math.round(v) * 60_000);
    case "%": return `${number(v, 1, fmt.locale)}%`;
    case "/min": return `${number(v, 1, fmt.locale)} per${NB}min`;
    case "°C": return `${number(degrees(v, fmt, false), 1, fmt.locale)}${NB}${degreeUnit(fmt)}`;
    case "steps": return `${number(v, 0, fmt.locale)} steps`;
    default: return `${number(v, 0, fmt.locale)}${NB}${unit}`;
  }
}

/** Whether a difference is nothing at all, as written. */
export function noDifference(key: CompareKey, d: number, fmt: CompareFormat): boolean {
  const unit = COMPARE_KEYS[key].unit;
  const shown = unit === "°C" ? degrees(d, fmt, true) : d;
  const scale = 10 ** decimals(unit);
  return Math.round(Math.abs(shown) * scale) === 0;
}

/** A difference's size without its sign: "3", "0.4", "1,240", "12 min", "1 h 5 min". */
function size(key: CompareKey, d: number, fmt: CompareFormat): string {
  const unit = COMPARE_KEYS[key].unit;
  if (unit === "min" || unit === "clock") return duration(d);
  const v = unit === "°C" ? degrees(Math.abs(d), fmt, true) : Math.abs(d);
  return number(v, decimals(unit), fmt.locale);
}

/** A difference with its sign, as the table and the range write it: "+3", "−0.4", "+12 min", "±0". */
export function signedText(key: CompareKey, d: number, fmt: CompareFormat): string {
  if (noDifference(key, d, fmt)) return `±${size(key, 0, fmt)}`;
  return `${d > 0 ? "+" : MINUS}${size(key, d, fmt)}`;
}

/** The unit after a difference's size in a sentence: " bpm", "%", " °C". */
function sentenceUnit(key: CompareKey, fmt: CompareFormat): string {
  switch (COMPARE_KEYS[key].unit) {
    case "bpm": return " bpm";
    case "ms": return " ms";
    case "°C": return ` ${degreeUnit(fmt)}`;
    case "/min": return " breaths a minute";
    case "%": return " points";
    default: return "";
  }
}

/**
 * How far apart two devices read on one thing, in plain words, as parts to join with " · ":
 * "The ring read 3 bpm higher than the strap", "4 apart on average", "from −1 to +7", "9 nights".
 * HRV also as a share of [a]'s average: "6 ms (12%) lower". Steps as a share too, over only the
 * hours both were counting: "In the 5 hours both were counting, the ring counted 83% more steps
 * than the phone". With fewer than [ENOUGH] nights, it says it's early rather than how far apart
 * they usually are.
 */
export function metricSentence(m: ComparedMetric, names: ShortNames, fmt: CompareFormat): string[] {
  const unit = COMPARE_KEYS[m.key].unit;
  const b = called(names, "b", true);
  const a = called(names, "a");
  const d = m.difference;
  const hours = m.key === "STEPS" && (m.pairs ?? 0) > 0 ? m.pairs! : 0;
  let lead: string;
  if (unit === "steps") {
    const share = m.a > 0 ? Math.round((Math.abs(d) / m.a) * 100) : 0;
    // As a share of a's; without one to share out, the difference itself.
    const amount = share > 0 ? `${share}% ${d > 0 ? "more" : "fewer"} steps`
      : m.a > 0 || noDifference(m.key, d, fmt) ? "" : `${size(m.key, d, fmt)} ${d > 0 ? "more" : "fewer"} steps`;
    const who = hours > 0 ? called(names, "b") : b;
    const said = amount ? `${who} counted ${amount} than ${a}` : `${who} and ${a} counted about the same steps`;
    lead = hours > 0 ? `In ${hoursBoth(hours, fmt.locale)} both were counting, ${said}` : said;
  } else if (noDifference(m.key, d, fmt)) {
    lead = `${b} and ${a} read the same on average`;
  } else {
    const amount = size(m.key, d, fmt);
    const up = d > 0;
    switch (unit) {
      case "min": lead = `${b} counted ${amount} ${up ? "more" : "less"} than ${a}`; break;
      case "clock": lead = `${b} put it ${amount} ${up ? "later" : "earlier"} than ${a}`; break;
      default: {
        const share = m.key === "HRV" && m.a > 0 ? ` (${Math.round((Math.abs(d) / m.a) * 100)}%)` : "";
        lead = `${b} read ${amount}${sentenceUnit(m.key, fmt)}${share} ${up ? "higher" : "lower"} than ${a}`;
      }
    }
  }
  const parts = [lead];
  if (m.count < ENOUGH) {
    parts.push(`only ${counted(m.key, m.count, fmt.locale)} so far: a few more will show what's usual`);
    return parts;
  }
  // The range's ends: "−1", "+7", and a night with no difference as plain "0".
  const end = (v: number) => (noDifference(m.key, v, fmt) ? size(m.key, 0, fmt) : signedText(m.key, v, fmt));
  if (unit !== "steps") {
    parts.push(`${size(m.key, m.apart, fmt)} apart on average`);
    if (m.lowest !== m.highest) parts.push(`from ${end(m.lowest)} to ${end(m.highest)}`);
  }
  parts.push(counted(m.key, m.count, fmt.locale));
  return parts;
}

/** The metrics this version knows, in the server's order. */
export function knownMetrics(metrics: ComparedMetric[]): ComparedMetric[] {
  return metrics.filter((m) => knownKey(m.key)).sort((x, y) => ORDER.indexOf(x.key) - ORDER.indexOf(y.key));
}

/**
 * Compare devices in one line, for Body's row: how far apart the two read on heart rate at night
 * (or the first thing both measured): "The ring read 3 bpm higher than the strap", or "Only 1
 * night together so far" before there's [ENOUGH] to say. Null with nothing both measured.
 */
export function compareLine(c: Pick<DeviceComparison, "a" | "b" | "metrics">, fmt: CompareFormat): string | null {
  const metrics = knownMetrics(c.metrics);
  const m = metrics.find((x) => x.key === "NIGHT_HEART_RATE") ?? metrics[0];
  if (!m || !c.a || !c.b) return null;
  if (m.count < ENOUGH) return `Only ${counted(m.key, m.count, fmt.locale)} together so far`;
  return metricSentence(m, shortNames(c.a, c.b), fmt)[0];
}

// ---- Each night ----

/** The time both nights' charts share: from the earlier one's start to the later one's end. */
export function nightSpan(n: Pick<ComparedNight, "a" | "b">): { start: number; end: number } | null {
  const sessions = [n.a, n.b].filter((s): s is SleepSession => !!s && s.end > s.start);
  if (sessions.length === 0) return null;
  return { start: Math.min(...sessions.map((s) => s.start)), end: Math.max(...sessions.map((s) => s.end)) };
}

/**
 * Minutes asleep in a night, counted the way the server counts Time asleep (CompareService):
 * every stage but awake, or the whole night without stages. Unrounded, so it reads the same.
 */
export function asleepMinutes(s: SleepSession): number {
  const stages = s.stages ?? [];
  if (stages.length === 0) return (s.end - s.start) / 60_000;
  return stages.filter((x) => x.stage !== "AWAKE").reduce((sum, x) => sum + (x.end - x.start), 0) / 60_000;
}

/** One line of a night's table: the thing, each device's value, and how far apart. */
export interface NightRow {
  key: CompareKey;
  label: string;
  a: string;
  b: string;
  difference: string;
}

/**
 * A night's table: what both devices had, in the server's order. One device's own sleep without the
 * other's (a strap records none) is left out: its hypnogram already says it.
 */
export function nightRows(n: ComparedNight, fmt: CompareFormat): NightRow[] {
  return n.values
    .filter((v): v is typeof v & { a: number; b: number } => knownKey(v.key) && v.a != null && v.b != null)
    .sort((x, y) => ORDER.indexOf(x.key) - ORDER.indexOf(y.key))
    .map((v) => ({
      key: v.key,
      label: COMPARE_KEYS[v.key].label,
      a: valueText(v.key, v.a, fmt, n.date),
      b: valueText(v.key, v.b, fmt, n.date),
      difference: signedText(v.key, v.b - v.a, fmt),
    }));
}

// ---- Night by night: one thing on a chart ----

/** The things to follow night by night: those both had on two nights or more (not times), heart rate and HRV first. */
export function trendKeys(metrics: ComparedMetric[]): CompareKey[] {
  const first: CompareKey[] = ["NIGHT_HEART_RATE", "HRV", "ASLEEP"];
  const keys = knownMetrics(metrics).filter((m) => m.count > 1 && COMPARE_KEYS[m.key].unit !== "clock").map((m) => m.key);
  return [...first.filter((k) => keys.includes(k)), ...keys.filter((k) => !first.includes(k))];
}

/** A value as it's drawn: hours for a length of time, a temperature in the person's units, the rest as it is. */
export function drawnValue(key: CompareKey, v: number, fmt: CompareFormat): number {
  const unit = COMPARE_KEYS[key].unit;
  if (unit === "min") return v / 60;
  if (unit === "°C") return degrees(v, fmt, false);
  return v;
}

/** A drawn value for the tooltip: "7 h 12 min", "61 bpm", "34.1 °C". (Times aren't drawn.) */
export function drawnText(key: CompareKey, v: number, fmt: CompareFormat): string {
  const unit = COMPARE_KEYS[key].unit;
  if (unit === "min") return duration(v * 60);
  if (unit === "°C") return `${number(v, 1, fmt.locale)} ${degreeUnit(fmt)}`;
  return valueText(key, v, fmt, "");
}

/** A drawn value on the chart's axis, short enough to fit: "6.5 h", "58", "96.5", "12K". */
export function tickText(key: CompareKey, v: number, fmt: CompareFormat): string {
  const unit = COMPARE_KEYS[key].unit;
  const short = (n: number, most: number) => n.toLocaleString(fmt.locale, { maximumFractionDigits: most });
  if (unit === "min") return `${short(v, 1)} h`;
  if (unit === "steps") return Math.abs(v) >= 10_000 ? `${short(v / 1000, 0)}K` : short(v, 0);
  return short(v, decimals(unit));
}

/** One thing night by night, oldest first: each device's value as drawn (null where it had none). */
export function trendPoints(nights: ComparedNight[], key: CompareKey, fmt: CompareFormat): { date: string; a: number | null; b: number | null }[] {
  return [...nights]
    .sort((x, y) => x.date.localeCompare(y.date))
    .map((n) => {
      const v = n.values.find((x) => x.key === key);
      return { date: n.date, a: v?.a != null ? drawnValue(key, v.a, fmt) : null, b: v?.b != null ? drawnValue(key, v.b, fmt) : null };
    })
    .filter((p) => p.a != null || p.b != null);
}
