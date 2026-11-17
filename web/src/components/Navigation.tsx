import { useState } from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import {
  ArrowUpDown, BellRing, Bot, Clock, Ellipsis, FileText, GitCompareArrows, Heart, Inbox, LogOut, Settings as SettingsIcon, Shield, Sun, TrendingUp, Users, Watch,
} from "lucide-react";
import { useModules } from "../App";
import { api } from "../lib/api";
import { useAlertBadge } from "../lib/alertBadge";
import { NAMES, TABS, compareOffered, tabOf, type Tab } from "../lib/nav";
import { useScreenLayout } from "../lib/layout";
import { session, useSession } from "../lib/session";
import { LAYOUT_NAV, arrange, type Module } from "../lib/types";
import { startViewing, useViewing } from "../lib/viewing";
import { useAiStatus } from "../pages/Assistant";
import { ArrangePanel } from "./Arrange";
import { FEATURES } from "./features";
import { CountBadge, IconButton, useLoad } from "./ui";

/*
 * Getting around. On a wide screen, the sidebar: Today, Last 24 hours, Trends and Health alerts at
 * the top; Your health (the features, in the person's order); Your records; Devices; the assistant;
 * Settings at the bottom. On a phone, five tabs that never change: Today · 24 hours · Health ·
 * Trends · More (lib/nav.ts). The features' order and what's hidden are shared with the phone app's
 * Health tab (Layouts.NAV, saved under "nav"): only the features are arranged here, and other ids
 * the phone app saves there keep their places.
 */

/** A feature in the sidebar and on Health: its shared id (a Module name, FOOD for Food), page, name, line and icon. */
export interface FeatureItem {
  id: string;
  module: Module;
  to: string;
  label: string;
  about: string;
  icon: LucideIcon;
}

/** Every feature that's on (or shared, while viewing), in the default order. Autism & ADHD is a tab in Mind while both are on. */
export function useFeatureItems(): FeatureItem[] {
  const on = useModules();
  return FEATURES.filter((f) => on.includes(f.id) && f.route && !(f.id === "NEURO" && on.includes("MIND"))).map((f) => ({
    id: f.id === "NUTRITION" ? "FOOD" : f.id, module: f.id, to: f.route!, label: f.name, about: f.about, icon: f.icon,
  }));
}

/** The features in the person's order, without the ones they've hidden. */
export function useFeaturesShown(items: FeatureItem[]): FeatureItem[] {
  const layout = useScreenLayout(LAYOUT_NAV);
  const hidden = new Set(layout?.hidden ?? []);
  return arrange(items, (i) => i.id, layout).filter((i) => !hidden.has(i.id));
}

/** Arranging the features (the sidebar's Your health, and Health on a phone). */
export function FeatureArrange({ items, onClose }: { items: FeatureItem[]; onClose: () => void }) {
  return (
    <ArrangePanel screen={LAYOUT_NAV} title="your features" items={items.map((i) => ({ id: i.id, label: i.label }))}
      intro="Put your features in the order you use them, and hide the ones you don't need here. A hidden feature stays on: it's still in What you're tracking, and the phone app follows the same order."
      onClose={onClose} />
  );
}

/** Whether to offer Compare devices: two or more devices with readings. Asked once per person. */
export function useCompareOffered(): boolean {
  const user = useSession((s) => s.user?.id);
  const viewing = useViewing((s) => !!s.share);
  const { data } = useLoad(() => (viewing || !user ? Promise.resolve(null) : api.devices().catch(() => null)), [user, viewing]);
  return compareOffered(data);
}

function Item({ to, icon: Icon, label, end, badge }: { to: string; icon: LucideIcon; label: string; end?: boolean; badge?: number }) {
  return (
    <NavLink to={to} end={end} className="nav-link">
      <Icon size={20} aria-hidden="true" />
      <span className="nav-label">{label}</span>
      {badge != null && <CountBadge count={badge} />}
    </NavLink>
  );
}

function Group({ title, action, children }: { title?: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="nav-group" role="group" aria-label={title}>
      {title && <div className="nav-heading"><span>{title}</span>{action}</div>}
      {children}
    </div>
  );
}

