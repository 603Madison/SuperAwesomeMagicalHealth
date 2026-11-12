import type {
  AdminSettings, AuthConfig, CheckIn, DaySummary, Device, Food, FoodDay, FoodEntry, FoodSearch, Meal, Metric, Nutrients,
  OidcSettings, Point, Profile, Sample, ServerSettings, SessionInfo, SleepSummary, Trends, User, WaterEntry, Workout,
  ContinenceEntry, ContinenceSummary, CrisisResource, CycleDay, CycleOverview, DoseLog, JournalEntry, LabResult, Medication, MedsDay,
  MindSummary, MindfulnessSession, Module, MoodEntry, ScreenerDefinition, ScreenerResult,
} from "./types";

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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  // Every change carries this header, which other sites' pages can't send.
  if (method !== "GET") headers["X-SAMH"] = "1";
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
