import { useState } from "react";
import { Plus, Scale, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, shortDate, useUnits, weight, weightValue } from "../lib/format";
import { useSession } from "../lib/session";
import { LineChart } from "../components/charts";
import { Button, Card, Empty, ErrorNotice, PageHeader, Segmented, Spinner, Stat, errorToast, useLoad } from "../components/ui";
import { WeightDialog } from "../components/LogDialogs";

export function Body() {
  const units = useUnits();
  const [days, setDays] = useState(90);
  const goals = useSession((s) => s.profile?.goals);
  const weights = useLoad(() => api.measurements("WEIGHT", days), [days]);
  const fat = useLoad(() => api.measurements("BODY_FAT", days), [days]);
  const [adding, setAdding] = useState(false);
  const reload = () => { weights.reload(); fat.reload(); };
  const list = weights.data ?? [];
  const latest = list[0];
  const oldest = list[list.length - 1];
  const change = latest && oldest && latest !== oldest ? latest.value - oldest.value : undefined;
  const target = goals?.targetWeightKg ?? undefined;
  const unit = units === "IMPERIAL" ? "lb" : "kg";

  return (
    <div className="page">
      <PageHeader title="Body" subtitle="Weight and body composition" actions={<>
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 30, label: "Month" }, { value: 90, label: "3 months" }, { value: 365, label: "Year" }]} />
        <Button variant="primary" onClick={() => setAdding(true)}><Plus size={18} /> Log weight</Button>
      </>} />
      {weights.error && <ErrorNotice message={weights.error} onRetry={weights.reload} />}
      {!weights.data && weights.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {weights.data && list.length === 0 && (
        <Card><Empty icon={<Scale size={36} />} title="No weights yet">Log your weight here or from the phone app; a smart scale in Health Connect comes in by itself.</Empty></Card>
      )}
      {list.length > 0 && (
        <div className="grid grid-2">
          <Card title="Weight" icon={<Scale size={18} />} className="span-2">
            <div className="stats">
              <Stat label="Latest" value={weight(latest.value, units)} sub={new Date(latest.start).toLocaleDateString()} />
              {change != null && <Stat label="Change" value={`${change > 0 ? "+" : "−"}${weight(Math.abs(change), units)}`} sub={`since ${new Date(oldest.start).toLocaleDateString()}`} />}
              {target != null && <Stat label="Target" value={weight(target, units)} sub={`${weight(Math.abs(latest.value - target), units)} to go`} />}
              {fat.data?.[0] && <Stat label="Body fat" value={fat.data[0].value.toFixed(1)} unit="%" />}
            </div>
            <LineChart
              label="Weight over time" color="var(--c1)" height={220} dots={list.length <= 60}
              band={target != null ? [weightValue(target, units) - 0.5, weightValue(target, units) + 0.5] : undefined}
              points={[...list].reverse().map((s) => ({ x: s.start, y: weightValue(s.value, units) }))}
              formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${y.toFixed(1)} ${unit}`}
            />
          </Card>
          <Card title="Entries" className="span-2">
            <ul className="rows">
              {list.slice(0, 50).map((s) => (
                <li key={s.id} className="row">
                  <div className="row-main">
                    <div className="row-title">{weight(s.value, units)}</div>
                    <div className="row-sub">{new Date(s.start).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" })} · {s.source}</div>
                  </div>
                  {s.source === "Entered by hand" && (
                    <div className="row-actions">
                      <Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteMeasurement(s.id!); reload(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </Card>
        </div>
      )}
      {adding && <WeightDialog onClose={() => setAdding(false)} onSaved={reload} />}
    </div>
  );
}
