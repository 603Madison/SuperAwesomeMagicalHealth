import { useState } from "react";
import { Moon, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, hm, isoDate, num, shortDate } from "../lib/format";
import { useSession } from "../lib/session";
import { BarChart, Hypnogram, LineChart, StageLegend, scoreFloor } from "../components/charts";
import { Button, Card, Empty, ErrorNotice, PageHeader, Segmented, Spinner, Stat, errorToast, useLoad } from "../components/ui";
import { SleepDialog } from "../components/LogDialogs";

export function Sleep() {
  const [days, setDays] = useState(30);
  const goal = useSession((s) => s.profile?.goals.sleepMinutes ?? 480);
  const trends = useLoad(() => api.trends(days), [days]);
  const nights = useLoad(() => api.sleep(Math.min(days, 30)), [days]);
  const [adding, setAdding] = useState(false);
  const reload = () => { trends.reload(); nights.reload(); };
  const rows = trends.data?.days ?? [];
  const slept = rows.filter((r) => r.sleepMinutes != null);
  const avg = (f: (r: (typeof rows)[number]) => number | undefined) => {
    const v = rows.map(f).filter((x): x is number => x != null);
    return v.length ? v.reduce((a, b) => a + b, 0) / v.length : undefined;
  };

  return (
    <div className="page">
      <PageHeader title="Sleep" subtitle="How long, how well, and when" actions={<>
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 7, label: "Week" }, { value: 30, label: "Month" }, { value: 90, label: "3 months" }]} />
        <Button onClick={() => setAdding(true)}><Plus size={18} /> Add a night</Button>
      </>} />
      {trends.error && <ErrorNotice message={trends.error} onRetry={trends.reload} />}
      {!trends.data && trends.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {trends.data && (
        <div className="grid grid-2">
          <Card title="Averages" icon={<Moon size={18} />} className="span-2">
            <div className="stats">
              <Stat label="Asleep" value={hm(avg((r) => r.sleepMinutes))} sub={`goal ${hm(goal)}`} />
              <Stat label="Sleep score" value={num(avg((r) => r.sleepScore))} />
              <Stat label="Deep" value={hm(avg((r) => r.deepMinutes))} />
              <Stat label="REM" value={hm(avg((r) => r.remMinutes))} />
              <Stat label="Nights recorded" value={slept.length} sub={`of ${rows.length}`} />
            </div>
          </Card>
          <Card title="Time asleep">
            <BarChart
              label="Time asleep each night" color="var(--c1)" goal={goal / 60} labelEvery={Math.ceil(rows.length / 8)}
              formatY={(v) => hm(v * 60)} formatTick={(v) => `${v}h`}
              bars={rows.map((r) => ({ key: r.date, label: shortDate(r.date), value: r.sleepMinutes != null ? r.sleepMinutes / 60 : null, detail: shortDate(r.date) }))}
            />
          </Card>
          <Card title="Sleep score">
            <LineChart
              label="Sleep score each night" color="var(--c1)" yMin={scoreFloor(rows.map((r) => r.sleepScore))} yMax={100} band={[85, 100]} area={false} dots={rows.length <= 31}
              points={rows.map((r) => ({ x: new Date(`${r.date}T12:00`).getTime(), y: r.sleepScore ?? null }))}
              formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => String(Math.round(y))}
            />
          </Card>
        </div>
      )}

      <h2 className="section-title">Nights</h2>
      {nights.error && <ErrorNotice message={nights.error} onRetry={nights.reload} />}
      {nights.data && nights.data.length === 0 && (
        <Card><Empty icon={<Moon size={36} />} title="No nights yet">Wear your ring or watch to bed and sync with the phone app, or add a night by hand.</Empty></Card>
      )}
      {nights.data && nights.data.length > 0 && (
        <Card>
          {nights.data.map((n) => (
            <div key={n.sessionId} className="night">
              <div className="night-head">
                <div>
                  <strong>{new Date(n.end).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" })}</strong>
                  <div className="muted small">{clock(n.start)} – {clock(n.end)} · {hm(n.asleepMinutes)} asleep · {n.efficiency}% efficiency{n.lowestHeartRate ? ` · lowest ${num(n.lowestHeartRate)} bpm` : ""}{n.source ? ` · ${n.source}` : ""}</div>
                </div>
                {n.source === "Entered by hand" && n.sessionId && (
                  <Button variant="ghost" onClick={async () => { try { await api.deleteSleep(n.sessionId!); reload(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /> Delete</Button>
                )}
              </div>
              {n.stages.length > 0 && <Hypnogram stages={n.stages} start={n.start} end={n.end} height={130} />}
              {n.staged && <StageLegend deep={n.deepMinutes} rem={n.remMinutes} light={n.lightMinutes} awake={n.awakeMinutes} />}
            </div>
          ))}
        </Card>
      )}
      {adding && <SleepDialog onClose={() => setAdding(false)} onSaved={reload} />}
    </div>
  );
}
