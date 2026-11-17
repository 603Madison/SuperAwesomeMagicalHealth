import { Link, useLocation } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { Activity, Heart, LogOut, Plus, Sun, UserRound, Users } from "lucide-react";
import { api } from "../lib/api";
import { NAMES, TABS, compareOffered, tabOf, type Tab } from "../lib/nav";
import { session, useSession } from "../lib/session";
import { startViewing, useViewing } from "../lib/viewing";
import { openLog } from "./LogSheet";
import { IconButton, useLoad } from "./ui";

/*
 * Getting around. On a phone, the tabs along the bottom: Today · Body · + · Care · You, the + a
 * raised round button that opens the log sheet (components/LogSheet.tsx). On a wide screen, a calm
 * sidebar with the same four and Log at the top. Every other page belongs to one of them (lib/nav.ts),
 * which stays lit while it's open. While viewing someone's share: no +, and no You, but a way back.
 */

const ICONS: Record<Tab, LucideIcon> = { today: Sun, body: Activity, care: Heart, you: UserRound };

/** Whether to offer Compare devices: two or more devices with readings. Asked once per person. */
export function useCompareOffered(): boolean {
  const user = useSession((s) => s.user?.id);
  const viewing = useViewing((s) => !!s.share);
  const { data } = useLoad(() => (viewing || !user ? Promise.resolve(null) : api.devices().catch(() => null)), [user, viewing]);
  return compareOffered(data);
}

/** The tabs to show: all four for one's own, and no You while viewing someone's share. */
function useTabs() {
  const viewing = useViewing((s) => !!s.share);
  return viewing ? TABS.filter((t) => t.id !== "you") : TABS;
}

/** The sidebar, on a wide screen. */
export function Sidebar({ viewSwitcher, pronouns }: { viewSwitcher: React.ReactNode; pronouns?: string }) {
  const user = useSession((s) => s.user)!;
  const viewing = useViewing((s) => s.share);
  const { pathname } = useLocation();
  const here = tabOf(pathname);
  const tabs = useTabs();
  return (
    <nav className="sidebar" aria-label="Main">
      <div className="brand">
        <img src="/icon.svg" alt="" width={32} height={32} />
        <span>SuperAwesome<wbr />MagicalHealth</span>
      </div>
      {!viewing && (
        <button type="button" className="btn btn-primary sidebar-log" onClick={openLog} aria-haspopup="dialog">
          <Plus size={20} aria-hidden="true" /> {NAMES.log}
        </button>
      )}
      <div className="nav-group">
        {tabs.map((t) => {
          const Icon = ICONS[t.id];
          const on = here === t.id;
          return (
            <Link key={t.id} to={t.to} className={`nav-link ${on ? "active" : ""}`} aria-current={on ? "page" : undefined}>
              <Icon size={20} aria-hidden="true" />
              <span className="nav-label">{t.label}</span>
            </Link>
          );
        })}
      </div>
      <div className="nav-spacer" />
      {viewSwitcher}
      <div className="sidebar-user">
        <div className="sidebar-who">
          <span className="sidebar-name">{user.displayName}</span>
          {pronouns && <span className="pronouns">{pronouns}</span>}
        </div>
        <IconButton label="Sign out" onClick={() => void session.signOut()}><LogOut size={18} /></IconButton>
      </div>
    </nav>
  );
}

/** The phone's tabs, with the + in the middle (and, while viewing someone's share, a way back to your own). */
export function TabBar() {
  const { pathname } = useLocation();
  const viewing = useViewing((s) => s.share);
  const here = tabOf(pathname);
  const tabs = useTabs();
  const tab = (t: (typeof TABS)[number]) => {
    const Icon = ICONS[t.id];
    const on = here === t.id;
    return (
      <Link key={t.id} to={t.to} className={`tab ${on ? "is-on" : ""}`} aria-current={on ? "page" : undefined}>
        <span className="tab-icon"><Icon size={24} aria-hidden="true" /></span>
        <span className="tab-label">{t.label}</span>
      </Link>
    );
  };
  return (
    <nav className="tabbar" aria-label="Tabs">
      {tabs.slice(0, 2).map(tab)}
      {!viewing && (
        <button type="button" className="tab tab-log" onClick={openLog} aria-label="Log something" aria-haspopup="dialog">
          <span className="tab-log-btn"><Plus size={26} strokeWidth={2.2} aria-hidden="true" /></span>
        </button>
      )}
      {tabs.slice(2).map(tab)}
      {viewing && (
        <button type="button" className="tab" onClick={() => startViewing(null)}>
          <span className="tab-icon"><Users size={24} aria-hidden="true" /></span>
          <span className="tab-label">Mine</span>
        </button>
      )}
    </nav>
  );
}
