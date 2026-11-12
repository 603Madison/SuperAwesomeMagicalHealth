// The API's models: the web app's copies of shared/src/main/kotlin/com/health/api/*.kt.

export type Role = "ADMIN" | "USER";
export type SessionKind = "WEB" | "APP" | "UPLOAD" | "MCP" | "MCP_WRITE" | "MCP_ALL";

export interface User {
  id: string;
  username: string;
  displayName: string;
  role: Role;
  breakGlass: boolean;
  disabled: boolean;
  oidc: boolean;
  hasPassword: boolean;
  createdAt: number;
  lastLoginAt?: number;
}

export interface SessionInfo {
  id: string;
  kind: SessionKind;
  name: string;
  createdAt: number;
  lastSeenAt: number;
  expiresAt: number;
  current: boolean;
  scope?: Module[];
}

export interface AuthConfig {
  serverName: string;
  version: string;
  localLogin: boolean;
  oidc?: { label: string };
}

export type Metric =
  | "HEART_RATE" | "RESTING_HEART_RATE" | "HRV" | "SPO2" | "RESPIRATORY_RATE" | "SKIN_TEMPERATURE" | "TEMPERATURE_DEVIATION"
  | "STRESS" | "STEPS" | "DISTANCE" | "ACTIVE_CALORIES" | "FLOORS" | "WEIGHT" | "BODY_FAT"
  | "BLOOD_PRESSURE_SYSTOLIC" | "BLOOD_PRESSURE_DIASTOLIC" | "BLOOD_GLUCOSE";

export interface Sample {
  metric: Metric;
  start: number;
  end?: number;
  value: number;
  id?: number;
  source?: string;
}

export type SleepStage = "AWAKE" | "LIGHT" | "DEEP" | "REM" | "ASLEEP";

export interface StageSpan {
  stage: SleepStage;
  start: number;
  end: number;
}

export type WorkoutType =
  | "WALK" | "RUN" | "CYCLE" | "SWIM" | "STRENGTH" | "HIIT" | "YOGA" | "PILATES" | "HIKE" | "ROW" | "ELLIPTICAL"
  | "DANCE" | "SPORT" | "CLIMB" | "SKI" | "OTHER";

export interface Workout {
  type: WorkoutType;
  start: number;
  end: number;
  name?: string;
  distanceM?: number;
  activeCalories?: number;
  avgHeartRate?: number;
  maxHeartRate?: number;
  steps?: number;
  effort?: number;
  note?: string;
  id?: string;
  deviceId?: string;
  source?: string;
  manual?: boolean;
}

export type DeviceKind = "COLMI_RING" | "PEBBLE" | "GARMIN" | "HEALTH_CONNECT" | "GADGETBRIDGE" | "FIT_FILE" | "PHONE" | "MANUAL" | "OTHER";

export interface Device {
  id: string;
  kind: DeviceKind;
  identifier: string;
  name: string;
  model?: string;
  firmware?: string;
  battery?: number;
  lastSyncAt?: number;
  createdAt: number;
  samples: number;
}

export interface CheckIn {
  date: string;
  mood?: number;
  energy?: number;
  stress?: number;
  soreness?: number;
  tags: string[];
  note?: string;
}

export interface Point {
  t: number;
  v: number;
}

export type Meal = "BREAKFAST" | "LUNCH" | "DINNER" | "SNACK";

export interface Nutrients {
  calories: number;
  protein: number;
  carbs: number;
  fat: number;
  fiber?: number;
  sugar?: number;
  saturatedFat?: number;
  sodiumMg?: number;
}

export interface Food {
  name: string;
  nutrients: Nutrients;
  serving: string;
  servingGrams?: number;
  brand?: string;
  barcode?: string;
  source: "CUSTOM" | "OPEN_FOOD_FACTS";
  id?: string;
  uses: number;
}

export interface FoodEntry {
  date: string;
  meal: Meal;
  name: string;
  nutrients: Nutrients;
  servings: number;
  serving: string;
  brand?: string;
  foodId?: string;
  id?: string;
  loggedAt?: number;
}

export interface WaterEntry {
  date: string;
  ml: number;
  id?: string;
  at?: number;
}

export interface NutritionTarget {
  calories: number;
  protein: number;
  carbs: number;
  fat: number;
  waterMl: number;
  basis: string;
}

export interface FoodDay {
  date: string;
  entries: FoodEntry[];
  water: WaterEntry[];
  totals: Nutrients;
  waterMl: number;
  target: NutritionTarget;
  burned?: number;
}

export interface FoodSearch {
  mine: Food[];
  online: Food[];
  onlineError?: string;
}

export type EnergyProfile = "FEMALE" | "MALE" | "AVERAGE";
export type Units = "METRIC" | "IMPERIAL";

export interface Goals {
  steps: number;
  activeCalories: number;
  sleepMinutes: number;
  calories?: number | null;
  protein?: number | null;
  carbs?: number | null;
  fat?: number | null;
  waterMl: number;
  workoutsPerWeek: number;
  targetWeightKg?: number | null;
  weeklyChangeKg: number;
}

