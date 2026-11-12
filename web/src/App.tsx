import { useEffect } from "react";
import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation } from "react-router-dom";
import { Activity as ActivityIcon, Brain, CalendarHeart, Droplet, LogOut, Moon, Pill, Salad, Settings as SettingsIcon, Shield, Sun, TrendingUp, Watch, Weight } from "lucide-react";
import type { Module } from "./lib/types";
import { Mind } from "./pages/Mind";
import { Cycle } from "./pages/Cycle";
import { Continence } from "./pages/Continence";
import { Meds } from "./pages/Meds";
import { session, useSession } from "./lib/session";
import { IconButton, Spinner, Toasts } from "./components/ui";
import { Login } from "./pages/Login";
import { ConnectApp } from "./pages/ConnectApp";
import { Today } from "./pages/Today";
import { Sleep } from "./pages/Sleep";
import { Activity } from "./pages/Activity";
import { Food } from "./pages/Food";
import { Body } from "./pages/Body";
import { Trends } from "./pages/Trends";
import { Devices } from "./pages/Devices";
import { Settings } from "./pages/Settings";
import { Admin } from "./pages/Admin";

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
  return <Shell />;
}

/** The modules this person uses (the defaults until their profile has loaded). */
export function useModules(): Module[] {
  // One array for the defaults: a new one each time would look like a change on every render.
  return useSession((s) => s.profile?.modules ?? DEFAULT_MODULES);
}

const DEFAULT_MODULES: Module[] = ["ACTIVITY", "SLEEP", "NUTRITION", "BODY"];

function Shell() {
  const user = useSession((s) => s.user)!;
  const on = useModules();
  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Main">
        <div className="brand">
          <img src="/icon.svg" alt="" width={32} height={32} />
          <span>SuperAwesome<wbr />MagicalHealth</span>
        </div>
        <NavLink to="/" end className="nav-link"><Sun size={20} /> <span>Today</span></NavLink>
        {on.includes("SLEEP") && <NavLink to="/sleep" className="nav-link nav-optional"><Moon size={20} /> <span>Sleep</span></NavLink>}
        {on.includes("ACTIVITY") && <NavLink to="/activity" className="nav-link nav-optional"><ActivityIcon size={20} /> <span>Activity</span></NavLink>}
        {on.includes("NUTRITION") && <NavLink to="/food" className="nav-link"><Salad size={20} /> <span>Food</span></NavLink>}
        {on.includes("BODY") && <NavLink to="/body" className="nav-link nav-optional"><Weight size={20} /> <span>Body</span></NavLink>}
        {on.includes("MIND") && <NavLink to="/mind" className="nav-link"><Brain size={20} /> <span>Mind</span></NavLink>}
        {on.includes("CYCLE") && <NavLink to="/cycle" className="nav-link nav-optional"><CalendarHeart size={20} /> <span>Cycle</span></NavLink>}
        {on.includes("CONTINENCE") && <NavLink to="/bladder" className="nav-link nav-optional"><Droplet size={20} /> <span>Bladder &amp; bowel</span></NavLink>}
        {on.includes("MEDICATIONS") && <NavLink to="/meds" className="nav-link nav-optional"><Pill size={20} /> <span>Medications</span></NavLink>}
        <NavLink to="/trends" className="nav-link"><TrendingUp size={20} /> <span>Trends</span></NavLink>
        <NavLink to="/devices" className="nav-link nav-optional"><Watch size={20} /> <span>Devices</span></NavLink>
        <NavLink to="/settings" className="nav-link"><SettingsIcon size={20} /> <span>Settings</span></NavLink>
        {user.role === "ADMIN" && <NavLink to="/admin" className="nav-link nav-optional"><Shield size={20} /> <span>Admin</span></NavLink>}
        <div className="nav-spacer" />
        <div className="sidebar-user">
          <span>{user.displayName}</span>
          <IconButton label="Sign out" onClick={() => void session.signOut()}><LogOut size={18} /></IconButton>
        </div>
      </nav>

      <main className="content">
        <Routes>
          <Route path="/" element={<Today />} />
          <Route path="/sleep" element={<Sleep />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="/food" element={<Food />} />
          <Route path="/body" element={<Body />} />
          <Route path="/trends" element={<Trends />} />
          <Route path="/mind" element={<Mind />} />
          <Route path="/cycle" element={<Cycle />} />
          <Route path="/bladder" element={<Continence />} />
          <Route path="/meds" element={<Meds />} />
          <Route path="/devices" element={<Devices />} />
          <Route path="/settings" element={<Settings />} />
          {user.role === "ADMIN" && <Route path="/admin" element={<Admin />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
