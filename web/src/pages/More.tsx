import type { ReactNode } from "react";
import { Bell, BellRing, Bot, CircleUserRound, ClipboardList, Eye, FileText, GitCompareArrows, Inbox, Palette, Shield, ShieldCheck, UserRound, Watch } from "lucide-react";
import { api } from "../lib/api";
import { useAlertBadge } from "../lib/alertBadge";
import { NAMES, SETTINGS_PAGES, type SettingsPage } from "../lib/nav";
import { useSession } from "../lib/session";
import { startViewing } from "../lib/viewing";
import { useCompareOffered } from "../components/Navigation";
import { MenuGroup, MenuRow, PageHeader, useLoad } from "../components/ui";
import { useAiStatus } from "./Assistant";

/*
 * More: everything that isn't a tab, grouped, each one tap. Your records (Health alerts with what's
 * new, Your week, Reports & export), Devices, the assistant, health shared with you, and each
 * Settings page on its own. Only ever the person's own.
 */

const SETTINGS_ICONS: Record<SettingsPage, ReactNode> = {
  you: <UserRound size={20} />,
  features: <ClipboardList size={20} />,
  reminders: <Bell size={20} />,
  connections: <ShieldCheck size={20} />,
  appearance: <Palette size={20} />,
  account: <CircleUserRound size={20} />,
};

export function More() {
  const user = useSession((s) => s.user)!;
  const alerts = useAlertBadge();
  const compare = useCompareOffered();
  const ai = useAiStatus();
  const shares = useLoad(() => api.shares().catch(() => ({ given: [], received: [] })), [user.id]);
  const received = shares.data?.received ?? [];
  return (
    <div className="page menu-page">
      <PageHeader title={NAMES.more} subtitle="Your records, devices and settings" />
      <MenuGroup title="Your records">
        <MenuRow to="/alerts" icon={<BellRing size={20} />} title={NAMES.alerts} sub="A heads-up when your readings look out of the ordinary" badge={alerts} />
        <MenuRow to="/digest" icon={<Inbox size={20} />} title={NAMES.digest} sub="Your week in health, against the week before" />
        <MenuRow to="/reports" icon={<FileText size={20} />} title={NAMES.reports} sub="A summary for your doctor, or all your data to keep" />
      </MenuGroup>
      <MenuGroup title={NAMES.devices}>
        <MenuRow to="/devices" icon={<Watch size={20} />} title={NAMES.devices} sub="Where your data comes from, and how to bring in more" />
        {compare && <MenuRow to="/devices/compare" icon={<GitCompareArrows size={20} />} title={NAMES.compare} sub="How far two devices read from each other, night by night" />}
      </MenuGroup>
      {ai?.available && (
        <MenuGroup title={NAMES.assistant}>
          <MenuRow to="/assistant" icon={<Bot size={20} />} title={NAMES.assistant} sub="Ask about your own data" />
        </MenuGroup>
      )}
      {received.length > 0 && (
        <MenuGroup title="Shared with you">
          {received.map((s) => (
            <MenuRow key={s.id} to="/" icon={<Eye size={20} />} title={`${s.ownerName}'s health`} sub="Look at what they share with you" onClick={() => startViewing(s)} />
          ))}
        </MenuGroup>
      )}
      <MenuGroup title={NAMES.settings}>
        {SETTINGS_PAGES.map((p) => <MenuRow key={p.id} to={`/settings#${p.id}`} icon={SETTINGS_ICONS[p.id]} title={p.label} sub={p.sub} />)}
        {user.role === "ADMIN" && <MenuRow to="/admin" icon={<Shield size={20} />} title="Admin" sub="Accounts and how people sign in" />}
      </MenuGroup>
    </div>
  );
}
