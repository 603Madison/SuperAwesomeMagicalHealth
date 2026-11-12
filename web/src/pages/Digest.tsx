import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronLeft, ChevronRight, Inbox, Settings2, Sparkles } from "lucide-react";
import { api, ApiError } from "../lib/api";
import { parseDate, shiftDate } from "../lib/format";
import { useSession } from "../lib/session";
import type { Digest } from "../lib/types";
import { FEATURES } from "../components/features";
import { Button, Card, Empty, ErrorNotice, IconButton, PageHeader, Spinner, useLoad } from "../components/ui";

export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export function weekText(d: Digest): string {
  const a = parseDate(d.weekStart);
  const b = parseDate(d.weekEnd);
  const sameMonth = a.getMonth() === b.getMonth();
  return `${a.toLocaleDateString(undefined, { day: "numeric", month: sameMonth ? undefined : "short" })} – ${b.toLocaleDateString(undefined, { day: "numeric", month: "short" })}`;
}

/** The weekly digest: the week in health, against the week before. */
export function DigestPage() {
  const [week, setWeek] = useState<string | null>(null);
  const prefs = useSession((s) => s.profile?.digest);
  const navigate = useNavigate();
  const { data, error, loading, reload } = useLoad<Digest | null>(
    () => (week ? api.digest(week) : api.latestDigest()).catch((e) => (e instanceof ApiError && e.status === 404 ? null : Promise.reject(e))),
    [week],
  );
  const latest = useLoad<Digest | null>(() => api.latestDigest().catch(() => null), []);
  const isLatest = !!data && data.weekStart === latest.data?.weekStart;

  return (
    <div className="page">
      <PageHeader title="Your week" subtitle={data ? weekText(data) : "A look back at the week, every week"} actions={<>
        {data && <IconButton label="The week before" onClick={() => setWeek(shiftDate(data.weekStart, -7))}><ChevronLeft size={20} /></IconButton>}
        {data && !isLatest && <IconButton label="The week after" onClick={() => setWeek(shiftDate(data.weekStart, 7))}><ChevronRight size={20} /></IconButton>}
        <Button variant="ghost" onClick={() => navigate("/settings#digest")}><Settings2 size={18} /> Settings</Button>
      </>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {loading && data === undefined && <div className="center-pad"><Spinner size={28} /></div>}
      {data === null && (
        <Card>
          <Empty icon={<Inbox size={36} />} title={week ? "No digest for that week" : "Your first digest is on its way"}>
            {prefs?.enabled === false ? "The weekly digest is off. Turn it on in Settings." : `It comes every ${WEEKDAYS[(prefs?.day ?? 7) - 1]} at ${String(prefs?.hour ?? 18).padStart(2, "0")}:00, looking back at Monday to Sunday.`}
          </Empty>
        </Card>
      )}
      {data && (
        <div className="grid grid-2" style={{ opacity: loading ? 0.6 : 1 }}>
          <Card className="span-2 digest-headline">
            <Sparkles size={22} />
            <p>{data.headline}</p>
          </Card>
          {data.sections.map((s, i) => {
            const Icon = FEATURES.find((f) => f.id === s.module)?.icon;
            return (
              <Card key={i} title={s.title} icon={Icon ? <Icon size={18} /> : undefined} className={`digest-section tone-${s.tone}`}>
                <ul className="digest-lines">{s.lines.map((l, k) => <li key={k}>{l}</li>)}</ul>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
