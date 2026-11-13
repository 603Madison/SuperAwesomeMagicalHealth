// The API's models: the web app's copies of shared/src/main/kotlin/com/health/api/*.kt.

export type Role = "ADMIN" | "USER";
export type SessionKind = "WEB" | "APP" | "UPLOAD" | "MCP" | "MCP_WRITE" | "MCP_ALL" | "INTEGRATION" | "WATCH";

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
  | "BLOOD_PRESSURE_SYSTOLIC" | "BLOOD_PRESSURE_DIASTOLIC" | "BLOOD_GLUCOSE"
  | "WAIST" | "HIPS" | "CHEST" | "BUST" | "UNDERBUST" | "SHOULDERS" | "NECK" | "UPPER_ARM" | "THIGH" | "CALF";

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

export type DeviceKind = "COLMI_RING" | "PEBBLE" | "GARMIN" | "HEALTH_CONNECT" | "GADGETBRIDGE" | "FIT_FILE" | "PHONE" | "BP_MONITOR" | "GLUCOSE_METER" | "MANUAL" | "OTHER";

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
  reminders: ReminderPrefs;
  digest: DigestPrefs;
  place?: Place | null;
  vitalsTargets: VitalsTargets;
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
  stressDay?: StressDay;
  resilience?: Resilience;
  sleepSummary?: SleepSummary;
  naps: SleepSummary[];
  workouts: Workout[];
  nutrition: Nutrients;
  nutritionTarget?: NutritionTarget;
  waterMl: number;
  drinks?: DrinksDay;
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
  stressedMinutes?: number;
  restoredMinutes?: number;
  caffeineMg?: number;
  standardDrinks?: number;
  systolic?: number;
  diastolic?: number;
  glucose?: number;
  symptomWorst?: number;
  migraine?: boolean;
  habitsDone?: number;
  habitsDue?: number;
  hotFlashes?: number;
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
  /** Air pressure for symptom patterns, and place search (a rough place goes to open-meteo.com). */
  weather?: boolean;
  smtp?: SmtpSettings;
  /** Let webhooks call addresses on private networks. */
  webhooksPrivateNetworks?: boolean;
}

/** Mail for weekly digests; the password is set apart, at /api/admin/smtp. */
export interface SmtpSettings {
  enabled: boolean;
  host: string;
  port: number;
  security: "NONE" | "STARTTLS" | "TLS";
  username: string;
  from: string;
}

export interface AdminSettings {
  settings: ServerSettings;
  oidcClientSecretSet: boolean;
  redirectUri: string;
  fromEnvironment: string[];
  version: string;
}

// ---- Health modules (shared/src/main/kotlin/com/health/api/Modules.kt) ----

export type Module =
  | "ACTIVITY" | "SLEEP" | "NUTRITION" | "BODY" | "MIND" | "CYCLE" | "CONTINENCE" | "MEDICATIONS"
  | "VITALS" | "HABITS" | "SYMPTOMS" | "HORMONES" | "APPOINTMENTS" | "NEURO" | "TRANSITION" | "PHOTOS";

export const SENSITIVE: Module[] = [
  "MIND", "CYCLE", "CONTINENCE", "MEDICATIONS", "SYMPTOMS", "HORMONES", "APPOINTMENTS", "NEURO", "TRANSITION", "PHOTOS",
];

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

export type Screener = "PHQ9" | "GAD7" | "EPDS";

export interface ScreenerDefinition {
  id: Screener;
  title: string;
  about: string;
  question: string;
  items: string[];
  options: string[];
  bands: { from: number; label: string }[];
  source: string;
  /** Where each question has its own answers (the EPDS). */
  itemOptions?: string[][];
  itemScores?: number[][];
  /** The question about self-harm. */
  flagItem?: number;
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
  products?: ProductUse[];
  clots?: Clots;
  color?: string;
  painAreas?: string[];
  symptomSeverity?: Record<string, number>;
  mood?: string[];
  energy?: number;
  libido?: number;
  sex?: SexEntry;
  cervix?: Cervix;
  lh?: number;
  pregnancyTest?: PregnancyTest;
  discharge?: string;
  tags?: string[];
  details?: Record<string, string>;
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

export type ContinenceKind = "URINATION" | "LEAK" | "BOWEL" | "PAD_CHANGE" | "PELVIC_FLOOR" | "BOWEL_LEAK" | "CATHETER" | "BEDWETTING";
export type LeakSize = "DROPS" | "SMALL" | "MEDIUM" | "LARGE" | "FULL";

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
  contents?: LeakContents;
  activity?: string[];
  awareness?: Awareness;
  reachedToilet?: ReachedToilet;
  warningSeconds?: number;
  location?: string;
  supplyId?: string;
  fullness?: Fullness;
  padWeightG?: number;
  lossG?: number;
  changedClothes?: boolean;
  changedBedding?: boolean;
  leakedThrough?: boolean;
  skin?: number;
  utiSigns?: string[];
  count?: number;
  tags?: string[];
  details?: Record<string, string>;
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
  bowelLeaks: number;
  wetNights: number;
  /** Products changed (each change's count). */
  products: number;
  leakedThrough: number;
  catheters: number;
  catheterMl?: number;
  /** Grams lost, from weighed pads. */
  lossG?: number;
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
  hormone?: boolean;
  startedOn?: string;
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
  vitals?: VitalsToday;
  habits?: HabitStatus[];
  symptoms?: SymptomsDay;
  hormones?: HormonesToday;
  lifeStage?: LifeStageToday;
  appointments?: Appointment[];
  lowSupplies?: SupplyStatus[];
  neuro?: NeuroToday;
  transition?: TransitionToday;
}

