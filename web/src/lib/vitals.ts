import type { BpCategory, GlucoseContext, GlucoseUnit, VitalsTargets } from "./types";

export const MGDL_PER_MMOL = 18.0182;

export const DEFAULT_TARGETS: VitalsTargets = { systolicHigh: 130, diastolicHigh: 80, glucoseLow: 3.9, glucoseHigh: 10, glucoseUnit: "MMOL_L" };

/** A glucose value in the person's unit, as a number. */
export function glucoseValue(mmol: number, unit: GlucoseUnit): number {
  return unit === "MG_DL" ? Math.round(mmol * MGDL_PER_MMOL) : Math.round(mmol * 10) / 10;
}

export function glucoseText(mmol: number | undefined | null, unit: GlucoseUnit): string {
  if (mmol == null) return "–";
  return unit === "MG_DL" ? `${glucoseValue(mmol, unit)} mg/dL` : `${glucoseValue(mmol, unit).toFixed(1)} mmol/L`;
}

export const unitLabel = (unit: GlucoseUnit) => (unit === "MG_DL" ? "mg/dL" : "mmol/L");

/** A value typed in the person's unit, back to mmol/L. */
export function toMmol(value: number, unit: GlucoseUnit): number {
  return unit === "MG_DL" ? Math.round((value / MGDL_PER_MMOL) * 100) / 100 : value;
}

export const BP_TEXT: Record<BpCategory, string> = {
  LOW: "Low", NORMAL: "Normal", ELEVATED: "Elevated", STAGE_1: "High (stage 1)", STAGE_2: "High (stage 2)", CRISIS: "Very high",
};

/** The category a reading falls in (American Heart Association), as the server works it out. */
export function bpCategory(systolic: number, diastolic: number): BpCategory {
  if (systolic > 180 || diastolic > 120) return "CRISIS";
  if (systolic >= 140 || diastolic >= 90) return "STAGE_2";
  if (systolic >= 130 || diastolic >= 80) return "STAGE_1";
  if (systolic >= 120) return "ELEVATED";
  if (systolic < 90 || diastolic < 60) return "LOW";
  return "NORMAL";
}

/** A category's tone, for its chip. */
export function bpTone(c: BpCategory): "good" | "info" | "warn" {
  return c === "NORMAL" ? "good" : c === "ELEVATED" || c === "LOW" ? "info" : "warn";
}

export const GLUCOSE_CONTEXTS: { id: GlucoseContext; label: string }[] = [
  { id: "FASTING", label: "Fasting" }, { id: "BEFORE_MEAL", label: "Before a meal" }, { id: "AFTER_MEAL", label: "After a meal" },
  { id: "BEDTIME", label: "Bedtime" }, { id: "OTHER", label: "Other" }, { id: "CGM", label: "Sensor" },
];