export interface Profile {
  birthYear?: number | null;
  heightCm?: number | null;
  energyProfile: EnergyProfile;
  units: Units;
  timezone: string;
  goals: Goals;
  modules: Module[];
}

export interface Contributor {
  key: string;
  label: string;
  score: number;
  value: string;
  detail?: string;
}

export interface Score {
  score: number;
  label: string;
  contributors: Contributor[];
}

export interface SleepSummary {
  start: number;
  end: number;
  asleepMinutes: number;
  inBedMinutes: number;
  efficiency: number;
  deepMinutes: number;
  remMinutes: number;
  lightMinutes: number;
  awakeMinutes: number;
  staged: boolean;
  latencyMinutes?: number;
  lowestHeartRate?: number;
  avgHeartRate?: number;
  avgHrv?: number;
  stages: StageSpan[];
  source?: string;
  sessionId?: string;
}

export interface Insight {
  tone: "good" | "info" | "warn";
  title: string;
  body: string;
  icon: string;
  module?: Module;
}

export interface DaySummary {
  date: string;
  readiness?: Score;
  sleep?: Score;
  activity?: Score;
  steps: number;
  distanceM: number;
  activeCalories: number;
  restingCalories?: number;
  floors?: number;
  restingHeartRate?: number;
  avgHeartRate?: number;
  minHeartRate?: number;
  maxHeartRate?: number;
  hrv?: number;
  spo2?: number;
  respiratoryRate?: number;
  temperatureDeviation?: number;
  stress?: number;
  sleepSummary?: SleepSummary;
  naps: SleepSummary[];
  workouts: Workout[];
  nutrition: Nutrients;
  nutritionTarget?: NutritionTarget;
  waterMl: number;
  weightKg?: number;
  bodyFat?: number;
  checkIn?: CheckIn;
  insights: Insight[];
  hourlySteps: number[];
  heartRate: Point[];
  goals: Goals;
  hasData: boolean;
  modules: ModulesToday;
}

export interface TrendDay {
  date: string;
  readiness?: number;
  sleepScore?: number;
  activityScore?: number;
  steps?: number;
  activeCalories?: number;
  restingHeartRate?: number;
  hrv?: number;
  sleepMinutes?: number;
  deepMinutes?: number;
  remMinutes?: number;
  spo2?: number;
  temperatureDeviation?: number;
  weightKg?: number;
  bodyFat?: number;
  calories?: number;
  protein?: number;
  carbs?: number;
  fat?: number;
  waterMl?: number;
  workouts: number;
  workoutMinutes: number;
  mood?: number;
  anxiety?: number;
  mindfulMinutes?: number;
  flow?: Flow;
  voids?: number;
  nightVoids?: number;
  leaks?: number;
  dosesTaken?: number;
  dosesDue?: number;
}

export interface Trends {
  from: string;
  to: string;
  days: TrendDay[];
}

export interface OidcSettings {
  enabled: boolean;
  issuer: string;
  clientId: string;
  scopes: string;
  buttonLabel: string;
  usernameClaim: string;
  groupsClaim: string;
  userGroup: string;
  adminGroup: string;
  autoCreateUsers: boolean;
}

export interface ServerSettings {
  serverName: string;
  publicUrl: string;
  localLogin: boolean;
  foodDatabase: boolean;
  crisisResources: CrisisResource[];
  oidc: OidcSettings;
}

export interface AdminSettings {
  settings: ServerSettings;
  oidcClientSecretSet: boolean;
  redirectUri: string;
  fromEnvironment: string[];
  version: string;
}

// ---- Health modules (shared/src/main/kotlin/com/health/api/Modules.kt) ----

export type Module = "ACTIVITY" | "SLEEP" | "NUTRITION" | "BODY" | "MIND" | "CYCLE" | "CONTINENCE" | "MEDICATIONS";

export const SENSITIVE: Module[] = ["MIND", "CYCLE", "CONTINENCE", "MEDICATIONS"];

export interface MoodEntry {
  at: number;
  mood: number;
  energy?: number;
  anxiety?: number;
  feelings: string[];
  context: string[];
  note?: string;
  id?: string;
  date?: string;
}

export type JournalKind = "FREE" | "GRATITUDE" | "THOUGHT_RECORD";

export interface ThoughtRecord {
  situation: string;
  thought: string;
  feelings?: string;
  beliefBefore?: number;
  evidenceFor?: string;
  evidenceAgainst?: string;
  balancedThought?: string;
  beliefAfter?: number;
}

export interface JournalEntry {
  at: number;
  kind: JournalKind;
  title?: string;
  text: string;
  prompt?: string;
  gratitude: string[];
  thought?: ThoughtRecord;
  id?: string;
  date?: string;
}

export type Screener = "PHQ9" | "GAD7";

export interface ScreenerDefinition {
  id: Screener;
  title: string;
  about: string;
  question: string;
  items: string[];
  options: string[];
  bands: { from: number; label: string }[];
  source: string;
}

export interface ScreenerResult {
  screener: Screener;
  at: number;
  answers: number[];
  score: number;
  severity: string;
  flagged: boolean;
  id?: string;
  date?: string;
}