// ---- Blood pressure and glucose, drinks, symptoms, stress, habits, hormones, life stages, appointments
//      (shared/src/main/kotlin/com/health/api/Tracking.kt) ----

export interface BloodPressure {
  at: number;
  systolic: number;
  diastolic: number;
  pulse?: number;
  arm?: string;
  note?: string;
  id?: string;
  date?: string;
  source?: string;
}

export type BpCategory = "LOW" | "NORMAL" | "ELEVATED" | "STAGE_1" | "STAGE_2" | "CRISIS";
export type GlucoseContext = "FASTING" | "BEFORE_MEAL" | "AFTER_MEAL" | "BEDTIME" | "OTHER" | "CGM";
export type GlucoseUnit = "MMOL_L" | "MG_DL";

export interface GlucoseReading {
  at: number;
  mmolL: number;
  context: GlucoseContext;
  note?: string;
  id?: string;
  date?: string;
  source?: string;
}

export interface VitalsTargets {
  systolicHigh: number;
  diastolicHigh: number;
  glucoseLow: number;
  glucoseHigh: number;
  glucoseUnit: GlucoseUnit;
}

export interface VitalsSummary {
  days: number;
  bloodPressure: BloodPressure[];
  glucose: GlucoseReading[];
  systolicAverage?: number;
  diastolicAverage?: number;
  category?: BpCategory;
  glucoseAverage?: number;
  fastingAverage?: number;
  timeBelow?: number;
  timeInRange?: number;
  timeAbove?: number;
  gmi?: number;
  targets: VitalsTargets;
  patterns: Insight[];
}

export interface VitalsToday {
  bloodPressure?: BloodPressure;
  category?: BpCategory;
  glucose?: GlucoseReading;
  timeInRange?: number;
  targets: VitalsTargets;
}

export type DrinkKind =
  | "WATER" | "COFFEE" | "ESPRESSO" | "TEA" | "GREEN_TEA" | "ENERGY_DRINK" | "SODA" | "DECAF"
  | "BEER" | "WINE" | "SPIRITS" | "COCKTAIL" | "OTHER";

export interface Drink {
  at: number;
  kind: DrinkKind;
  volumeMl: number;
  caffeineMg: number;
  alcoholG: number;
  name?: string;
  id?: string;
  date?: string;
}

export interface DrinksDay {
  date: string;
  drinks: Drink[];
  caffeineMg: number;
  alcoholG: number;
  standardDrinks: number;
  lastCaffeineAt?: number;
  caffeineAtBedtimeMg?: number;
  fluidMl: number;
}

export interface DrinksSummary {
  days: DrinksDay[];
  averageCaffeineMg?: number;
  averageStandardDrinks?: number;
  patterns: Insight[];
}

export interface SymptomEntry {
  at: number;
  symptom: string;
  severity: number;
  bodyAreas: string[];
  durationMinutes?: number;
  triggers: string[];
  relief: string[];
  reliefHelped?: number;
  note?: string;
  id?: string;
  date?: string;
}

export interface Migraine {
  start: number;
  end?: number;
  severity: number;
  aura: boolean;
  symptoms: string[];
  triggers: string[];
  medications: string[];
  reliefHelped?: number;
  missedPlans: boolean;
  note?: string;
  id?: string;
  date?: string;
}

export interface Correlation {
  factor: string;
  text: string;
  withRate: number;
  withoutRate: number;
  days: number;
}

export interface SymptomsDay {
  date: string;
  entries: SymptomEntry[];
  migraines: Migraine[];
  worst?: number;
}

export interface SymptomsSummary {
  days: SymptomsDay[];
  counts: Count[];
  migraineDays: number;
  triggers: Count[];
  correlations: Correlation[];
  weather: boolean;
}

export interface Place {
  name: string;
  latitude: number;
  longitude: number;
}

export type StressState = "STRESSED" | "ENGAGED" | "RELAXED" | "RESTORED";

export interface StressSpan {
  start: number;
  end: number;
  state: StressState;
}

export interface StressDay {
  date: string;
  stressedMinutes: number;
  engagedMinutes: number;
  relaxedMinutes: number;
  restoredMinutes: number;
  summary: string;
  spans: StressSpan[];
}

export type ResilienceLevel = "LIMITED" | "ADEQUATE" | "SOLID" | "STRONG" | "EXCEPTIONAL";

export interface Resilience {
  level: ResilienceLevel;
  contributors: Contributor[];
  trend: "up" | "down" | "steady";
}

export type HabitSource =
  | "MANUAL" | "STEPS_GOAL" | "SLEEP_GOAL" | "WATER_GOAL" | "WORKOUT" | "MOOD_LOGGED" | "JOURNAL" | "MINDFUL"
  | "MEDS_TAKEN" | "PELVIC_FLOOR" | "NO_ALCOHOL" | "CAFFEINE_CUTOFF";

export interface Habit {
  name: string;
  icon: string;
  source: HabitSource;
  timesPerWeek: number;
  perDay: number;
  reminderTime?: string;
  active: boolean;
  id?: string;
}

