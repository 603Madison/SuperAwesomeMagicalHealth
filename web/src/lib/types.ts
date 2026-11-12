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
  oidc: OidcSettings;
}

export interface AdminSettings {
  settings: ServerSettings;
  oidcClientSecretSet: boolean;
  redirectUri: string;
  fromEnvironment: string[];
  version: string;
}
