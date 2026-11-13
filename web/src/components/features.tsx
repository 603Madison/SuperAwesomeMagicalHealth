import type { LucideIcon } from "lucide-react";
import {
  Activity, Brain, CalendarClock, CalendarHeart, Camera, Droplet, HeartPulse, Infinity as InfinityIcon, ListChecks, Moon, Pill, Salad, Sparkles,
  Stethoscope, Syringe, Weight,
} from "lucide-react";
import type { Module, ReportSectionKey } from "../lib/types";

/** Every feature a person can turn on: its name, what it's for, where it lives and its icon. */
export interface Feature {
  id: Module;
  name: string;
  about: string;
  route?: string;
  icon: LucideIcon;
}

export const FEATURES: Feature[] = [
  { id: "ACTIVITY", name: "Activity", about: "Steps, calories, workouts, stress and the activity score.", route: "/activity", icon: Activity },
  { id: "SLEEP", name: "Sleep", about: "Nights, sleep stages and the sleep score.", route: "/sleep", icon: Moon },
  { id: "NUTRITION", name: "Food", about: "A food diary with calories, protein, carbs, fat, water, coffee and drinks.", route: "/food", icon: Salad },
  { id: "BODY", name: "Body", about: "Weight and body fat.", route: "/body", icon: Weight },
  { id: "VITALS", name: "Blood pressure & glucose", about: "Readings from a monitor or by hand, against your own targets.", route: "/vitals", icon: HeartPulse },
  { id: "HABITS", name: "Habits", about: "Small daily habits, with streaks that forgive a rest day.", route: "/habits", icon: ListChecks },
  { id: "MIND", name: "Mind", about: "Mood, a private journal, anxiety and depression check-ins, calming tools, and support when you need it.", route: "/mind", icon: Brain },
  { id: "CYCLE", name: "Cycle", about: "Periods, symptoms and predictions; pregnancy, after a birth, perimenopause and after.", route: "/cycle", icon: CalendarHeart },
  { id: "CONTINENCE", name: "Bladder & bowel", about: "A diary of toilet trips, leaks and bowel movements, and pelvic floor exercises.", route: "/bladder", icon: Droplet },
  { id: "MEDICATIONS", name: "Medications", about: "Medications and supplements, reminders, injection-site rotation and lab results.", route: "/meds", icon: Pill },
  { id: "SYMPTOMS", name: "Symptoms & pain", about: "Symptoms, pain and migraines, and what seems to go with them.", route: "/symptoms", icon: Stethoscope },
  { id: "HORMONES", name: "Hormone therapy", about: "Dose history, levels against your own targets, and when labs are due.", route: "/hormones", icon: Syringe },
  { id: "APPOINTMENTS", name: "Appointments", about: "Appointments with questions to ask, and notes afterwards.", route: "/appointments", icon: CalendarClock },
  { id: "NEURO", name: "Autism & ADHD", about: "Your sensory profile, overwhelm, energy, focus, routines, cards to show people and a calm mode.", route: "/neuro", icon: InfinityIcon },
  { id: "TRANSITION", name: "Transition", about: "Milestones, the changes you notice, voice practice, hair removal and the moments that feel right.", route: "/transition", icon: Sparkles },
  { id: "PHOTOS", name: "Progress photos", about: "Private, encrypted photos over time, to compare side by side.", route: "/photos", icon: Camera },
];

export const featureName = (id: Module) => FEATURES.find((f) => f.id === id)?.name ?? id;

/** The report's sections, in order, with the feature each needs (none: always offered). */
export const REPORT_SECTIONS: { id: ReportSectionKey; name: string; module?: Module }[] = [
  { id: "OVERVIEW", name: "Overview" },
  { id: "SLEEP", name: "Sleep", module: "SLEEP" },
  { id: "ACTIVITY", name: "Activity", module: "ACTIVITY" },
  { id: "STRESS", name: "Stress and resilience", module: "ACTIVITY" },
  { id: "BODY", name: "Weight and body", module: "BODY" },
  { id: "NUTRITION", name: "Food and drinks", module: "NUTRITION" },
  { id: "VITALS", name: "Blood pressure and glucose", module: "VITALS" },
  { id: "MEDICATIONS", name: "Medications", module: "MEDICATIONS" },
  { id: "HORMONES", name: "Hormone therapy", module: "HORMONES" },
  { id: "MIND", name: "Mind", module: "MIND" },
  { id: "CYCLE", name: "Cycle", module: "CYCLE" },
  { id: "CONTINENCE", name: "Bladder and bowel", module: "CONTINENCE" },
  { id: "SYMPTOMS", name: "Symptoms", module: "SYMPTOMS" },
  { id: "HABITS", name: "Habits", module: "HABITS" },
  { id: "APPOINTMENTS", name: "Appointments", module: "APPOINTMENTS" },
  { id: "NEURO", name: "Autism and ADHD", module: "NEURO" },
  { id: "TRANSITION", name: "Transition", module: "TRANSITION" },
];