export interface HabitStatus {
  habit: Habit;
  date: string;
  count: number;
  done: boolean;
  weekDone: number;
  streakWeeks: number;
  streakDays: number;
  /** The last 14 days, oldest first: true done, false missed, null before it began. */
  recent: (boolean | null)[];
}

export interface HabitTick {
  date: string;
  count: number;
}

export interface DoseChange {
  medicationId: string;
  at: number;
  from?: string;
  to: string;
  note?: string;
  id?: string;
}

export interface LabTarget {
  name: string;
  unit: string;
  low?: number;
  high?: number;
  note?: string;
}

export interface HormoneSettings {
  labsEveryWeeks?: number | null;
  targets: LabTarget[];
}

export interface LabSeries {
  name: string;
  unit: string;
  target?: LabTarget;
  results: LabResult[];
}

export interface HormoneMed {
  medication: Medication;
  since?: string;
  changes: DoseChange[];
}

export interface HormoneOverview {
  medications: HormoneMed[];
  labs: LabSeries[];
  settings: HormoneSettings;
  lastLabs?: string;
  nextLabsDue?: string;
}

export interface HormonesToday {
  medications: number;
  nextLabsDue?: string;
  outOfRange: string[];
}

export type LifeStage = "CYCLING" | "TRYING" | "PREGNANT" | "POSTPARTUM" | "PERIMENOPAUSE" | "MENOPAUSE";

export interface LifeStageInfo {
  stage: LifeStage;
  dueDate?: string;
  lastPeriod?: string;
  birthDate?: string;
  since?: string;
}

export interface KickSession {
  start: number;
  end?: number;
  kicks: number;
  id?: string;
}

export interface Contraction {
  start: number;
  end?: number;
  id?: string;
}

export type FeedKind = "LEFT" | "RIGHT" | "BOTH" | "BOTTLE" | "PUMP";

export interface Feed {
  start: number;
  end?: number;
  kind: FeedKind;
  amountMl?: number;
  note?: string;
  id?: string;
}

export interface HotFlash {
  at: number;
  intensity: number;
  night: boolean;
  id?: string;
}

export interface PregnancyStatus {
  week: number;
  day: number;
  trimester: number;
  dueDate: string;
  daysToGo: number;
}

export interface LifeStageOverview {
  info: LifeStageInfo;
  pregnancy?: PregnancyStatus;
  weeksSinceBirth?: number;
  monthsSinceLastPeriod?: number;
  kicks: KickSession[];
  contractions: Contraction[];
  contractionsPattern?: string;
  feeds: Feed[];
  hotFlashes: HotFlash[];
  hotFlashesPerDay?: number;
  tips: Insight[];
}

export interface LifeStageToday {
  stage: LifeStage;
  headline: string;
  pregnancy?: PregnancyStatus;
  feedsToday: number;
  lastFeed?: Feed;
  hotFlashesToday: number;
}

export interface AppointmentQuestion {
  text: string;
  done: boolean;
  suggested: boolean;
}

export interface Appointment {
  at: number;
  title: string;
  durationMinutes?: number;
  with?: string;
  place?: string;
  questions: AppointmentQuestion[];
  notes?: string;
  remindBefore: number[];
  id?: string;
  date?: string;
}

export interface SuggestedQuestion {
  text: string;
  why: string;
  module?: Module;
}

// ---- Sharing, reminders, digest, webhooks, push, reports, watch (shared/…/Platform.kt) ----

export interface Share {
  id: string;
  ownerId: string;
  ownerName: string;
  viewerId: string;
  viewerName: string;
  modules: Module[];
  createdAt: number;
  lastViewedAt?: number;
}

export interface Shares {
  given: Share[];
  received: Share[];
}

export interface ReminderPrefs {
  remindAgainMinutes: number;
  remindAgainTimes: number;
  missedAfterMinutes: number;
  refillDays: number;
  refillTime: string;
  showNames: boolean;
  quietFrom?: string | null;
  quietTo?: string | null;
  habits: boolean;
  appointments: boolean;
}

export interface DigestPrefs {
  enabled: boolean;
  day: number;
  hour: number;
  email?: string | null;
  emailEnabled: boolean;
  emailModules: Module[];
}

export interface DigestSection {
  title: string;
  module?: Module;
  tone: "good" | "info" | "warn";
  lines: string[];
}

export interface Digest {
  weekStart: string;
  weekEnd: string;
  generatedAt: number;
  headline: string;
  sections: DigestSection[];
}

export type WebhookEvent =
  | "MORNING" | "READINESS_LOW" | "DOSE_DUE" | "DOSE_MISSED" | "DOSE_TAKEN" | "REFILL" | "BP_HIGH" | "GLUCOSE_LOW"
  | "GLUCOSE_HIGH" | "MIGRAINE" | "HABIT_DUE" | "APPOINTMENT_SOON" | "DEVICE_BATTERY_LOW" | "DEVICE_QUIET" | "DIGEST" | "TEST"
  | "BLADDER_TRAINING" | "SUPPLIES_LOW" | "CYCLE_REMINDER";

export interface Webhook {
  name: string;
  url: string;
  events: WebhookEvent[];
  enabled: boolean;
  includeDetails: boolean;
  id?: string;
  createdAt?: number;
  lastDelivery?: WebhookDelivery;
  secret?: string;
}

export interface WebhookDelivery {
  at: number;
  event: WebhookEvent;
  ok: boolean;
  status?: number;
  error?: string;
  webhookId?: string;
}

export interface IntegrationKey {
  id: string;
  name: string;
  token: string;
  url: string;
}

