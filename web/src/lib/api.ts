import type {
  AdminSettings, AuthConfig, CheckIn, DaySummary, Device, Food, FoodDay, FoodEntry, FoodSearch, FoodSuggestions, Meal, Metric, Nutrients,
  OidcSettings, Point, Profile, Sample, ServerSettings, SessionInfo, SleepSummary, Trends, User, WaterEntry, Workout,
  ContinenceEntry, ContinenceSummary, ContinenceWearing, ProductStats, CrisisResource, CycleDay, CycleOverview, DoseLog, JournalEntry, LabResult, Medication, MedsDay,
  MindSummary, MindfulnessSession, Module, MoodEntry, ScreenerDefinition, ScreenerResult,
  Appointment, BloodPressure, Digest, DoseChange, Drink, DrinksDay, DrinksSummary, Contraction, Feed, GlucoseReading, Habit,
  HabitStatus, HabitTick, HormoneOverview, HormoneSettings, HotFlash, IntegrationKey, KickSession, LifeStageInfo,
  LifeStageOverview, Migraine, Place, PushKey, PushMessage, PushSubscriptionInfo, ReportSectionKey, Shares, Share,
  SmtpSettings, SuggestedQuestion, SymptomEntry, SymptomsSummary, VitalsSummary, Webhook, WebhookDelivery,
  ConditionDefinition, MyConditions, FieldLayout, FieldChoices, ContinenceSettings, ContinenceProgress, ContinenceCheckIn,
  Supply, SupplyStatus, SupplyAdjustment, CycleSettings, NeuroProfile, OverloadEvent, EnergyDay, NeuroCheckIn, FocusSession,
  Routine, RoutineRun, CommunicationCard, NeuroSummary, TransitionOverview, TransitionProfile, MilestoneTemplate, Milestone,
  BodyChange, VoiceSession, HairRemovalSession, GenderMoment, Photo, PhotoAlbum, AiSettings, AiStatus, AiConsent, AiMessage,
  AiChatReply, AiBreakdownReply, AiAuditEntry, LayoutPrefs, ScreenLayout, VoicePlan, VoiceProgram, VoiceProgress, DeviceChoices, FoodPhotoEstimate,
  NutrientTargets,
} from "./types";
import { viewingOwner } from "./viewing";

/** An error from the server, with a message meant for people. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Told when the server says the sign-in is gone, so the app can show the sign-in page. */
let onSignedOut: (() => void) | null = null;
export function setSignedOutHandler(handler: () => void) {
  onSignedOut = handler;
}

/** The paths that hold a person's health data: the ones someone they share with may read. */
const SHARED_PATHS = [
  "/api/day/", "/api/trends", "/api/series", "/api/food/", "/api/measurements", "/api/workouts", "/api/sleep", "/api/checkins/",
  "/api/mind/", "/api/cycle", "/api/continence/", "/api/meds", "/api/labs", "/api/vitals/", "/api/drinks", "/api/symptoms/",
  "/api/habits", "/api/hormones", "/api/appointments", "/api/supplies", "/api/neuro/", "/api/transition", "/api/photos",
  // Their field layout, filtered by the server to the features they shared.
  "/api/fields",
];

/** The person's own settings, never someone's share: no X-SAMH-View, and no changes while viewing one. */
const OWN_PATHS = ["/api/devices/choices", "/api/food/targets"];

/** Where a photo's picture is, for an <img> (the session cookie goes with it). */
export const photoUrl = (id: string, thumb = false) => `/api/photos/${encodeURIComponent(id)}/${thumb ? "thumb" : "image"}`;

/**
 * A photo as a blob URL, fetched with the X-SAMH-View header: an <img> can't send headers, so
 * someone's shared photos are fetched this way. The caller revokes the URL when done.
 */
