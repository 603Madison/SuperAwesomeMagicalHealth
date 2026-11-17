import { Link } from "react-router-dom";
import { ChevronRight, CircleDot, Eye, HeartPulse, Pencil, ShieldCheck, Smartphone, Watch, type LucideIcon } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import { NAMES, SETTINGS_PAGES } from "../lib/nav";
import { privacyLine } from "../lib/privacy";
import { useSession } from "../lib/session";
import type { Device, DeviceKind } from "../lib/types";
import { startViewing } from "../lib/viewing";
import { MenuGroup, MenuRow, PageHeader, useLoad } from "../components/ui";
import { useAiStatus } from "./Assistant";

/*
 * You: the person's devices (how each is doing), their records (Your week, a report for their
 * doctor, the assistant), where their data goes, and the settings in five plain groups, each a page
 * of its own (/settings#features and so on). Only ever the person's own.
 */

const DEVICE_ICONS: Partial<Record<DeviceKind, LucideIcon>> = {
  COLMI_RING: CircleDot, HEART_RATE_STRAP: HeartPulse, PHONE: Smartphone, HEALTH_CONNECT: Smartphone, MANUAL: Pencil,
};

/** How a device is doing, in one line: "Synced 4 min ago · battery 78%". */
function deviceLine(d: Device): string {
  const synced = d.lastSyncAt ? `Synced ${when(d.lastSyncAt)}` : `Added ${when(d.createdAt)}`;
  const strap = d.kind === "HEART_RATE_STRAP" ? "records from the phone app" : null;
  return [synced, d.battery != null && `battery ${d.battery}%`, strap].filter(Boolean).join(" · ");
}

export function You() {
  const user = useSession((s) => s.user)!;
  const ai = useAiStatus();
  const devices = useLoad(() => api.devices().catch(() => null), []);
  const digest = useLoad(() => api.latestDigest().catch(() => null), []);
  const privacy = useLoad(() => api.privacy().catch(() => null), []);
  const shares = useLoad(() => api.shares().catch(() => ({ given: [], received: [] })), [user.id]);
  const received = shares.data?.received ?? [];
  const list = (devices.data ?? []).filter((d) => d.kind !== "MANUAL").sort((a, b) => (b.lastSyncAt ?? 0) - (a.lastSyncAt ?? 0));
  const settings = SETTINGS_PAGES.filter((p) => p.id !== "privacy");

  return (
    <div className="page you-page">
      <PageHeader title={NAMES.you} />

      <section className="you-devices" aria-labelledby="you-devices">
        <h2 id="you-devices" className="you-card-title">Your devices</h2>
        {devices.data && list.length === 0 && <p className="muted">Nothing connected yet. Devices show here once they've sent data.</p>}
        {list.length > 0 && (
          <ul className="you-device-list">
            {list.slice(0, 4).map((d) => {
              const Icon = DEVICE_ICONS[d.kind] ?? Watch;
              return (
                <li key={d.id} className="you-device">
                  <span className="you-device-icon" aria-hidden="true"><Icon size={20} /></span>
                  <span className="you-device-text">
                    <span className="you-device-name">{d.name}</span>
                    <span className="you-device-sub">{deviceLine(d)}</span>
                  </span>
                </li>
              );
            })}
          </ul>
        )}
        <Link to="/devices" className="you-manage">Manage devices <ChevronRight size={16} aria-hidden="true" /></Link>
      </section>

      <MenuGroup>
        <MenuRow to="/digest" title={NAMES.digest} sub={digest.data?.headline ?? "Your week in health, against the week before"} />
        <MenuRow to="/reports" title={NAMES.reports} sub="PDF, CSV or FHIR, for the dates you choose" />
        {ai?.available && <MenuRow to="/assistant" title="Ask the assistant" sub="Questions about your own data" />}
      </MenuGroup>

      <Link to="/settings#privacy" className="you-privacy">
        <ShieldCheck size={24} aria-hidden="true" />
        <span className="you-privacy-text">
          <span className="you-privacy-title">{NAMES.privacy}</span>
          <span className="you-privacy-sub">{privacy.data ? privacyLine(privacy.data) : "Every way something can leave your server"}</span>
        </span>
        <ChevronRight size={20} aria-hidden="true" />
      </Link>

      <MenuGroup title={NAMES.settings}>
        {settings.map((p) => <MenuRow key={p.id} to={`/settings#${p.id}`} title={p.label} sub={p.sub} />)}
        {user.role === "ADMIN" && <MenuRow to="/admin" title="Admin" sub="Accounts and how people sign in" />}
      </MenuGroup>

      {received.length > 0 && (
        <MenuGroup title="Shared with you">
          {received.map((s) => (
            <MenuRow key={s.id} to="/" icon={<Eye size={20} />} title={`${s.ownerName}'s health`} sub="Look at what they share with you" onClick={() => startViewing(s)} />
          ))}
        </MenuGroup>
      )}
    </div>
  );
}