export interface PushKey {
  publicKey: string;
}

export interface PushSubscriptionInfo {
  id: string;
  name: string;
  createdAt: number;
  lastOkAt?: number;
}

export interface ReminderAction {
  action: "taken" | "skip" | "snooze" | "open";
  title: string;
}

export interface PushMessage {
  title: string;
  body: string;
  tag: string;
  url: string;
  actions: ReminderAction[];
  medicationId?: string;
  scheduledFor?: number;
  /** When it's for (in /api/reminders/upcoming). */
  at?: number;
}

export type ReportSectionKey =
  | "OVERVIEW" | "SLEEP" | "ACTIVITY" | "STRESS" | "BODY" | "NUTRITION" | "VITALS" | "MEDICATIONS" | "HORMONES"
  | "MIND" | "CYCLE" | "CONTINENCE" | "SYMPTOMS" | "HABITS" | "APPOINTMENTS" | "NEURO" | "TRANSITION";

export interface WatchToday {
  date: string;
  readiness?: number;
  sleep?: number;
  activity?: number;
  steps: number;
  stepGoal: number;
  waterMl: number;
  waterGoal: number;
  modules: Module[];
  doses: { medicationId: string; scheduledFor: number; name: string; time: string; taken: boolean }[];
  habits: { id: string; name: string; done: boolean }[];
  stress?: StressState;
}

// ---------------------------------------------------------------- Conditions and goals (Conditions.kt)

export type ConditionKind = "CONDITION" | "GOAL" | "LIFE";
export const CONDITION_KINDS: { id: ConditionKind; label: string }[] = [
  { id: "CONDITION", label: "Conditions" }, { id: "GOAL", label: "Goals" }, { id: "LIFE", label: "Life" },
];

export interface ConditionDefinition {
  id: string;
  name: string;
  kind: ConditionKind;
  group: string;
  about: string;
  modules: Module[];
  fields: string[];
  symptoms: string[];
  triggers: string[];
  aliases: string[];
}

export type ConditionStatus = "DIAGNOSED" | "SELF_IDENTIFIED" | "SUSPECTED" | "MANAGING" | "PAST";
export const CONDITION_STATUSES: { id: ConditionStatus; label: string }[] = [
  { id: "DIAGNOSED", label: "Diagnosed" }, { id: "SELF_IDENTIFIED", label: "Self-identified" }, { id: "SUSPECTED", label: "Wondering" },
  { id: "MANAGING", label: "Managing" }, { id: "PAST", label: "In the past" },
];

export interface MyCondition {
  id: string;
  name: string;
  kind: ConditionKind;
  status?: ConditionStatus;
  since?: string;
  notes?: string;
  modules: Module[];
  fields: string[];
}

export interface MyConditions {
  conditions: MyCondition[];
}

export interface FieldLayout {
  effective: string[];
  show: string[];
  hide: string[];
  because: Record<string, string>;
}

export interface FieldChoices {
  show: string[];
  hide: string[];
}

export type CustomFieldType = "TEXT" | "NUMBER" | "SCALE" | "CHOICE" | "MULTI" | "YES_NO";

export interface CustomField {
  key: string;
  label: string;
  type: CustomFieldType;
  options: string[];
  min: number;
  max: number;
  unit?: string;
}

// ---------------------------------------------------------------- Bladder and bowel, in detail

export type Fullness = "DRY" | "DAMP" | "WET" | "FULL" | "SOAKED" | "LEAKED";
export const FULLNESS: { id: Fullness; label: string; level: number }[] = [
  { id: "DRY", label: "Dry", level: 0 }, { id: "DAMP", label: "Damp", level: 1 }, { id: "WET", label: "Wet", level: 2 },
  { id: "FULL", label: "Full", level: 3 }, { id: "SOAKED", label: "Soaked", level: 4 }, { id: "LEAKED", label: "Leaked through", level: 5 },
];
export type Awareness = "FELT_IT" | "FELT_DURING" | "NOTICED_AFTER" | "DIDNT_NOTICE";
export const AWARENESS: { id: Awareness; label: string }[] = [
  { id: "FELT_IT", label: "Felt it coming" }, { id: "FELT_DURING", label: "Felt it happening" },
  { id: "NOTICED_AFTER", label: "Noticed afterwards" }, { id: "DIDNT_NOTICE", label: "Didn't notice" },
];
export type ReachedToilet = "YES" | "PARTLY" | "NO" | "NOT_TRYING";
export const REACHED_TOILET: { id: ReachedToilet; label: string }[] = [
  { id: "YES", label: "Made it" }, { id: "PARTLY", label: "Partly" }, { id: "NO", label: "Didn't make it" }, { id: "NOT_TRYING", label: "Wasn't trying" },
];
export type LeakContents = "URINE" | "STOOL" | "WIND" | "BOTH";
export const LEAK_CONTENTS: { id: LeakContents; label: string }[] = [
  { id: "URINE", label: "Urine" }, { id: "STOOL", label: "Stool" }, { id: "WIND", label: "Wind" }, { id: "BOTH", label: "Urine and stool" },
];

export interface BladderTraining {
  enabled: boolean;
  intervalMinutes: number;
  from: string;
  to: string;
  stepMinutes: number;
  stepEveryDays: number;
  goalMinutes: number;
  startedOn?: string;
}

