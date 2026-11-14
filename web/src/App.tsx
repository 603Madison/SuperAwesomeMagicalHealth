import { useEffect, useState, type ReactNode } from "react";
import { safeReturn } from "./lib/paths";
import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { ArrowUpDown, Bot, ClipboardList, Eye, FileText, LogOut, Settings as SettingsIcon, Shield, Sun, TrendingUp, Users, Watch } from "lucide-react";
import { LAYOUT_NAV, arrange } from "./lib/types";
import { useScreenLayout } from "./lib/layout";
import { ArrangePanel } from "./components/Arrange";
import { Conditions } from "./pages/Conditions";
import { ContinenceSettingsPage } from "./pages/Continence";
import { CycleSettingsPage } from "./pages/Cycle";
import { Supplies } from "./pages/Supplies";
import { CalmMode, Neuro } from "./pages/Neuro";
import { Transition } from "./pages/Transition";
import { VoicePage } from "./pages/Voice";
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

/** The sidebar's groups, top to bottom; Settings and Admin sit at the very bottom. */
const NAV_GROUPS = ["Today & Trends", "Your health", "Records"] as const;

/** One link in the sidebar: its shared id (a Module or NavPage name), where it goes, and its group. */
export interface NavItem {
  id: string;
  group: (typeof NAV_GROUPS)[number];
  to: string;
  /** In the sidebar (short); [label] in Arrange and on phones' Settings. */
  short: string;
  label: string;
  icon: LucideIcon;
  /** One of the phone's tabs along the bottom. */
  tab?: boolean;
}

/**
 * The links the sidebar can have (Today aside, always first), in the default order: Trends and
 * the assistant; the features that are on, Food first; then Reports, Devices and What you're
 * tracking. Ids are shared with the phone app's Health tab (Layouts.NAV).
 */
export function useNavItems(): NavItem[] {
  const viewing = useViewing((s) => s.share);
  const on = useModules();
  const ai = useAiStatus();
  // Autism & ADHD lives inside Mind when both are on (a tab there), and on its own otherwise.
  const features = FEATURES.filter((f) => on.includes(f.id) && f.route && !(f.id === "NEURO" && on.includes("MIND")));
  return [
    { id: "TRENDS", group: "Today & Trends", to: "/trends", short: "Trends", label: "Trends", icon: TrendingUp, tab: true },
    ...(!viewing && ai?.available ? [{ id: "ASSISTANT", group: "Today & Trends" as const, to: "/assistant", short: "Assistant", label: "Assistant", icon: Bot }] : []),
    ...features.map((f) => ({
      id: f.id === "NUTRITION" ? "FOOD" : f.id, group: "Your health" as const, to: f.route!, icon: f.icon, label: f.name, tab: f.id === "NUTRITION" || f.id === "MIND",
      short: f.id === "VITALS" ? "BP & glucose" : f.id === "SYMPTOMS" ? "Symptoms" : f.id === "PHOTOS" ? "Photos" : f.name,
    })),
    ...(!viewing ? [
      { id: "REPORTS", group: "Records" as const, to: "/reports", short: "Reports", label: "Reports", icon: FileText },
      { id: "DEVICES", group: "Records" as const, to: "/devices", short: "Devices", label: "Devices", icon: Watch },
      { id: "CONDITIONS", group: "Records" as const, to: "/conditions", short: "What you're tracking", label: "What you're tracking", icon: ClipboardList },
    ] : []),
  ];
}

/** The sidebar's links in the person's order, without the ones they've hidden. */
export function useNavShown(items: NavItem[]): NavItem[] {
  const layout = useScreenLayout(LAYOUT_NAV);
  const hidden = new Set(layout?.hidden ?? []);
  return arrange(items, (i) => i.id, layout).filter((i) => !hidden.has(i.id));
}

function Shell() {
  const user = useSession((s) => s.user)!;
  const viewing = useViewing((s) => s.share);
  const on = useModules();
  const pronouns = usePronouns(on.includes("TRANSITION") && !viewing);
  const items = useNavItems();
  const shown = useNavShown(items);
  const [arranging, setArranging] = useState(false);
  // Which fields show is the shown person's: lib/fields.ts loads them afresh when that changes.
  const link = (i: NavItem) => (
    <NavLink key={i.id} to={i.to} className={`nav-link ${i.tab ? `nav-tab nav-${i.id.toLowerCase()}` : "nav-optional"}`} title={i.short === i.label ? undefined : i.label}>
      <i.icon size={20} /> <span>{i.short}</span>
    </NavLink>
  );
  return (
    <div className={`shell ${viewing ? "is-viewing" : ""}`}>
      <nav className="sidebar" aria-label="Main">
        <div className="brand">
          <img src="/icon.svg" alt="" width={32} height={32} />
          <span>SuperAwesome<wbr />MagicalHealth</span>
        </div>
        {NAV_GROUPS.map((g) => {
          const list = shown.filter((i) => i.group === g);
          if (g !== "Today & Trends" && list.length === 0 && !(g === "Your health" && !viewing)) return null;
          return (
            <div key={g} className="nav-group" role="group" aria-label={g}>
              <div className="nav-heading">
                <span>{g}</span>
                {g === "Your health" && !viewing && (
                  <button type="button" className="icon-btn nav-arrange" aria-label="Arrange the sidebar" title="Arrange the sidebar" aria-haspopup="dialog" onClick={() => setArranging(true)}>
                    <ArrowUpDown size={16} />
                  </button>
                )}
              </div>
              {g === "Today & Trends" && <NavLink to="/" end className="nav-link nav-tab nav-today"><Sun size={20} /> <span>Today</span></NavLink>}
              {list.map(link)}
            </div>
          );
        })}
        <div className="nav-spacer" />
        <div className="nav-group nav-bottom">
          {!viewing && <NavLink to="/settings" className="nav-link nav-tab nav-settings"><SettingsIcon size={20} /> <span>Settings</span></NavLink>}
          {!viewing && user.role === "ADMIN" && <NavLink to="/admin" className="nav-link nav-optional"><Shield size={20} /> <span>Admin</span></NavLink>}
          {viewing && <NavLink to="/back" className="nav-link phone-tab-only nav-tab nav-settings" onClick={(e) => { e.preventDefault(); startViewing(null); }}><Users size={20} /> <span>Mine</span></NavLink>}
        </div>
        <ViewSwitcher />
        <div className="sidebar-user">
          <div className="sidebar-who">
            <span className="sidebar-name">{user.displayName}</span>
            {pronouns && <span className="pronouns">{pronouns}</span>}
          </div>
          <IconButton label="Sign out" onClick={() => void session.signOut()}><LogOut size={18} /></IconButton>
        </div>
      </nav>
      {arranging && (
        <ArrangePanel screen={LAYOUT_NAV} title="the sidebar" items={items.map((i) => ({ id: i.id, label: i.label, group: i.group }))} groups={[...NAV_GROUPS]}
          intro="Move your features into the order you use them, and hide what you don't need here. Hidden pages are still in Settings › Features."
          onClose={() => setArranging(false)} />
      )}

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
          <Route path="/transition/voice" element={<Shared id="TRANSITION"><VoicePage /></Shared>} />
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