export async function sharedPhotoBlobUrl(id: string, thumb: boolean): Promise<string> {
  const owner = viewingOwner();
  const response = await fetch(photoUrl(id, thumb), { credentials: "same-origin", headers: owner ? { "X-SAMH-View": owner } : {} });
  if (!response.ok) throw new ApiError(response.status, `The server answered ${response.status}`);
  return URL.createObjectURL(await response.blob());
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  // Every change carries this header, which other sites' pages can't send.
  if (method !== "GET") headers["X-SAMH"] = "1";
  // Viewing someone's shared data: reads of their data say whose, and nothing of theirs can change.
  const owner = viewingOwner();
  if (owner && method !== "GET" && OWN_PATHS.some((p) => path.startsWith(p))) {
    throw new ApiError(403, "You're looking at someone else's data, so nothing can be changed here.");
  }
  if (owner && SHARED_PATHS.some((p) => path.startsWith(p)) && !OWN_PATHS.some((p) => path.startsWith(p))) {
    if (method !== "GET") throw new ApiError(403, "You're looking at someone else's data, so nothing can be changed here.");
    headers["X-SAMH-View"] = owner;
  }
  const isForm = body instanceof FormData;
  if (body !== undefined && !isForm) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(path, {
      method, headers, credentials: "same-origin",
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the server");
  }
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/")) onSignedOut?.();
    const message = (data && typeof data === "object" && "error" in data && typeof data.error === "string") ? data.error : `The server answered ${response.status}`;
    throw new ApiError(response.status, message);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** A file from the server (a PDF, a zip), saved by the browser as [name]. */
async function download(path: string, name: string): Promise<void> {
  let response: Response;
  try {
    response = await fetch(path, { credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "Can't reach the server");
  }
  if (!response.ok) {
    const data = safeJson(await response.text());
    const message = (data && typeof data === "object" && "error" in data && typeof data.error === "string") ? data.error : `The server answered ${response.status}`;
    throw new ApiError(response.status, message);
  }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {});
const patch = <T>(path: string, body: unknown) => request<T>("PATCH", path, body);
const put = <T>(path: string, body: unknown) => request<T>("PUT", path, body);
const del = <T>(path: string) => request<T>("DELETE", path);
const q = encodeURIComponent;

export const api = {
  authConfig: () => get<AuthConfig>("/api/auth/config"),
  login: (username: string, password: string) => post<{ user: User }>("/api/auth/login", { username, password }),
  logout: () => post<unknown>("/api/auth/logout"),
  appCode: (challenge: string) => post<{ code: string }>("/api/auth/app/code", { challenge }),

  me: () => get<{ user: User; session: SessionInfo }>("/api/me"),
  updateName: (displayName: string) => patch<User>("/api/me", { displayName }),
  changePassword: (current: string, next: string) => post<unknown>("/api/me/password", { current, new: next }),
  sessions: () => get<SessionInfo[]>("/api/me/sessions"),
  revokeSession: (id: string) => del<unknown>(`/api/me/sessions/${q(id)}`),
  connector: (allowLogging: boolean, allAccounts: boolean, modules: Module[]) =>
    post<{ url: string; token: string }>("/api/me/connectors", { allowLogging, allAccounts, modules }),

  profile: () => get<Profile>("/api/profile"),
  saveProfile: (profile: Profile) => put<Profile>("/api/profile", profile),
  day: (date: string) => get<DaySummary>(`/api/day/${q(date)}`),
  trends: (days: number) => get<Trends>(`/api/trends?days=${days}`),
  series: (metric: Metric, from: number, to: number) => get<Point[]>(`/api/series?metric=${metric}&from=${from}&to=${to}`),

  foodDay: (date: string) => get<FoodDay>(`/api/food/${q(date)}`),
  nutrientTargets: () => get<NutrientTargets>("/api/food/targets"),
  saveNutrientTargets: (own: Partial<Record<string, number>>) => put<NutrientTargets>("/api/food/targets", { own }),
  logFood: (entry: { date: string; meal: Meal; servings: number; food?: Food; name?: string; nutrients?: Nutrients; serving?: string }) => post<FoodEntry>("/api/food/entries", entry),
  changeEntry: (id: string, change: { meal?: Meal; servings?: number; date?: string }) => patch<FoodEntry>(`/api/food/entries/${q(id)}`, change),
  deleteEntry: (id: string) => del<unknown>(`/api/food/entries/${q(id)}`),
  copyMeal: (from: string, to: string, meal?: Meal) => post<{ copied: number }>("/api/food/copy", { from, to, meal }),
  /** Ideas from what's been eaten before, for `meal` (the one it is now, without). */
  foodSuggestions: (date: string, meal?: Meal) => get<FoodSuggestions>(`/api/food/suggestions?date=${q(date)}${meal ? `&meal=${meal}` : ""}`),
  searchFoods: (query: string) => get<FoodSearch>(`/api/foods/search?q=${q(query)}`),
  barcode: (code: string) => get<Food>(`/api/foods/barcode/${q(code)}`),
  recentFoods: () => get<Food[]>("/api/foods/recent"),
  saveFood: (food: Food) => (food.id ? put<Food>(`/api/foods/${q(food.id)}`, food) : post<Food>("/api/foods", food)),
  deleteFood: (id: string) => del<unknown>(`/api/foods/${q(id)}`),
  addWater: (date: string, ml: number) => post<WaterEntry>("/api/water", { date, ml }),
  deleteWater: (id: string) => del<unknown>(`/api/water/${q(id)}`),

  measurements: (metric: Metric, days = 365) => get<Sample[]>(`/api/measurements?metric=${metric}&days=${days}`),
  addMeasurement: (metric: Metric, value: number, at?: number) => post<Sample>("/api/measurements", { metric, value, at }),
  deleteMeasurement: (id: number) => del<unknown>(`/api/measurements/${id}`),
  workouts: (days = 60) => get<Workout[]>(`/api/workouts?days=${days}`),
  saveWorkout: (w: Workout) => (w.id ? put<Workout>(`/api/workouts/${q(w.id)}`, w) : post<Workout>("/api/workouts", w)),
  deleteWorkout: (id: string) => del<unknown>(`/api/workouts/${q(id)}`),
  sleep: (days = 30) => get<SleepSummary[]>(`/api/sleep?days=${days}`),
  addSleep: (start: number, end: number, note?: string) => post<unknown>("/api/sleep", { start, end, note, stages: [] }),
  deleteSleep: (id: string) => del<unknown>(`/api/sleep/${q(id)}`),
  checkIn: (date: string) => get<CheckIn>(`/api/checkins/${q(date)}`),
  saveCheckIn: (checkIn: CheckIn) => put<CheckIn>(`/api/checkins/${q(checkIn.date)}`, checkIn),

  devices: () => get<Device[]>("/api/devices"),
  renameDevice: (id: string, name: string) => patch<Device>(`/api/devices/${q(id)}`, { name }),
  deleteDevice: (id: string) => del<unknown>(`/api/devices/${q(id)}`),
  // Devices that measure the same thing: one device for a reading, or all combined (always the person's own)
  deviceChoices: () => get<DeviceChoices>("/api/devices/choices"),
  saveDeviceChoices: (c: DeviceChoices) => put<DeviceChoices>("/api/devices/choices", c),
  uploadKey: (name: string) => post<{ id: string; name: string; token: string; url: string }>("/api/devices/upload-keys", { name }),
  importFit: (files: File[]) => {
    const form = new FormData();
    files.forEach((f) => form.append("file", f, f.name));
    return post<{ workouts: number; samples: number; sleep: number; message: string }>("/api/import/fit", form);
  },

  moods: (days = 30) => get<MoodEntry[]>(`/api/mind/moods?days=${days}`),
  saveMood: (m: MoodEntry) => (m.id ? put<MoodEntry>(`/api/mind/moods/${q(m.id)}`, m) : post<MoodEntry>("/api/mind/moods", m)),
  deleteMood: (id: string) => del<unknown>(`/api/mind/moods/${q(id)}`),
  journal: (days = 90) => get<JournalEntry[]>(`/api/mind/journal?days=${days}`),
  saveJournal: (j: JournalEntry) => (j.id ? put<JournalEntry>(`/api/mind/journal/${q(j.id)}`, j) : post<JournalEntry>("/api/mind/journal", j)),
  deleteJournal: (id: string) => del<unknown>(`/api/mind/journal/${q(id)}`),
  screeners: () => get<ScreenerDefinition[]>("/api/mind/screeners"),
  screenerResults: (days = 365) => get<ScreenerResult[]>(`/api/mind/screeners/results?days=${days}`),
  submitScreener: (screener: string, answers: number[]) => post<ScreenerResult>("/api/mind/screeners/results", { screener, at: Date.now(), answers }),
  deleteScreener: (id: string) => del<unknown>(`/api/mind/screeners/results/${q(id)}`),
  mindSessions: (days = 30) => get<MindfulnessSession[]>(`/api/mind/sessions?days=${days}`),
  saveSession: (s: Omit<MindfulnessSession, "id" | "date">) => post<MindfulnessSession>("/api/mind/sessions", s),
  mindSummary: (days = 30) => get<MindSummary>(`/api/mind/summary?days=${days}`),
  resources: () => get<CrisisResource[]>("/api/mind/resources"),

  cycle: (months = 12) => get<CycleOverview>(`/api/cycle?months=${months}`),
  saveCycleDay: (day: CycleDay) => put<CycleDay>(`/api/cycle/days/${q(day.date)}`, day),
  deleteCycleDay: (date: string) => del<unknown>(`/api/cycle/days/${q(date)}`),

  continence: (days = 7) => get<ContinenceEntry[]>(`/api/continence/entries?days=${days}`),
  saveContinence: (e: ContinenceEntry) => (e.id ? put<ContinenceEntry>(`/api/continence/entries/${q(e.id)}`, e) : post<ContinenceEntry>("/api/continence/entries", e)),
  deleteContinence: (id: string) => del<unknown>(`/api/continence/entries/${q(id)}`),
  continenceSummary: (days = 14) => get<ContinenceSummary>(`/api/continence/summary?days=${days}`),
  continenceWearing: () => get<ContinenceWearing>("/api/continence/wearing"),
  continenceProducts: (days = 90) => get<ProductStats[]>(`/api/continence/products?days=${days}`),

  meds: (all = false) => get<Medication[]>(`/api/meds${all ? "?all=true" : ""}`),
  saveMed: (m: Medication) => (m.id ? put<Medication>(`/api/meds/${q(m.id)}`, m) : post<Medication>("/api/meds", m)),
  deleteMed: (id: string) => del<unknown>(`/api/meds/${q(id)}`),
  medsDay: (date: string) => get<MedsDay>(`/api/meds/day/${q(date)}`),
  logDose: (d: DoseLog) => post<DoseLog>("/api/meds/doses", d),
  deleteDose: (id: string) => del<unknown>(`/api/meds/doses/${q(id)}`),
  labs: () => get<LabResult[]>("/api/labs"),
  saveLab: (l: LabResult) => post<LabResult>("/api/labs", l),
  deleteLab: (id: string) => del<unknown>(`/api/labs/${q(id)}`),

  // Blood pressure and glucose
  bloodPressure: (days = 30) => get<BloodPressure[]>(`/api/vitals/bp?days=${days}`),
  addBloodPressure: (bp: BloodPressure) => post<BloodPressure>("/api/vitals/bp", bp),
  deleteBloodPressure: (id: string) => del<unknown>(`/api/vitals/bp/${q(id)}`),
  glucose: (days = 14) => get<GlucoseReading[]>(`/api/vitals/glucose?days=${days}`),
  addGlucose: (g: GlucoseReading) => post<GlucoseReading>("/api/vitals/glucose", g),
  deleteGlucose: (id: string) => del<unknown>(`/api/vitals/glucose/${q(id)}`),
  vitalsSummary: (days = 30) => get<VitalsSummary>(`/api/vitals/summary?days=${days}`),

  // Drinks
  drinksDay: (date: string) => get<DrinksDay>(`/api/drinks/${q(date)}`),
  saveDrink: (d: Drink) => (d.id ? put<Drink>(`/api/drinks/${q(d.id)}`, d) : post<Drink>("/api/drinks", d)),
  deleteDrink: (id: string) => del<unknown>(`/api/drinks/${q(id)}`),
  drinksSummary: (days = 30) => get<DrinksSummary>(`/api/drinks/summary?days=${days}`),

  // Symptoms and migraines
  symptoms: (days = 30) => get<SymptomEntry[]>(`/api/symptoms/entries?days=${days}`),
  saveSymptom: (e: SymptomEntry) => (e.id ? put<SymptomEntry>(`/api/symptoms/entries/${q(e.id)}`, e) : post<SymptomEntry>("/api/symptoms/entries", e)),
  deleteSymptom: (id: string) => del<unknown>(`/api/symptoms/entries/${q(id)}`),
  migraines: (days = 90) => get<Migraine[]>(`/api/symptoms/migraines?days=${days}`),
  saveMigraine: (m: Migraine) => (m.id ? put<Migraine>(`/api/symptoms/migraines/${q(m.id)}`, m) : post<Migraine>("/api/symptoms/migraines", m)),
  deleteMigraine: (id: string) => del<unknown>(`/api/symptoms/migraines/${q(id)}`),
  symptomsSummary: (days = 90) => get<SymptomsSummary>(`/api/symptoms/summary?days=${days}`),
  places: (query: string) => get<Place[]>(`/api/places?q=${q(query)}`),

  // Habits
  habits: (all = false) => get<Habit[]>(`/api/habits${all ? "?all=true" : ""}`),
  saveHabit: (h: Habit) => (h.id ? put<Habit>(`/api/habits/${q(h.id)}`, h) : post<Habit>("/api/habits", h)),
  deleteHabit: (id: string) => del<unknown>(`/api/habits/${q(id)}`),
  habitsToday: (date?: string) => get<HabitStatus[]>(`/api/habits/today${date ? `?date=${q(date)}` : ""}`),
  tickHabit: (id: string, tick: HabitTick) => put<HabitTick>(`/api/habits/${q(id)}/ticks`, tick),
  habitHistory: (id: string, days = 90) => get<HabitTick[]>(`/api/habits/${q(id)}/history?days=${days}`),

  // Hormone therapy
  hormones: () => get<HormoneOverview>("/api/hormones"),
  saveHormoneSettings: (s: HormoneSettings) => put<HormoneSettings>("/api/hormones/settings", s),
  addDoseChange: (c: DoseChange) => post<DoseChange>("/api/hormones/changes", c),
  deleteDoseChange: (id: string) => del<unknown>(`/api/hormones/changes/${q(id)}`),

  // Life stages
  lifeStage: () => get<LifeStageInfo>("/api/cycle/stage"),
  saveLifeStage: (info: LifeStageInfo) => put<LifeStageInfo>("/api/cycle/stage", info),
  lifeStageOverview: (days = 30) => get<LifeStageOverview>(`/api/cycle/stage/overview?days=${days}`),
  saveKicks: (k: KickSession) => (k.id ? put<KickSession>(`/api/cycle/kicks/${q(k.id)}`, k) : post<KickSession>("/api/cycle/kicks", k)),
  deleteKicks: (id: string) => del<unknown>(`/api/cycle/kicks/${q(id)}`),
  saveContraction: (c: Contraction) => (c.id ? put<Contraction>(`/api/cycle/contractions/${q(c.id)}`, c) : post<Contraction>("/api/cycle/contractions", c)),
  deleteContraction: (id: string) => del<unknown>(`/api/cycle/contractions/${q(id)}`),
  saveFeed: (f: Feed) => (f.id ? put<Feed>(`/api/cycle/feeds/${q(f.id)}`, f) : post<Feed>("/api/cycle/feeds", f)),
  deleteFeed: (id: string) => del<unknown>(`/api/cycle/feeds/${q(id)}`),
  addHotFlash: (h: HotFlash) => post<HotFlash>("/api/cycle/hot-flashes", h),
  deleteHotFlash: (id: string) => del<unknown>(`/api/cycle/hot-flashes/${q(id)}`),

  // Appointments
  appointments: (days = 365, upcoming = false) => get<Appointment[]>(`/api/appointments?days=${days}${upcoming ? "&upcoming=true" : ""}`),
  saveAppointment: (a: Appointment) => (a.id ? put<Appointment>(`/api/appointments/${q(a.id)}`, a) : post<Appointment>("/api/appointments", a)),
  deleteAppointment: (id: string) => del<unknown>(`/api/appointments/${q(id)}`),
  appointmentSuggestions: (id: string) => get<SuggestedQuestion[]>(`/api/appointments/${q(id)}/suggestions`),

  // Sharing
  shares: () => get<Shares>("/api/shares"),
  share: (username: string, modules: Module[]) => post<Share>("/api/shares", { username, modules }),
  changeShare: (id: string, modules: Module[]) => put<Share>(`/api/shares/${q(id)}`, { modules }),
  deleteShare: (id: string) => del<unknown>(`/api/shares/${q(id)}`),

  // Browser notifications, reminders, the digest, webhooks, Home Assistant
  pushKey: () => get<PushKey>("/api/push/key"),
  pushSubscriptions: () => get<PushSubscriptionInfo[]>("/api/push/subscriptions"),
  addPushSubscription: (endpoint: string, p256dh: string, auth: string, name: string) =>
    post<PushSubscriptionInfo>("/api/push/subscriptions", { endpoint, p256dh, auth, name }),
  deletePushSubscription: (id: string) => del<unknown>(`/api/push/subscriptions/${q(id)}`),
  testPush: () => post<unknown>("/api/push/test"),
  upcomingReminders: (hours = 24) => get<PushMessage[]>(`/api/reminders/upcoming?hours=${hours}`),
  latestDigest: () => get<Digest>("/api/digest/latest"),
  digest: (week: string) => get<Digest>(`/api/digest?week=${q(week)}`),
  emailDigest: () => post<unknown>("/api/digest/email-test"),
  smtpStatus: () => get<{ configured: boolean }>("/api/digest/smtp"),
  adminSmtp: () => get<{ settings: SmtpSettings; passwordSet: boolean }>("/api/admin/smtp"),
  saveAdminSmtp: (settings: SmtpSettings, password?: string) => put<{ settings: SmtpSettings; passwordSet: boolean }>("/api/admin/smtp", { settings, password }),
  testAdminSmtp: (to: string) => post<unknown>("/api/admin/smtp/test", { to }),
  webhooks: () => get<Webhook[]>("/api/webhooks"),
  saveWebhook: (w: Webhook) => (w.id ? put<Webhook>(`/api/webhooks/${q(w.id)}`, w) : post<Webhook>("/api/webhooks", w)),
  deleteWebhook: (id: string) => del<unknown>(`/api/webhooks/${q(id)}`),
  testWebhook: (id: string) => post<unknown>(`/api/webhooks/${q(id)}/test`),
  webhookDeliveries: () => get<WebhookDelivery[]>("/api/webhooks/deliveries"),
  integrationKeys: () => get<IntegrationKey[]>("/api/integrations/keys"),
  addIntegrationKey: (name: string) => post<IntegrationKey>("/api/integrations/keys", { name }),
  deleteIntegrationKey: (id: string) => del<unknown>(`/api/integrations/keys/${q(id)}`),

  // Reports, export and the watch
  downloadReport: (from: string, to: string, sections: ReportSectionKey[]) =>
    download(`/api/report.pdf?from=${q(from)}&to=${q(to)}&sections=${sections.join(",")}`, `health-report-${from}-to-${to}.pdf`),
  downloadExport: (from: string, to: string) => download(`/api/export.zip?from=${q(from)}&to=${q(to)}`, `health-export-${from}-to-${to}.zip`),
  watchKey: () => post<IntegrationKey>("/api/watch/keys"),
  downloadFit: (from: string, to: string) => download(`/api/export/fit?from=${q(from)}&to=${q(to)}`, `health-fit-${from}-to-${to}.zip`),

  // Conditions and goals, and which fields show
  conditionCatalog: () => get<ConditionDefinition[]>("/api/conditions/catalog"),
  conditions: () => get<MyConditions>("/api/conditions"),
  saveConditions: (c: MyConditions) => put<MyConditions>("/api/conditions", c),
  fields: () => get<FieldLayout>("/api/fields"),
  saveFields: (c: FieldChoices) => put<FieldLayout>("/api/fields", c),

  // How the person has arranged each screen (always their own: never sent with X-SAMH-View)
  layout: () => get<LayoutPrefs>("/api/layout"),
  saveLayout: (key: string, layout: ScreenLayout) => put<LayoutPrefs>(`/api/layout/${q(key)}`, layout),
  resetLayout: (key: string) => del<LayoutPrefs>(`/api/layout/${q(key)}`),
  saveAllLayouts: (prefs: LayoutPrefs) => put<LayoutPrefs>("/api/layout", prefs),

  // Bladder and bowel, in detail; supplies
  continenceSettings: () => get<ContinenceSettings>("/api/continence/settings"),
  saveContinenceSettings: (s: ContinenceSettings) => put<ContinenceSettings>("/api/continence/settings", s),
  continenceProgress: (weeks = 12) => get<ContinenceProgress>(`/api/continence/progress?weeks=${weeks}`),
  continenceCheckIns: (days = 180) => get<ContinenceCheckIn[]>(`/api/continence/check-ins?days=${days}`),
  addContinenceCheckIn: (c: ContinenceCheckIn) => post<ContinenceCheckIn>("/api/continence/check-ins", c),
  deleteContinenceCheckIn: (id: string) => del<unknown>(`/api/continence/check-ins/${q(id)}`),
  supplies: (all = false) => get<Supply[]>(`/api/supplies${all ? "?all=true" : ""}`),
  saveSupply: (s: Supply) => (s.id ? put<Supply>(`/api/supplies/${q(s.id)}`, s) : post<Supply>("/api/supplies", s)),
  deleteSupply: (id: string) => del<unknown>(`/api/supplies/${q(id)}`),
  supplyStatus: () => get<SupplyStatus[]>("/api/supplies/status"),
  adjustSupply: (id: string, a: SupplyAdjustment) => post<SupplyAdjustment>(`/api/supplies/${q(id)}/adjust`, a),
  supplyLog: (id: string, days = 90) => get<SupplyAdjustment[]>(`/api/supplies/${q(id)}/log?days=${days}`),

  // Cycle settings
  cycleSettings: () => get<CycleSettings>("/api/cycle/settings"),
  saveCycleSettings: (s: CycleSettings) => put<CycleSettings>("/api/cycle/settings", s),

  // Autism and ADHD
  neuroProfile: () => get<NeuroProfile>("/api/neuro/profile"),
  saveNeuroProfile: (p: NeuroProfile) => put<NeuroProfile>("/api/neuro/profile", p),
  overloads: (days = 90) => get<OverloadEvent[]>(`/api/neuro/events?days=${days}`),
  saveOverload: (e: OverloadEvent) => (e.id ? put<OverloadEvent>(`/api/neuro/events/${q(e.id)}`, e) : post<OverloadEvent>("/api/neuro/events", e)),
  deleteOverload: (id: string) => del<unknown>(`/api/neuro/events/${q(id)}`),
  energy: (days = 30) => get<EnergyDay[]>(`/api/neuro/energy?days=${days}`),
  saveEnergy: (d: EnergyDay) => put<EnergyDay>(`/api/neuro/energy/${q(d.date)}`, d),
  neuroCheckIns: (days = 30) => get<NeuroCheckIn[]>(`/api/neuro/check-ins?days=${days}`),
  addNeuroCheckIn: (c: NeuroCheckIn) => post<NeuroCheckIn>("/api/neuro/check-ins", c),
  deleteNeuroCheckIn: (id: string) => del<unknown>(`/api/neuro/check-ins/${q(id)}`),
  focusSessions: (days = 30) => get<FocusSession[]>(`/api/neuro/focus?days=${days}`),
  saveFocus: (f: FocusSession) => (f.id ? put<FocusSession>(`/api/neuro/focus/${q(f.id)}`, f) : post<FocusSession>("/api/neuro/focus", f)),
  deleteFocus: (id: string) => del<unknown>(`/api/neuro/focus/${q(id)}`),
  routines: () => get<Routine[]>("/api/neuro/routines"),
  saveRoutine: (r: Routine) => (r.id ? put<Routine>(`/api/neuro/routines/${q(r.id)}`, r) : post<Routine>("/api/neuro/routines", r)),
  deleteRoutine: (id: string) => del<unknown>(`/api/neuro/routines/${q(id)}`),
  routineRuns: (days = 14) => get<RoutineRun[]>(`/api/neuro/routines/runs?days=${days}`),
  saveRoutineRun: (r: RoutineRun) => put<RoutineRun>(`/api/neuro/routines/${q(r.routineId)}/runs/${q(r.date)}`, r),
  cards: () => get<CommunicationCard[]>("/api/neuro/cards"),
  saveCards: (cards: CommunicationCard[]) => put<CommunicationCard[]>("/api/neuro/cards", cards),
  neuroSummary: (days = 30) => get<NeuroSummary>(`/api/neuro/summary?days=${days}`),

  // Transition
  transition: () => get<TransitionOverview>("/api/transition"),
  transitionProfile: () => get<TransitionProfile>("/api/transition/profile"),
  saveTransitionProfile: (p: TransitionProfile) => put<TransitionProfile>("/api/transition/profile", p),
  milestoneTemplates: () => get<MilestoneTemplate[]>("/api/transition/templates"),
  milestones: () => get<Milestone[]>("/api/transition/milestones"),
  saveMilestone: (m: Milestone) => (m.id ? put<Milestone>(`/api/transition/milestones/${q(m.id)}`, m) : post<Milestone>("/api/transition/milestones", m)),
  deleteMilestone: (id: string) => del<unknown>(`/api/transition/milestones/${q(id)}`),
  bodyChanges: () => get<BodyChange[]>("/api/transition/changes"),
  saveBodyChange: (c: BodyChange) => (c.id ? put<BodyChange>(`/api/transition/changes/${q(c.id)}`, c) : post<BodyChange>("/api/transition/changes", c)),
  deleteBodyChange: (id: string) => del<unknown>(`/api/transition/changes/${q(id)}`),
  voiceSessions: (days = 180) => get<VoiceSession[]>(`/api/transition/voice?days=${days}`),
  addVoiceSession: (v: VoiceSession) => post<VoiceSession>("/api/transition/voice", v),
  deleteVoiceSession: (id: string) => del<unknown>(`/api/transition/voice/${q(id)}`),
  // The voice trainer: the sound stays in the browser; only the numbers are saved
  voiceProgram: () => get<VoiceProgram>("/api/transition/voice/program"),
  voicePlan: () => get<VoicePlan>("/api/transition/voice/plan"),
  saveVoicePlan: (p: VoicePlan) => put<VoicePlan>("/api/transition/voice/plan", p),
  voiceProgress: () => get<VoiceProgress>("/api/transition/voice/progress"),
  hairRemoval: (days = 365) => get<HairRemovalSession[]>(`/api/transition/hair-removal?days=${days}`),
  addHairRemoval: (h: HairRemovalSession) => post<HairRemovalSession>("/api/transition/hair-removal", h),
  deleteHairRemoval: (id: string) => del<unknown>(`/api/transition/hair-removal/${q(id)}`),
  moments: (days = 90) => get<GenderMoment[]>(`/api/transition/moments?days=${days}`),
  addMoment: (m: GenderMoment) => post<GenderMoment>("/api/transition/moments", m),
  deleteMoment: (id: string) => del<unknown>(`/api/transition/moments/${q(id)}`),

  // Progress photos
  photos: (album?: string, days?: number) => get<Photo[]>(`/api/photos?${album ? `album=${q(album)}&` : ""}${days ? `days=${days}` : ""}`),
  photoAlbums: () => get<PhotoAlbum[]>("/api/photos/albums"),
  uploadPhoto: (file: Blob, photo: Photo, name = "photo.jpg") => {
    const form = new FormData();
    form.append("file", file, name);
    form.append("photo", JSON.stringify(photo));
    return post<Photo>("/api/photos", form);
  },
  savePhoto: (p: Photo) => put<Photo>(`/api/photos/${q(p.id!)}`, p),
  deletePhoto: (id: string) => del<unknown>(`/api/photos/${q(id)}`),

  // The AI assistant
  adminAi: () => get<AiSettings>("/api/admin/ai"),
  saveAdminAi: (s: AiSettings) => put<AiSettings>("/api/admin/ai", s),
  setAiKey: (apiKey: string) => put<unknown>("/api/admin/ai/key", { apiKey }),
  deleteAiKey: () => del<unknown>("/api/admin/ai/key"),
  testAi: () => post<{ ok: boolean; message: string }>("/api/admin/ai/test"),
  aiStatus: () => get<AiStatus>("/api/ai/status"),
  saveAiConsent: (c: AiConsent) => put<AiConsent>("/api/ai/consent", c),
  withdrawAiConsent: () => del<unknown>("/api/ai/consent"),
  aiChat: (messages: AiMessage[]) => post<AiChatReply>("/api/ai/chat", { messages }),
  aiBreakdown: (task: string, detail?: string) => post<AiBreakdownReply>("/api/ai/breakdown", { task, detail }),
  aiAudit: (days = 30) => get<AiAuditEntry[]>(`/api/ai/audit?days=${days}`),
  /** A photo of a meal (already shrunk and stripped of its details here), estimated. Never while viewing a share. */
  aiFoodPhoto: (photo: Blob, meal?: Meal, note?: string) => {
    if (viewingOwner()) return Promise.reject(new ApiError(403, "You're looking at someone else's data, so nothing can be changed here."));
    const form = new FormData();
    form.append("photo", photo, "meal.jpg");
    if (meal) form.append("meal", meal);
    if (note?.trim()) form.append("note", note.trim());
    return post<FoodPhotoEstimate>("/api/ai/food-photo", form);
  },

  users: () => get<User[]>("/api/admin/users"),
  createUser: (username: string, displayName: string, password: string, role: string) =>
    post<User>("/api/admin/users", { username, displayName, password, role }),
  updateUser: (id: string, change: { displayName?: string; role?: string; disabled?: boolean }) => patch<User>(`/api/admin/users/${q(id)}`, change),
  deleteUser: (id: string) => del<unknown>(`/api/admin/users/${q(id)}`),
  resetPassword: (id: string, password: string) => post<unknown>(`/api/admin/users/${q(id)}/password`, { password }),
  signOutUser: (id: string) => del<unknown>(`/api/admin/users/${q(id)}/sessions`),
  adminSettings: () => get<AdminSettings>("/api/admin/settings"),
  saveSettings: (settings: ServerSettings, oidcClientSecret?: string) => put<AdminSettings>("/api/admin/settings", { settings, oidcClientSecret }),
  testOidc: (oidc: OidcSettings) => post<{ ok: boolean; message: string }>("/api/admin/oidc/test", oidc),
};