export interface ContinenceSettings {
  quickButtons: ContinenceKind[];
  triggers: string[];
  activities: string[];
  locations: string[];
  customFields: CustomField[];
  defaultSupplyId?: string;
  nightFrom?: string;
  nightTo?: string;
  bladderTraining: BladderTraining;
}

export interface ContinenceCheckIn {
  date: string;
  bother: number;
  compared?: number;
  impact: string[];
  note?: string;
  id?: string;
}

export type Verdict = "IMPROVING" | "STEADY" | "WORSENING" | "NOT_ENOUGH";
export const VERDICT_LABEL: Record<Verdict, string> = {
  IMPROVING: "Getting better", STEADY: "About the same", WORSENING: "Getting worse", NOT_ENOUGH: "Not enough to tell yet",
};

export interface ProgressMetric {
  key: string;
  label: string;
  before?: number;
  after?: number;
  unit: string;
  lowerIsBetter: boolean;
  verdict: Verdict;
}

export interface ProgressWeek {
  weekStart: string;
  days: number;
  voidsPerDay?: number;
  nightVoidsPerDay?: number;
  leaksPerDay?: number;
  bowelLeaksPerDay?: number;
  urgency?: number;
  productsPerDay?: number;
  fullness?: number;
  lossGPerDay?: number;
  bother?: number;
  pelvicFloorSets: number;
}

export interface ContinenceProgress {
  verdict: Verdict;
  headline: string;
  recentWeeks: number;
  metrics: ProgressMetric[];
  weeks: ProgressWeek[];
  notes: string[];
}

// ---------------------------------------------------------------- Supplies

export type SupplyCategory = "CONTINENCE" | "PERIOD" | "OTHER";
export type SupplyKind =
  | "LINER" | "PAD" | "BOOSTER" | "PULL_UP" | "BRIEF" | "BED_PAD" | "REUSABLE_UNDERWEAR" | "CATHETER" | "WIPES" | "BARRIER_CREAM"
  | "PERIOD_PAD" | "TAMPON" | "CUP" | "DISC" | "PERIOD_UNDERWEAR" | "OTHER";
export const SUPPLY_KINDS: { id: SupplyKind; label: string; category: SupplyCategory }[] = [
  { id: "LINER", label: "Liner", category: "CONTINENCE" },
  { id: "PAD", label: "Pad", category: "CONTINENCE" },
  { id: "BOOSTER", label: "Booster pad", category: "CONTINENCE" },
  { id: "PULL_UP", label: "Pull-up / protective underwear", category: "CONTINENCE" },
  { id: "BRIEF", label: "Brief / diaper (with tabs)", category: "CONTINENCE" },
  { id: "BED_PAD", label: "Bed pad", category: "CONTINENCE" },
  { id: "REUSABLE_UNDERWEAR", label: "Reusable underwear", category: "CONTINENCE" },
  { id: "CATHETER", label: "Catheter", category: "CONTINENCE" },
  { id: "WIPES", label: "Wipes", category: "OTHER" },
  { id: "BARRIER_CREAM", label: "Barrier cream", category: "OTHER" },
  { id: "PERIOD_PAD", label: "Period pad", category: "PERIOD" },
  { id: "TAMPON", label: "Tampon", category: "PERIOD" },
  { id: "CUP", label: "Cup", category: "PERIOD" },
  { id: "DISC", label: "Disc", category: "PERIOD" },
  { id: "PERIOD_UNDERWEAR", label: "Period underwear", category: "PERIOD" },
  { id: "OTHER", label: "Other", category: "OTHER" },
];
export const supplyKindLabel = (k: SupplyKind) => SUPPLY_KINDS.find((x) => x.id === k)?.label ?? "Other";

export interface Supply {
  name: string;
  kind: SupplyKind;
  brand?: string;
  size?: string;
  absorbency?: string;
  dryWeightG?: number;
  onHand: number;
  perPack?: number;
  lowAt?: number;
  reusable: boolean;
  notes?: string;
  active: boolean;
  id?: string;
}

export type SupplyReason = "USED" | "RESTOCKED" | "COUNTED" | "GAVE_AWAY";

export interface SupplyAdjustment {
  at: number;
  delta: number;
  reason: SupplyReason;
  note?: string;
  id?: string;
  supplyId?: string;
}

export interface SupplyStatus {
  supply: Supply;
  perDay?: number;
  daysLeft?: number;
  low: boolean;
  lastRestocked?: string;
}

// ---------------------------------------------------------------- Cycle, in detail

export interface ProductUse {
  kind: SupplyKind;
  count: number;
  fullness?: Fullness;
  supplyId?: string;
}
export type Clots = "NONE" | "SMALL" | "LARGE";
export const CLOTS: { id: Clots; label: string }[] = [{ id: "NONE", label: "None" }, { id: "SMALL", label: "Small" }, { id: "LARGE", label: "Larger than a coin" }];
export interface SexEntry {
  had: boolean;
  protected?: boolean;
  note?: string;
}
export interface Cervix {
  position?: string;
  firmness?: string;
  opening?: string;
}
export type PregnancyTest = "NEGATIVE" | "FAINT" | "POSITIVE";
export const PREGNANCY_TESTS: { id: PregnancyTest; label: string }[] = [
  { id: "NEGATIVE", label: "Negative" }, { id: "FAINT", label: "Faint line" }, { id: "POSITIVE", label: "Positive" },
];

