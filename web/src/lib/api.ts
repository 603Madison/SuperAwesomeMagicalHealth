import type {
  AdminSettings, AuthConfig, CheckIn, DaySummary, Device, Food, FoodDay, FoodEntry, FoodSearch, Meal, Metric, Nutrients,
  OidcSettings, Point, Profile, Sample, ServerSettings, SessionInfo, SleepSummary, Trends, User, WaterEntry, Workout,
  ContinenceEntry, ContinenceSummary, CrisisResource, CycleDay, CycleOverview, DoseLog, JournalEntry, LabResult, Medication, MedsDay,
  MindSummary, MindfulnessSession, Module, MoodEntry, ScreenerDefinition, ScreenerResult,
  Appointment, BloodPressure, Digest, DoseChange, Drink, DrinksDay, DrinksSummary, Contraction, Feed, GlucoseReading, Habit,
  HabitStatus, HabitTick, HormoneOverview, HormoneSettings, HotFlash, IntegrationKey, KickSession, LifeStageInfo,
  LifeStageOverview, Migraine, Place, PushKey, PushMessage, PushSubscriptionInfo, ReportSectionKey, Shares, Share,
  SmtpSettings, SuggestedQuestion, SymptomEntry, SymptomsSummary, VitalsSummary, Webhook, WebhookDelivery,
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
  "/api/habits", "/api/hormones", "/api/appointments",
];

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  // Every change carries this header, which other sites' pages can't send.
  if (method !== "GET") headers["X-SAMH"] = "1";
  // Viewing someone's shared data: reads of their data say whose, and nothing of theirs can change.
  const owner = viewingOwner();
  if (owner && SHARED_PATHS.some((p) => path.startsWith(p))) {
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
  logFood: (entry: { date: string; meal: Meal; servings: number; food?: Food; name?: string; nutrients?: Nutrients }) => post<FoodEntry>("/api/food/entries", entry),
  changeEntry: (id: string, change: { meal?: Meal; servings?: number; date?: string }) => patch<FoodEntry>(`/api/food/entries/${q(id)}`, change),
  deleteEntry: (id: string) => del<unknown>(`/api/food/entries/${q(id)}`),
  copyMeal: (from: string, to: string, meal?: Meal) => post<{ copied: number }>("/api/food/copy", { from, to, meal }),
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
