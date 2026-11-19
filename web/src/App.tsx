import { useEffect, type ReactNode } from "react";
import { useToday } from "./lib/format";
import { safeReturn } from "./lib/paths";
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { Eye, Users } from "lucide-react";
import { useAlertBadgeUpdates } from "./lib/alertBadge";
import { Sidebar, TabBar } from "./components/Navigation";
import { Conditions } from "./pages/Conditions";
import { ContinenceSettingsPage } from "./pages/Continence";
import { CycleSettingsPage } from "./pages/Cycle";
import { Supplies } from "./pages/Supplies";
import { CalmMode, Neuro } from "./pages/Neuro";
import { Transition } from "./pages/Transition";
import { VoicePage } from "./pages/Voice";
import { Photos } from "./pages/Photos";
import { Assistant, AssistantSent } from "./pages/Assistant";
import type { Module } from "./lib/types";
import { Mind } from "./pages/Mind";
import { Cycle } from "./pages/Cycle";
import { Continence } from "./pages/Continence";
import { Meds } from "./pages/Meds";
import { session, useSession } from "./lib/session";
import { startViewing, useViewing } from "./lib/viewing";
import { api } from "./lib/api";
import { Button, Menu, Spinner, Toasts, useLoad } from "./components/ui";
import { Login } from "./pages/Login";
import { ConnectApp } from "./pages/ConnectApp";
import { WatchSetup } from "./pages/WatchSetup";
import { Today } from "./pages/Today";
import { Food } from "./pages/Food";
import { Shopping } from "./pages/Shopping";
import { Body } from "./pages/Body";
import { Weight } from "./pages/Weight";
import { Care } from "./pages/Care";
import { You } from "./pages/You";
import { Devices } from "./pages/Devices";
import { Settings } from "./pages/Settings";
import { Admin } from "./pages/Admin";
import { Vitals } from "./pages/Vitals";
import { Habits } from "./pages/Habits";
import { Symptoms } from "./pages/Symptoms";
import { Hormones } from "./pages/Hormones";
import { Appointments } from "./pages/Appointments";
import { Reports } from "./pages/Reports";
import { DigestPage } from "./pages/Digest";
import { Compare } from "./pages/Compare";
import { Alerts } from "./pages/Alerts";
import { Records } from "./pages/Records";
import { LogHost } from "./components/LogSheet";
import { MOVED, movedTo } from "./lib/nav";

export function App() {
  useEffect(() => { void session.start(); }, []);
  return (
    <BrowserRouter>
      <Root />
      <Toasts />
    </BrowserRouter>
  );
}

function Root() {
  const loading = useSession((s) => s.loading);
  const user = useSession((s) => s.user);
  const location = useLocation();
  const here = `${location.pathname}${location.search}`;

  if (loading) return <div className="splash"><Spinner size={32} /></div>;
  if (location.pathname === "/login") return user ? <Navigate to={safeReturn(new URLSearchParams(location.search).get("return"))} replace /> : <Login />;
  if (!user) return <Navigate to={`/login?return=${encodeURIComponent(here)}`} replace />;
  if (location.pathname === "/connect-app") return <ConnectApp />;
  if (location.pathname === "/watch-setup") return <WatchSetup />;
  // Calm mode fills the screen, with nothing else around it.
  if (location.pathname === "/calm") return <CalmMode />;
  return <Shell />;
}

/**
 * The features to show: this person's own (the defaults until their profile has loaded), or,
 * while viewing someone's shared data, the ones they shared.
 */
export function useModules(): Module[] {
  const shared = useViewing((s) => s.share?.modules);
  // One array for the defaults: a new one each time would look like a change on every render.
  const own = useSession((s) => s.profile?.modules ?? DEFAULT_MODULES);
  return shared ?? own;
}

const DEFAULT_MODULES: Module[] = ["ACTIVITY", "SLEEP", "NUTRITION", "BODY"];

/**
 * Around every page: the sidebar on a wide screen, the tabs along the bottom on a phone
 * (components/Navigation.tsx, lib/nav.ts), and the + sheet (components/LogSheet.tsx).
 */