export interface CycleSettings {
  symptoms: string[];
  hiddenSymptoms: string[];
  moods: string[];
  painAreas: string[];
  customFields: CustomField[];
  cycleLength?: number;
  periodLength?: number;
  lutealLength?: number;
  remindBeforePeriod?: number;
  remindLateAfter?: number;
  dailyReminder?: string;
  neutralWording: boolean;
}

// ---------------------------------------------------------------- Autism and ADHD (Neuro.kt)

export type Wording = "IDENTITY_FIRST" | "PERSON_FIRST";
export type Sense = "SOUND" | "LIGHT" | "TOUCH" | "SMELL" | "TASTE" | "TEMPERATURE" | "MOVEMENT" | "BODY_SIGNALS" | "CROWDS";
export const SENSES: { id: Sense; label: string }[] = [
  { id: "SOUND", label: "Sound" }, { id: "LIGHT", label: "Light" }, { id: "TOUCH", label: "Touch and textures" }, { id: "SMELL", label: "Smell" },
  { id: "TASTE", label: "Taste" }, { id: "TEMPERATURE", label: "Temperature" }, { id: "MOVEMENT", label: "Movement and balance" },
  { id: "BODY_SIGNALS", label: "Body signals (hunger, thirst, needing the toilet)" }, { id: "CROWDS", label: "Crowds and busy places" },
];
export const senseLabel = (s: Sense) => SENSES.find((x) => x.id === s)?.label ?? s;

export interface SensePreference {
  sense: Sense;
  response: number;
  notes?: string;
  helps: string[];
}

export interface NeuroProfile {
  identities: string[];
  wording: Wording;
  senses: SensePreference[];
  communication: string[];
  whatHelps: string[];
  accommodations: string[];
  warningSigns: string[];
  interests: string[];
  stims: string[];
  aboutMe?: string;
}

export type OverloadKind = "OVERWHELM" | "SENSORY_OVERLOAD" | "MELTDOWN" | "SHUTDOWN" | "PANIC";
export const OVERLOAD_KINDS: { id: OverloadKind; label: string }[] = [
  { id: "OVERWHELM", label: "Overwhelm" }, { id: "SENSORY_OVERLOAD", label: "Sensory overload" }, { id: "MELTDOWN", label: "Meltdown" },
  { id: "SHUTDOWN", label: "Shutdown" }, { id: "PANIC", label: "Panic" },
];

export interface OverloadEvent {
  start: number;
  end?: number;
  kind: OverloadKind;
  intensity: number;
  senses: Sense[];
  triggers: string[];
  warningSigns: string[];
  whereAt?: string;
  whatHelped: string[];
  recoveryMinutes?: number;
  note?: string;
  id?: string;
  date?: string;
}

export interface EnergySpend {
  what: string;
  amount: number;
  at?: number;
}

export interface EnergyDay {
  date: string;
  startWith?: number;
  spends: EnergySpend[];
  socialBattery?: number;
  note?: string;
}

export interface NeuroCheckIn {
  at: number;
  masking?: number;
  socialLoad?: number;
  sensoryLoad?: number;
  starting?: number;
  focus?: number;
  organising?: number;
  remembering?: number;
  switching?: number;
  restlessness?: number;
  emotions?: number;
  burnout?: number;
  medEffect?: number;
  woreOffAt?: string;
  note?: string;
  id?: string;
  date?: string;
}

export type FocusKind = "FOCUS" | "BODY_DOUBLING" | "INTEREST" | "HYPERFOCUS";
export const FOCUS_KINDS: { id: FocusKind; label: string }[] = [
  { id: "FOCUS", label: "Focus timer" }, { id: "BODY_DOUBLING", label: "Body doubling" },
  { id: "INTEREST", label: "Special interest time" }, { id: "HYPERFOCUS", label: "Hyperfocus" },
];

export interface FocusSession {
  start: number;
  end?: number;
  kind: FocusKind;
  task?: string;
  estimateMinutes?: number;
  breaks: number;
  rating?: number;
  note?: string;
  id?: string;
}

export interface RoutineStep {
  text: string;
  minutes?: number;
}

export interface Routine {
  name: string;
  steps: RoutineStep[];
  reminder?: string;
  days: number[];
  icon: string;
  active: boolean;
  id?: string;
}

export interface RoutineRun {
  routineId: string;
  date: string;
  done: number[];
  at?: number;
  id?: string;
}

export interface CommunicationCard {
  text: string;
  detail?: string;
  color?: string;
  id?: string;
}

export interface NeuroSummary {
  days: number;
  overloads: number;
  byKind: Count[];
  triggers: Count[];
  whatHelped: Count[];
  averageMasking?: number;
  averageEnergyUsed?: number;
  focusMinutes: number;
  estimateRatio?: number;
  patterns: Insight[];
  burnoutRisk?: string;
}

export interface NeuroToday {
  overloadsToday: number;
  energyLeft?: number;
  socialBattery?: number;
  routinesDue: string[];
  focusMinutes: number;
}

// ---------------------------------------------------------------- Transition (Transition.kt)

export type MilestoneArea = "SOCIAL" | "LEGAL" | "MEDICAL" | "VOICE" | "OTHER";
export const MILESTONE_AREAS: { id: MilestoneArea; label: string }[] = [
  { id: "SOCIAL", label: "Social" }, { id: "LEGAL", label: "Legal and documents" }, { id: "MEDICAL", label: "Medical" },
  { id: "VOICE", label: "Voice" }, { id: "OTHER", label: "Other" },
];
export type MilestoneStatus = "IDEA" | "PLANNED" | "IN_PROGRESS" | "DONE";
export const MILESTONE_STATUSES: { id: MilestoneStatus; label: string }[] = [
  { id: "IDEA", label: "Thinking about it" }, { id: "PLANNED", label: "Planned" }, { id: "IN_PROGRESS", label: "In progress" }, { id: "DONE", label: "Done" },
];