export interface MindfulnessSession {
  at: number;
  minutes: number;
  kind: string;
  moodBefore?: number;
  moodAfter?: number;
  note?: string;
  id?: string;
  date?: string;
}

export interface CrisisResource {
  name: string;
  contact: string;
  region?: string;
  url?: string;
  note?: string;
}

export interface MindDay {
  date: string;
  mood?: number;
  energy?: number;
  anxiety?: number;
  entries: number;
  mindfulMinutes: number;
  journal: number;
}

export interface Count {
  name: string;
  count: number;
}

export interface MindSummary {
  days: MindDay[];
  averageMood?: number;
  feelings: Count[];
  context: Count[];
  patterns: Insight[];
  latest: ScreenerResult[];
  mindfulMinutes: number;
}

export type Flow = "SPOTTING" | "LIGHT" | "MEDIUM" | "HEAVY";
export type OvulationTest = "NEGATIVE" | "POSITIVE" | "PEAK";
export type Mucus = "DRY" | "STICKY" | "CREAMY" | "WATERY" | "EGG_WHITE";
export type CyclePhase = "PERIOD" | "FOLLICULAR" | "FERTILE" | "LUTEAL" | "UNKNOWN";

export interface CycleDay {
  date: string;
  flow?: Flow;
  symptoms: string[];
  pain?: number;
  mucus?: Mucus;
  ovulationTest?: OvulationTest;
  temperature?: number;
  note?: string;
}

export interface Cycle {
  start: string;
  length?: number;
  periodLength: number;
  ovulation?: string;
}

export interface CyclePrediction {
  nextPeriod: string;
  earliest: string;
  latest: string;
  fertileStart?: string;
  fertileEnd?: string;
  ovulation?: string;
  confidence: "low" | "medium" | "high";
}

export interface CycleOverview {
  days: CycleDay[];
  cycles: Cycle[];
  averageCycle?: number;
  averagePeriod?: number;
  variation?: number;
  prediction?: CyclePrediction;
  cycleDay?: number;
  phase: CyclePhase;
  daysUntilPeriod?: number;
  temperatures: { date: string; value: number }[];
  caveat: string;
}

export type ContinenceKind = "URINATION" | "LEAK" | "BOWEL" | "PAD_CHANGE" | "PELVIC_FLOOR";
export type LeakSize = "DROPS" | "SMALL" | "MEDIUM" | "LARGE";

export interface ContinenceEntry {
  at: number;
  kind: ContinenceKind;
  volumeMl?: number;
  urgency?: number;
  leak?: LeakSize;
  triggers: string[];
  bristol?: number;
  pain: boolean;
  blood: boolean;
  reps?: number;
  holdSeconds?: number;
  note?: string;
  id?: string;
  date?: string;
  night: boolean;
}

export interface ContinenceDay {
  date: string;
  voids: number;
  nightVoids: number;
  leaks: number;
  bowel: number;
  pads: number;
  pelvicFloor: number;
  voidedMl?: number;
  fluidMl: number;
  urgentTrips: number;
}

export interface ContinenceSummary {
  days: ContinenceDay[];
  averageVoids?: number;
  averageNightVoids?: number;
  averageLeaks?: number;
  triggers: Count[];
  averageInterval?: number;
  patterns: Insight[];
}

export type MedForm = "PILL" | "CAPSULE" | "LIQUID" | "INJECTION" | "PATCH" | "GEL" | "SPRAY" | "INHALER" | "CREAM" | "DROPS" | "IMPLANT" | "SUPPLEMENT" | "OTHER";
export type ScheduleKind = "DAILY" | "WEEKLY" | "EVERY_N_DAYS" | "AS_NEEDED";

export interface MedSchedule {
  kind: ScheduleKind;
  times: string[];
  everyDays: number;
  weekdays: number[];
  startDate?: string;
}

export interface Medication {
  name: string;
  dose?: string;
  form: MedForm;
  schedule: MedSchedule;
  purpose?: string;
  notes?: string;
  sites: string[];
  reminders: boolean;
  active: boolean;
  stock?: number;
  id?: string;
}

export interface DoseLog {
  medicationId: string;
  at: number;
  status: "TAKEN" | "SKIPPED";
  scheduledFor?: number;
  site?: string;
  note?: string;
  id?: string;
  date?: string;
}

export interface DueDose {
  medicationId: string;
  name: string;
  dose?: string;
  form: MedForm;
  scheduledFor: number;
  log?: DoseLog;
  nextSite?: string;
}

export interface MedsDay {
  date: string;
  due: DueDose[];
  asNeeded: Medication[];
  extra: DoseLog[];
  adherence7d?: number;
  lowStock: Medication[];
}

export interface LabResult {
  name: string;
  value: number;
  unit: string;
  at: number;
  low?: number;
  high?: number;
  note?: string;
  id?: string;
  date?: string;
}

export interface CycleToday {
  cycleDay?: number;
  phase: CyclePhase;
  daysUntilPeriod?: number;
  logged?: CycleDay;
}

export interface ModulesToday {
  mind?: MindDay;
  cycle?: CycleToday;
  continence?: ContinenceDay;
  meds?: MedsDay;
}