/** The sidebar, on a wide screen. */
export function Sidebar({ viewSwitcher, pronouns }: { viewSwitcher: React.ReactNode; pronouns?: string }) {
  const user = useSession((s) => s.user)!;
  const viewing = useViewing((s) => s.share);
  const ai = useAiStatus();
  const alerts = useAlertBadge();
  const compare = useCompareOffered();
  const items = useFeatureItems();
  const features = useFeaturesShown(items);
  const [arranging, setArranging] = useState(false);
  return (
    <nav className="sidebar" aria-label="Main">
      <div className="brand">
        <img src="/icon.svg" alt="" width={32} height={32} />
        <span>SuperAwesome<wbr />MagicalHealth</span>
      </div>
      <Group>
        <Item to="/" end icon={Sun} label={NAMES.today} />
        {!viewing && <Item to="/recent" icon={Clock} label={NAMES.recent} />}
        <Item to="/trends" icon={TrendingUp} label={NAMES.trends} />
        {!viewing && <Item to="/alerts" icon={BellRing} label={NAMES.alerts} badge={alerts} />}
      </Group>
      {(features.length > 0 || !viewing) && (
        <Group title="Your health" action={!viewing && (
          <button type="button" className="icon-btn nav-arrange" aria-label="Arrange your features" title="Arrange your features" aria-haspopup="dialog" onClick={() => setArranging(true)}>
            <ArrowUpDown size={16} />
          </button>
        )}>
          {features.map((f) => <Item key={f.id} to={f.to} icon={f.icon} label={f.label} />)}
        </Group>
      )}
      {!viewing && (
        <>
          <Group title="Your records">
            <Item to="/digest" icon={Inbox} label={NAMES.digest} />
            <Item to="/reports" icon={FileText} label={NAMES.reports} />
          </Group>
          <Group title={NAMES.devices}>
            <Item to="/devices" end icon={Watch} label={NAMES.devices} />
            {compare && <Item to="/devices/compare" icon={GitCompareArrows} label={NAMES.compare} />}
          </Group>
          {ai?.available && <Group><Item to="/assistant" icon={Bot} label={NAMES.assistant} /></Group>}
        </>
      )}
      <div className="nav-spacer" />
      {!viewing && (
        <Group>
          <Item to="/settings" icon={SettingsIcon} label={NAMES.settings} />
          {user.role === "ADMIN" && <Item to="/admin" icon={Shield} label="Admin" />}
        </Group>
      )}
      {viewSwitcher}
      <div className="sidebar-user">
        <div className="sidebar-who">
          <span className="sidebar-name">{user.displayName}</span>
          {pronouns && <span className="pronouns">{pronouns}</span>}
        </div>
        <IconButton label="Sign out" onClick={() => void session.signOut()}><LogOut size={18} /></IconButton>
      </div>
      {arranging && <FeatureArrange items={items} onClose={() => setArranging(false)} />}
    </nav>
  );
}

const TAB_ICONS: Record<Tab, LucideIcon> = { today: Sun, recent: Clock, health: Heart, trends: TrendingUp, more: Ellipsis };

/** The phone's five tabs (four while viewing someone's share, with a way back to your own). */
export function TabBar() {
  const { pathname } = useLocation();
  const viewing = useViewing((s) => s.share);
  const alerts = useAlertBadge();
  const here = tabOf(pathname);
  const tabs = viewing ? TABS.filter((t) => t.id !== "recent" && t.id !== "more") : TABS;
  return (
    <nav className="tabbar" aria-label="Tabs">
      {tabs.map((t) => {
        const Icon = TAB_ICONS[t.id];
        const on = here === t.id;
        return (
          <Link key={t.id} to={t.to} className={`tab ${on ? "is-on" : ""}`} aria-current={on ? "page" : undefined}>
            <span className="tab-icon">
              <Icon size={22} aria-hidden="true" />
              {t.id === "more" && <CountBadge count={alerts} className="tab-badge" />}
            </span>
            <span className="tab-label">{t.label}</span>
          </Link>
        );
      })}
      {viewing && (
        <button type="button" className="tab" onClick={() => startViewing(null)}>
          <span className="tab-icon"><Users size={22} aria-hidden="true" /></span>
          <span className="tab-label">Mine</span>
        </button>
      )}
    </nav>
  );
}