export interface ChecklistItem {
  text: string;
  done: boolean;
}

export interface Milestone {
  title: string;
  area: MilestoneArea;
  status: MilestoneStatus;
  date?: string;
  notes?: string;
  checklist: ChecklistItem[];
  id?: string;
}

export interface MilestoneTemplate {
  title: string;
  area: MilestoneArea;
  checklist: string[];
  about?: string;
}

export interface BodyChange {
  date: string;
  area: string;
  note: string;
  feeling?: number;
  id?: string;
}

export interface VoiceSession {
  at: number;
  minutes: number;
  exercises: string[];
  pitchAvgHz?: number;
  pitchMinHz?: number;
  pitchMaxHz?: number;
  feeling?: number;
  note?: string;
  id?: string;
}

export type HairRemovalMethod = "LASER" | "ELECTROLYSIS" | "IPL" | "WAXING" | "OTHER";
export const HAIR_METHODS: { id: HairRemovalMethod; label: string }[] = [
  { id: "LASER", label: "Laser" }, { id: "ELECTROLYSIS", label: "Electrolysis" }, { id: "IPL", label: "IPL" },
  { id: "WAXING", label: "Waxing" }, { id: "OTHER", label: "Other" },
];

export interface HairRemovalSession {
  at: number;
  method: HairRemovalMethod;
  area: string;
  minutes?: number;
  pulses?: number;
  provider?: string;
  pain?: number;
  cost?: number;
  note?: string;
  id?: string;
}

export type MomentKind = "EUPHORIA" | "DYSPHORIA";

export interface GenderMoment {
  at: number;
  kind: MomentKind;
  intensity: number;
  context: string[];
  note?: string;
  id?: string;
}

export interface TransitionProfile {
  pronouns?: string;
  startedOn?: string;
  goals: string[];
  voiceTargetHz?: number;
}

export interface TransitionOverview {
  profile: TransitionProfile;
  sinceStart?: string;
  onHormones?: string;
  milestones: Milestone[];
  recentChanges: BodyChange[];
  voice: VoiceSession[];
  hairByArea: Count[];
  euphoria: number;
  dysphoria: number;
  highlights: string[];
  /** Body measurements (cm) over the last two years, oldest first, when Fields.TRANSITION_MEASUREMENTS is on. */
  measurements: MeasurementSet[];
}

/** One time's body measurements, in cm, by metric. */
export interface MeasurementSet {
  at: number;
  values: Partial<Record<Metric, number>>;
}

export interface TransitionToday {
  headline?: string;
  milestonesDue: string[];
  euphoriaThisWeek: number;
}

// ---------------------------------------------------------------- Progress photos

export type Pose = "FRONT" | "SIDE" | "BACK" | "FACE" | "FACE_SIDE" | "OTHER";
export const POSES: { id: Pose; label: string }[] = [
  { id: "FRONT", label: "Front" }, { id: "SIDE", label: "Side" }, { id: "BACK", label: "Back" }, { id: "FACE", label: "Face" },
  { id: "FACE_SIDE", label: "Face, side" }, { id: "OTHER", label: "Other" },
];

export interface Photo {
  album: string;
  pose: Pose;
  at: number;
  note?: string;
  tags: string[];
  id?: string;
  date?: string;
  width?: number;
  height?: number;
  weightKg?: number;
  onHormones?: string;
}

export interface PhotoAlbum {
  name: string;
  count: number;
  latest?: string;
  coverId?: string;
}

/** Body measurements, in cm, saved as Metric samples like weight. */
export const BODY_MEASUREMENTS: { id: Metric; label: string }[] = [
  { id: "WAIST", label: "Waist" }, { id: "HIPS", label: "Hips" }, { id: "CHEST", label: "Chest" }, { id: "BUST", label: "Bust" },
  { id: "UNDERBUST", label: "Underbust" }, { id: "SHOULDERS", label: "Shoulders" }, { id: "NECK", label: "Neck" },
  { id: "UPPER_ARM", label: "Upper arm" }, { id: "THIGH", label: "Thigh" }, { id: "CALF", label: "Calf" },
];

// ---------------------------------------------------------------- The AI assistant (Ai.kt)

export interface AiSettings {
  enabled: boolean;
  model: string;
  effort: string;
  dailyLimit: number;
  allowLogging: boolean;
  acknowledgedAt?: number;
  hasKey: boolean;
}

export interface AiConsent {
  modules: Module[];
  allowLogging: boolean;
  acknowledged: boolean;
  sensitiveAcknowledged: boolean;
  since?: number;
}

export interface AiStatus {
  available: boolean;
  provider: string;
  model?: string;
  consent?: AiConsent;
  usedToday: number;
  dailyLimit: number;
  /** Whether the admin lets people allow it to log entries for them. */
  loggingAllowed: boolean;
  dataNotice: string;
  termsUrl: string;
  privacyUrl: string;
}

export interface AiMessage {
  role: "user" | "assistant";
  text: string;
}

export interface AiLook {
  tool: string;
  module?: Module;
  summary: string;
}