function Shell() {
  const user = useSession((s) => s.user)!;
  const viewing = useViewing((s) => s.share);
  const on = useModules();
  const pronouns = usePronouns(on.includes("TRANSITION") && !viewing);
  // A new day (at midnight, or when the tab comes back) draws every page again, so "today" moves on everywhere.
  useToday();
  // The unseen health alerts, for the badges.
  useAlertBadgeUpdates();
  // Which fields show is the shown person's: lib/fields.ts loads them afresh when that changes.
  return (
    <div className={`shell ${viewing ? "is-viewing" : ""}`}>
      <Sidebar viewSwitcher={<ViewSwitcher />} pronouns={pronouns} />
      <main className="content">
        {viewing && <ViewingBanner />}
        {/* A new key when whose data shows changes, so every page loads afresh for that person. */}
        <Routes key={viewing?.ownerId ?? "mine"}>
          <Route path="/" element={<Today />} />
          {/* What the devices measured: 24 hours (only ever the person's own), a week, a month or a year. */}
          <Route path="/body" element={<Body />} />
          <Route path="/body/weight" element={<Shared id="BODY"><Weight /></Shared>} />
          {/* What the person looks after: a card per area, each opening its page. */}
          <Route path="/care" element={<Care />} />
          {/* Every feature's page is always reachable by its link (the profile may still be loading);
              while viewing someone's data, only the ones they shared. */}
          <Route path="/food" element={<Shared id="NUTRITION"><Food /></Shared>} />
          <Route path="/mind" element={<Shared id="MIND"><Mind /></Shared>} />
          <Route path="/cycle" element={<Shared id="CYCLE"><Cycle /></Shared>} />
          <Route path="/bladder" element={<Shared id="CONTINENCE"><Continence /></Shared>} />
          <Route path="/meds" element={<Shared id="MEDICATIONS"><Meds /></Shared>} />
          <Route path="/vitals" element={<Shared id="VITALS"><Vitals /></Shared>} />
          <Route path="/habits" element={<Shared id="HABITS"><Habits /></Shared>} />
          <Route path="/symptoms" element={<Shared id="SYMPTOMS"><Symptoms /></Shared>} />
          <Route path="/hormones" element={<Shared id="HORMONES"><Hormones /></Shared>} />
          <Route path="/appointments" element={<Shared id="APPOINTMENTS"><Appointments /></Shared>} />
          <Route path="/neuro" element={<Shared id="NEURO"><Neuro /></Shared>} />
          <Route path="/transition" element={<Shared id="TRANSITION"><Transition /></Shared>} />
          <Route path="/transition/voice" element={<Shared id="TRANSITION"><VoicePage /></Shared>} />
          <Route path="/photos" element={<Shared id="PHOTOS"><Photos /></Shared>} />
          <Route path="/supplies" element={<SharedAny ids={["CONTINENCE", "CYCLE"]}><Supplies /></SharedAny>} />
          {!viewing && <Route path="/bladder/settings" element={<ContinenceSettingsPage />} />}
          {!viewing && <Route path="/cycle/settings" element={<CycleSettingsPage />} />}
          {/* Devices, records, where data goes and the settings: only ever the person's own. */}
          {!viewing && <Route path="/you" element={<You />} />}
          {!viewing && <Route path="/food/shopping" element={<Shopping />} />}
          {!viewing && <Route path="/conditions" element={<Conditions />} />}
          {!viewing && <Route path="/assistant" element={<Assistant />} />}
          {!viewing && <Route path="/assistant/sent" element={<AssistantSent />} />}
          {!viewing && <Route path="/reports" element={<Reports />} />}
          {!viewing && <Route path="/digest" element={<DigestPage />} />}
          {!viewing && <Route path="/devices" element={<Devices />} />}
          {!viewing && <Route path="/records" element={<Records />} />}
          {/* Health alerts: only ever the person's own (the server never gives them to a share). */}
          {!viewing && <Route path="/alerts" element={<Alerts />} />}
          {/* Two devices side by side: never someone's share either (the server says no to one). */}
          {!viewing && <Route path="/devices/compare" element={<Compare />} />}
          {!viewing && <Route path="/settings" element={<Settings />} />}
          {!viewing && user.role === "ADMIN" && <Route path="/admin" element={<Admin />} />}
          {/* The old addresses (Last 24 hours, Trends, Sleep, Activity, Health, More) lead to where they are now. */}
          {MOVED.map((path) => <Route key={path} path={path} element={<Moved />} />)}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <TabBar />
      <LogHost />
    </div>
  );
}

/** An old address: where it is now, keeping what it asked for (a device, a span). */
function Moved() {
  const location = useLocation();
  return <Navigate to={movedTo(location.pathname, location.search) ?? "/"} replace />;
}

/** A feature's page, unless someone else's data is showing and they didn't share that feature. */
function Shared({ id, children }: { id: Module; children: ReactNode }) {
  const viewing = useViewing((s) => s.share);
  if (viewing && !viewing.modules.includes(id)) return <Navigate to="/" replace />;
  return <>{children}</>;
}

/** A page several features share (supplies: bladder and bowel, and periods): reachable if any was shared. */
function SharedAny({ ids, children }: { ids: Module[]; children: ReactNode }) {
  const viewing = useViewing((s) => s.share);
  if (viewing && !ids.some((id) => viewing.modules.includes(id))) return <Navigate to="/" replace />;
  return <>{children}</>;
}

/** The person's pronouns, from their transition profile, shown with their name. */
function usePronouns(enabled: boolean): string | undefined {
  const { data } = useLoad(() => (enabled ? api.transitionProfile().catch(() => null) : Promise.resolve(null)), [enabled]);
  return data?.pronouns ?? undefined;
}

/** "Viewing Alex": whose data is showing, from the people who shared theirs with this account. */
function ViewSwitcher() {
  const viewing = useViewing((s) => s.share);
  const user = useSession((s) => s.user)!;
  const navigate = useNavigate();
  const { data } = useLoad(() => api.shares().catch(() => ({ given: [], received: [] })), [user.id]);
  const received = data?.received ?? [];
  if (received.length === 0 && !viewing) return null;
  return (
    <div className="view-switcher">
      <Menu label="Whose health to show" buttonClass="view-switch-btn" trigger={<><Eye size={18} /><span>{viewing ? viewing.ownerName : "My health"}</span></>} items={[
        { label: "My health", icon: <Users size={18} />, onSelect: () => { startViewing(null); navigate("/"); } },
        ...received.map((s) => ({ label: s.ownerName, icon: <Eye size={18} />, onSelect: () => { startViewing(s); navigate("/"); } })),
      ]} />
    </div>
  );
}

function ViewingBanner() {
  const viewing = useViewing((s) => s.share)!;
  const navigate = useNavigate();
  return (
    <div className="viewing-banner" role="status">
      <Eye size={18} />
      <span><strong>{viewing.ownerName}'s health</strong>, shared with you. You can look; nothing here can be changed.</span>
      <Button variant="primary" onClick={() => { startViewing(null); navigate("/"); }}>Back to mine</Button>
    </div>
  );
}
