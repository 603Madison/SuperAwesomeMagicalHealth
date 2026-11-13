import { useEffect, useRef, type ReactNode } from "react";
import { loadFields } from "./lib/fields";
import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { Bot, Eye, FileText, LogOut, Settings as SettingsIcon, Shield, Sun, TrendingUp, Users, Watch } from "lucide-react";
import { Conditions } from "./pages/Conditions";
import { ContinenceSettingsPage } from "./pages/Continence";
import { CycleSettingsPage } from "./pages/Cycle";
import { Supplies } from "./pages/Supplies";
import { CalmMode, Neuro } from "./pages/Neuro";
import { Transition } from "./pages/Transition";
import { Photos } from "./pages/Photos";
import { Assistant, AssistantSent, useAiStatus } from "./pages/Assistant";
import type { Module } from "./lib/types";
import { Mind } from "./pages/Mind";
import { Cycle } from "./pages/Cycle";
import { Continence } from "./pages/Continence";
import { Meds } from "./pages/Meds";
import { session, useSession } from "./lib/session";
import { startViewing, useViewing } from "./lib/viewing";
import { api } from "./lib/api";
import { Button, IconButton, Menu, Spinner, Toasts, useLoad } from "./components/ui";
import { FEATURES } from "./components/features";
import { Login } from "./pages/Login";
import { ConnectApp } from "./pages/ConnectApp";
import { WatchSetup } from "./pages/WatchSetup";
import { Today } from "./pages/Today";
import { Sleep } from "./pages/Sleep";
import { Activity } from "./pages/Activity";
import { Food } from "./pages/Food";
import { Body } from "./pages/Body";
import { Trends } from "./pages/Trends";
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
  if (location.pathname === "/login") return user ? <Navigate to={new URLSearchParams(location.search).get("return") ?? "/"} replace /> : <Login />;
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

/** Shown on phones as tabs (the rest are a tap away in Settings). */
const PHONE_TABS: Module[] = ["NUTRITION", "MIND"];

function Shell() {
  const user = useSession((s) => s.user)!;
  const viewing = useViewing((s) => s.share);
  const on = useModules();
  // Autism & ADHD lives inside Mind when both are on (a tab there), and on its own otherwise.
  const features = FEATURES.filter((f) => on.includes(f.id) && f.route && !(f.id === "NEURO" && on.includes("MIND")));
  const ai = useAiStatus();
  const pronouns = usePronouns(on.includes("TRANSITION") && !viewing);
  // Which fields show is the shown person's: reload it when that changes.
  const owner = viewing?.ownerId;
  const firstOwner = useRef(owner);
  useEffect(() => {
    if (firstOwner.current === owner) return;
    firstOwner.current = owner;
    void loadFields(true);
  }, [owner]);
  return (
    <div className={`shell ${viewing ? "is-viewing" : ""}`}>
      <nav className="sidebar" aria-label="Main">
        <div className="brand">
          <img src="/icon.svg" alt="" width={32} height={32} />
          <span>SuperAwesome<wbr />MagicalHealth</span>
        </div>
        <NavLink to="/" end className="nav-link"><Sun size={20} /> <span>Today</span></NavLink>
        {features.map((f) => (
          <NavLink key={f.id} to={f.route!} className={`nav-link ${PHONE_TABS.includes(f.id) ? "" : "nav-optional"}`}>
            <f.icon size={20} /> <span>{f.id === "CONTINENCE" ? <>Bladder &amp; bowel</> : f.id === "VITALS" ? "BP & glucose" : f.name}</span>
          </NavLink>
        ))}
        <NavLink to="/trends" className="nav-link"><TrendingUp size={20} /> <span>Trends</span></NavLink>
        {!viewing && ai?.available && <NavLink to="/assistant" className="nav-link nav-optional"><Bot size={20} /> <span>Assistant</span></NavLink>}
        {!viewing && <NavLink to="/reports" className="nav-link nav-optional"><FileText size={20} /> <span>Reports</span></NavLink>}
        {!viewing && <NavLink to="/devices" className="nav-link nav-optional"><Watch size={20} /> <span>Devices</span></NavLink>}
        {!viewing && <NavLink to="/settings" className="nav-link"><SettingsIcon size={20} /> <span>Settings</span></NavLink>}
        {!viewing && user.role === "ADMIN" && <NavLink to="/admin" className="nav-link nav-optional"><Shield size={20} /> <span>Admin</span></NavLink>}
        {viewing && <NavLink to="/back" className="nav-link phone-tab-only" onClick={(e) => { e.preventDefault(); startViewing(null); }}><Users size={20} /> <span>Mine</span></NavLink>}
        <div className="nav-spacer" />
        <ViewSwitcher />
        <div className="sidebar-user">
          <span>{user.displayName}{pronouns && <span className="pronouns"> ({pronouns})</span>}</span>
          <IconButton label="Sign out" onClick={() => { startViewing(null); void session.signOut(); }}><LogOut size={18} /></IconButton>
        </div>
      </nav>

      <main className="content">
        {viewing && <ViewingBanner />}
        {/* A new key when whose data shows changes, so every page loads afresh for that person. */}
        <Routes key={viewing?.ownerId ?? "mine"}>
          <Route path="/" element={<Today />} />
          <Route path="/trends" element={<Trends />} />
          {/* Every feature's page is always reachable by its link (the profile may still be loading);
              while viewing someone's data, only the ones they shared. */}
          <Route path="/sleep" element={<Shared id="SLEEP"><Sleep /></Shared>} />
          <Route path="/activity" element={<Shared id="ACTIVITY"><Activity /></Shared>} />
          <Route path="/food" element={<Shared id="NUTRITION"><Food /></Shared>} />
          <Route path="/body" element={<Shared id="BODY"><Body /></Shared>} />
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
          <Route path="/photos" element={<Shared id="PHOTOS"><Photos /></Shared>} />
          <Route path="/supplies" element={<SharedAny ids={["CONTINENCE", "CYCLE"]}><Supplies /></SharedAny>} />
          {!viewing && <Route path="/bladder/settings" element={<ContinenceSettingsPage />} />}
          {!viewing && <Route path="/cycle/settings" element={<CycleSettingsPage />} />}
          {!viewing && <Route path="/conditions" element={<Conditions />} />}
          {!viewing && <Route path="/assistant" element={<Assistant />} />}
          {!viewing && <Route path="/assistant/sent" element={<AssistantSent />} />}
          {!viewing && <Route path="/reports" element={<Reports />} />}
          {!viewing && <Route path="/digest" element={<DigestPage />} />}
          {!viewing && <Route path="/devices" element={<Devices />} />}
          {!viewing && <Route path="/settings" element={<Settings />} />}
          {!viewing && user.role === "ADMIN" && <Route path="/admin" element={<Admin />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
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
