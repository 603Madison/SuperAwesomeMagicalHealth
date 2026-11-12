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
  | "VITALS" | "HABITS" | "SYMPTOMS" | "HORMONES" | "APPOINTMENTS";

export const SENSITIVE: Module[] = ["MIND", "CYCLE", "CONTINENCE", "MEDICATIONS", "SYMPTOMS", "HORMONES", "APPOINTMENTS"];

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
  | "GLUCOSE_HIGH" | "MIGRAINE" | "HABIT_DUE" | "APPOINTMENT_SOON" | "DEVICE_BATTERY_LOW" | "DEVICE_QUIET" | "DIGEST" | "TEST";

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
  | "MIND" | "CYCLE" | "CONTINENCE" | "SYMPTOMS" | "HABITS" | "APPOINTMENTS";

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