export interface AiChatReply {
  text: string;
  looked: AiLook[];
  modules: Module[];
  usedToday: number;
}

export interface AiBreakdownReply {
  steps: RoutineStep[];
}

export interface AiAuditEntry {
  at: number;
  kind: string;
  modules: Module[];
  tools: string[];
  model: string;
  inputTokens: number;
  outputTokens: number;
}

// ---------------------------------------------------------------- Arranging screens (Layouts.kt)

/** One screen: its sections in the person's order, and those hidden. */
export interface ScreenLayout {
  order: string[];
  hidden: string[];
}

/** Every screen the person has arranged (GET/PUT /api/layout), by key. */
export interface LayoutPrefs {
  screens: Record<string, ScreenLayout>;
}

/** Keys shared with the phone app; other screens are "web.<screen>". */
export const LAYOUT_TODAY = "today";
export const LAYOUT_QUICK = "today.quick";
export const LAYOUT_NAV = "nav";
export const layoutKey = (screen: string) => `web.${screen}`;
export const LAYOUT_MAX_SECTIONS = 80;

export type TodaySection =
  | "SCORES" | "INSIGHTS" | "QUICK" | "MEDS" | "MIND" | "CYCLE" | "CONTINENCE" | "SUPPLIES" | "NEURO" | "TRANSITION"
  | "VITALS" | "DRINKS" | "SYMPTOMS" | "HABITS" | "HORMONES" | "LIFE_STAGE" | "APPOINTMENTS" | "ACTIVITY" | "SLEEP"
  | "HEART" | "STRESS" | "FOOD" | "WATER" | "WORKOUTS" | "BODY";

export type QuickAction =
  | "FOOD" | "WATER" | "MOOD" | "PERIOD" | "BLADDER" | "DOSE" | "WEIGHT" | "COFFEE" | "DRINK" | "WORKOUT" | "SLEEP"
  | "CHECK_IN" | "SYMPTOM" | "BLOOD_PRESSURE" | "GLUCOSE" | "HABIT" | "ENERGY" | "OVERLOAD" | "CALM" | "MOMENT"
  | "PHOTO" | "MEASUREMENTS" | "APPOINTMENT";

/** Quick actions, in the default order, with the feature each needs (null: always). */
export const QUICK_ACTIONS: { id: QuickAction; label: string; module: Module | null }[] = [
  { id: "FOOD", label: "Log food", module: "NUTRITION" }, { id: "WATER", label: "Water", module: "NUTRITION" },
  { id: "MOOD", label: "Mood", module: "MIND" }, { id: "PERIOD", label: "Period", module: "CYCLE" },
  { id: "BLADDER", label: "Bladder & bowel", module: "CONTINENCE" }, { id: "DOSE", label: "Medications", module: "MEDICATIONS" },
  { id: "WEIGHT", label: "Weight", module: "BODY" }, { id: "COFFEE", label: "Coffee", module: "NUTRITION" },
  { id: "DRINK", label: "A drink", module: "NUTRITION" }, { id: "WORKOUT", label: "Workout", module: "ACTIVITY" },
  { id: "SLEEP", label: "Add a night", module: "SLEEP" }, { id: "CHECK_IN", label: "Check in", module: null },
  { id: "SYMPTOM", label: "Symptom", module: "SYMPTOMS" }, { id: "BLOOD_PRESSURE", label: "Blood pressure", module: "VITALS" },
  { id: "GLUCOSE", label: "Glucose", module: "VITALS" }, { id: "HABIT", label: "Habits", module: "HABITS" },
  { id: "ENERGY", label: "Energy & focus", module: "NEURO" }, { id: "OVERLOAD", label: "Too much", module: "NEURO" },
  { id: "CALM", label: "Calm", module: "MIND" }, { id: "MOMENT", label: "Euphoria", module: "TRANSITION" },
  { id: "PHOTO", label: "Photo", module: "PHOTOS" }, { id: "MEASUREMENTS", label: "Measurements", module: "BODY" },
  { id: "APPOINTMENT", label: "Appointment", module: "APPOINTMENTS" },
];
/** How many quick actions show before "More", unless the person has arranged them. */
export const QUICK_SHOWN = 6;

export type NavPage = "TODAY" | "TRENDS" | "FOOD" | "REPORTS" | "DEVICES" | "CONDITIONS" | "ASSISTANT";

/** Applies a saved layout to a screen's sections (ids in the default order): the person's order, then new ones where they'd be. */
export function arrange<T>(defaults: T[], id: (t: T) => string, layout?: ScreenLayout): T[] {
  if (!layout || layout.order.length === 0) return defaults;
  const byId = new Map(defaults.map((d) => [id(d), d] as const));
  const placed: T[] = layout.order.map((k) => byId.get(k)).filter((d): d is T => d !== undefined);
  defaults.forEach((item, i) => {
    if (layout.order.includes(id(item))) return;
    const before = defaults.slice(0, i).reverse().find((prev) => placed.includes(prev));
    placed.splice(before === undefined ? 0 : placed.indexOf(before) + 1, 0, item);
  });
  return placed;
}

/** arrange(), less what's hidden. */
export function visible<T>(defaults: T[], id: (t: T) => string, layout?: ScreenLayout): T[] {
  const hidden = new Set(layout?.hidden ?? []);
  return arrange(defaults, id, layout).filter((d) => !hidden.has(id(d)));
}
