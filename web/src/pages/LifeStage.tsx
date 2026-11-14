import { useEffect, useState } from "react";
import { Baby, Footprints, Hourglass, Milk, Play, Sparkles, Square, ThermometerSun, Timer, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, dayLabel, dayOf, hm, isoDate, num, parseDate, shortDate, today } from "../lib/format";
import type { Contraction, Feed, FeedKind, KickSession, LifeStage, LifeStageInfo, LifeStageOverview } from "../lib/types";
import { Meter } from "../components/charts";
import { Button, Card, Field, Modal, Stat, Toggle, errorToast, toast } from "../components/ui";
import { INSIGHT_ICONS } from "../components/LogDialogs";

export const STAGES: { id: LifeStage; label: string; about: string }[] = [
  { id: "CYCLING", label: "Tracking my cycle", about: "Periods, symptoms and predictions." },
  { id: "TRYING", label: "Trying to conceive", about: "The fertile window and ovulation, front and centre." },
  { id: "PREGNANT", label: "Pregnant", about: "Weeks, the due date, a kick counter and a contraction timer." },
  { id: "POSTPARTUM", label: "After a birth", about: "Feeds, recovery, bleeding and how you're feeling." },
  { id: "PERIMENOPAUSE", label: "Perimenopause", about: "Changing cycles, hot flashes, sleep and mood." },
  { id: "MENOPAUSE", label: "After menopause", about: "Hot flashes, sleep and mood; no period predictions." },
];

/** Whether period predictions make sense in this stage. */
export const predicts = (stage: LifeStage) => stage === "CYCLING" || stage === "TRYING" || stage === "PERIMENOPAUSE";

function useNow(every = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), every);
    return () => clearInterval(timer);
  }, [every]);
  return now;
}

function mmss(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export function StageDialog({ info, onClose, onSaved }: { info: LifeStageInfo; onClose: () => void; onSaved: () => void }) {
  const [i, setI] = useState<LifeStageInfo>(info);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await api.saveLifeStage({ ...i, since: i.stage === info.stage ? info.since : today() });
      toast("Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title="Where you are" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <p className="muted">This shapes what Cycle shows and what Today and your insights talk about. You can change it any time.</p>
      <div className="stage-options" role="radiogroup" aria-label="Life stage">
        {STAGES.map((s) => (
          <button key={s.id} type="button" role="radio" aria-checked={i.stage === s.id} className={`kind-option ${i.stage === s.id ? "is-on" : ""}`} onClick={() => setI({ ...i, stage: s.id })}>
            <strong>{s.label}</strong><span>{s.about}</span>
          </button>
        ))}
      </div>
      {i.stage === "PREGNANT" && (
        <div className="two-col">
          <Field label="Due date" help="From your scan, if you have one"><input type="date" value={i.dueDate ?? ""} onChange={(e) => setI({ ...i, dueDate: e.target.value || undefined })} /></Field>
          <Field label="Or the first day of your last period" help="The due date is worked out from it"><input type="date" max={today()} value={i.lastPeriod ?? ""} onChange={(e) => setI({ ...i, lastPeriod: e.target.value || undefined })} /></Field>
        </div>
      )}
      {i.stage === "POSTPARTUM" && (
        <Field label="The birth was on"><input type="date" max={today()} value={i.birthDate ?? ""} onChange={(e) => setI({ ...i, birthDate: e.target.value || undefined })} /></Field>
      )}
      {(i.stage === "PERIMENOPAUSE" || i.stage === "MENOPAUSE") && (
        <Field label="Your last period started" help="Optional: twelve months without one is when menopause is counted"><input type="date" max={today()} value={i.lastPeriod ?? ""} onChange={(e) => setI({ ...i, lastPeriod: e.target.value || undefined })} /></Field>
      )}
    </Modal>
  );
}

/** What the stage brings: pregnancy, after a birth, perimenopause and after. */
export function StagePanels({ overview, readOnly, onChanged }: { overview: LifeStageOverview; readOnly: boolean; onChanged: () => void }) {
  const stage = overview.info.stage;
  return (
    <>
      {stage === "PREGNANT" && <Pregnancy overview={overview} />}
      {stage === "PREGNANT" && <Kicks overview={overview} readOnly={readOnly} onChanged={onChanged} />}
      {stage === "PREGNANT" && <Contractions overview={overview} readOnly={readOnly} onChanged={onChanged} />}
      {stage === "POSTPARTUM" && <AfterBirth overview={overview} />}
      {stage === "POSTPARTUM" && <Feeds overview={overview} readOnly={readOnly} onChanged={onChanged} />}
      {(stage === "PERIMENOPAUSE" || stage === "MENOPAUSE") && <HotFlashes overview={overview} readOnly={readOnly} onChanged={onChanged} />}
      {overview.tips.length > 0 && (
        <Card title="Good to know" icon={<Sparkles size={18} />} className="span-2">
          <div className="insights" style={{ margin: 0 }}>
            {overview.tips.map((t, i) => {
              const Icon = INSIGHT_ICONS[t.icon] ?? Sparkles;
              return <article key={i} className={`insight tone-${t.tone}`}><div className="insight-icon"><Icon size={20} /></div><div><h4>{t.title}</h4><p>{t.body}</p></div></article>;
            })}
          </div>
        </Card>
      )}
    </>
  );
}

function Pregnancy({ overview }: { overview: LifeStageOverview }) {
  const p = overview.pregnancy;
  if (!p) return <Card title="Pregnancy" icon={<Baby size={18} />} className="span-2"><p className="muted">Add your due date, or the first day of your last period, to see your weeks.</p></Card>;
  const days = p.week * 7 + p.day;
  return (
    <Card title="Pregnancy" icon={<Baby size={18} />} className="span-2">
      <div className="stats">
        <Stat label="Week" value={`${p.week}+${p.day}`} sub={`trimester ${p.trimester}`} />
        <Stat label="Due" value={parseDate(p.dueDate).toLocaleDateString(undefined, { day: "numeric", month: "short" })} sub={p.daysToGo > 0 ? `${p.daysToGo} days to go` : "Any day now"} />
      </div>
      <Meter value={Math.min(days, 280)} max={280} color="var(--c2)" label={`${days} of about 280 days`} />
      <p className="muted small">A due date is an estimate: most babies arrive in the weeks either side of it.</p>
    </Card>
  );
}

function Kicks({ overview, readOnly, onChanged }: { overview: LifeStageOverview; readOnly: boolean; onChanged: () => void }) {
  const open = overview.kicks.find((k) => k.end == null);
  const [count, setCount] = useState(open?.kicks ?? 0);
  const now = useNow();
  useEffect(() => setCount(open?.kicks ?? 0), [open?.id, open?.kicks]);
  const save = async (k: KickSession, done = false) => {
    try { await api.saveKicks(done ? { ...k, end: Date.now() } : k); onChanged(); } catch (e) { errorToast(e); }
  };
  const past = overview.kicks.filter((k) => k.end != null).slice(0, 5);
  return (
    <Card title="Baby's movements" icon={<Footprints size={18} />}>
      {open ? (
        <div className="counter">
          <button type="button" className="counter-button" disabled={readOnly} aria-label={`A movement. ${count} so far`} onClick={() => { const next = count + 1; setCount(next); void save({ ...open, kicks: next }); }}>
            <strong>{count}</strong><span>Tap for each movement</span>
          </button>
          <p className="muted small">{mmss(now - open.start)} so far</p>
          {!readOnly && <Button onClick={() => save({ ...open, kicks: count }, true)}><Square size={16} /> Done</Button>}
        </div>
      ) : (
        <>
          <p className="muted">Get to know your baby's usual pattern of movements. There's no set number to reach.</p>
          {!readOnly && <div><Button variant="primary" onClick={() => save({ start: Date.now(), kicks: 0 })}><Play size={16} /> Start counting</Button></div>}
        </>
      )}
      {past.length > 0 && (
        <ul className="rows">
          {past.map((k) => (
            <li key={k.id} className="row">
              <div className="row-main"><div className="row-title">{k.kicks} movements in {hm((k.end! - k.start) / 60_000)}</div><div className="row-sub">{dayLabel(isoDate(new Date(k.start)))}, {clock(k.start)}</div></div>
              {!readOnly && <div className="row-actions"><Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteKicks(k.id!); onChanged(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button></div>}
            </li>
          ))}
        </ul>
      )}
      <p className="small">If your baby's movements slow down, stop or change, contact your midwife or maternity unit straight away, day or night. Don't wait until tomorrow.</p>
    </Card>
  );
}

function Contractions({ overview, readOnly, onChanged }: { overview: LifeStageOverview; readOnly: boolean; onChanged: () => void }) {
  const list = [...overview.contractions].sort((a, b) => b.start - a.start);
  const open = list.find((c) => c.end == null);
  const now = useNow();
  const toggle = async () => {
    try {
      if (open) await api.saveContraction({ ...open, end: Date.now() });
      else await api.saveContraction({ start: Date.now() });
      onChanged();
    } catch (e) { errorToast(e); }
  };
  const recent = list.filter((c) => c.end != null && Date.now() - c.start < 3 * 3_600_000).slice(0, 8);
  const gaps = recent.slice(0, -1).map((c, i) => c.start - recent[i + 1].start);
  const avgGap = gaps.length ? gaps.reduce((a, b) => a + b, 0) / gaps.length : null;
  const avgLength = recent.length ? recent.reduce((a, c) => a + (c.end! - c.start), 0) / recent.length : null;
  return (
    <Card title="Contractions" icon={<Timer size={18} />}>
      {!readOnly && (
        <button type="button" className={`timer-button ${open ? "is-running" : ""}`} onClick={toggle} aria-pressed={!!open}>
          {open ? <><strong>{mmss(now - open.start)}</strong><span>Tap when it ends</span></> : <><strong>Start</strong><span>Tap when a contraction begins</span></>}
        </button>
      )}
      {recent.length > 0 && (
        <div className="stats">
          {avgGap != null && <Stat label="Apart" value={mmss(avgGap)} sub="on average, start to start" />}
          {avgLength != null && <Stat label="Lasting" value={mmss(avgLength)} />}
          <Stat label="In the last 3 hours" value={recent.length} />
        </div>
      )}
      {overview.contractionsPattern && <p className="notice">{overview.contractionsPattern}</p>}
      <p className="muted small">Your midwife will tell you when to call: often when they come about every 5 minutes, last about a minute, for an hour (5-1-1). Call straight away if your waters break, you're bleeding, or the baby moves less.</p>
      {recent.length > 0 && (
        <ul className="rows">
          {recent.map((c: Contraction, i) => (
            <li key={c.id ?? c.start} className="row">
              <div className="row-main"><div className="row-title">{clock(c.start)} · {mmss(c.end! - c.start)}</div><div className="row-sub">{gaps[i] != null ? `${mmss(gaps[i])} after the one before` : "First in this run"}</div></div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function AfterBirth({ overview }: { overview: LifeStageOverview }) {
  return (
    <Card title="After the birth" icon={<Baby size={18} />} className="span-2">
      <div className="stats">
        {overview.weeksSinceBirth != null && <Stat label="Weeks since the birth" value={overview.weeksSinceBirth} />}
        <Stat label="Feeds today" value={overview.feeds.filter((f) => dayOf(f.start) === today()).length} />
      </div>
      <p className="muted small">Go gently. Bleeding, tiredness and big feelings are all common in the first weeks. If your mood stays low or you feel unlike yourself, Mind has a check-in made for this time, and your midwife, health visitor or doctor can help.</p>
    </Card>
  );
}

const FEED_KINDS: { id: FeedKind; label: string }[] = [
  { id: "LEFT", label: "Left" }, { id: "RIGHT", label: "Right" }, { id: "BOTH", label: "Both sides" }, { id: "BOTTLE", label: "Bottle" }, { id: "PUMP", label: "Pump" },
];

function Feeds({ overview, readOnly, onChanged }: { overview: LifeStageOverview; readOnly: boolean; onChanged: () => void }) {
  const list = [...overview.feeds].sort((a, b) => b.start - a.start);
  const open = list.find((f) => f.end == null);
  const last = list.find((f) => f.end != null);
  const [amount, setAmount] = useState<Feed | null>(null);
  const now = useNow(15_000);
  const start = async (kind: FeedKind) => { try { await api.saveFeed({ start: Date.now(), kind }); onChanged(); } catch (e) { errorToast(e); } };
  const stop = async () => {
    if (!open) return;
    if (open.kind === "BOTTLE" || open.kind === "PUMP") { setAmount({ ...open, end: Date.now() }); return; }
    try { await api.saveFeed({ ...open, end: Date.now() }); onChanged(); } catch (e) { errorToast(e); }
  };
  return (
    <Card title="Feeds" icon={<Milk size={18} />}>
      {open ? (
        <div className="feed-open">
          <strong>{FEED_KINDS.find((k) => k.id === open.kind)?.label} · {hm((now - open.start) / 60_000)}</strong>
          {!readOnly && <Button variant="primary" onClick={stop}><Square size={16} /> Finished</Button>}
        </div>
      ) : !readOnly && (
        <div className="button-row">{FEED_KINDS.map((k) => <Button key={k.id} onClick={() => start(k.id)}><Play size={14} /> {k.label}</Button>)}</div>
      )}
      {last && !open && <p className="muted small">Last feed {clock(last.start)} ({FEED_KINDS.find((k) => k.id === last.kind)?.label.toLowerCase()}), {hm((now - last.start) / 60_000)} ago.</p>}
      {list.filter((f) => f.end != null).length > 0 && (
        <ul className="rows">
          {list.filter((f) => f.end != null).slice(0, 8).map((f) => (
            <li key={f.id} className="row">
              <div className="row-main">
                <div className="row-title">{FEED_KINDS.find((k) => k.id === f.kind)?.label}{f.amountMl ? ` · ${f.amountMl} ml` : ""}</div>
                <div className="row-sub">{dayLabel(isoDate(new Date(f.start)))}, {clock(f.start)} · {hm((f.end! - f.start) / 60_000)}</div>
              </div>
              {!readOnly && <div className="row-actions"><Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteFeed(f.id!); onChanged(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button></div>}
            </li>
          ))}
        </ul>
      )}
      {amount && <FeedAmountDialog feed={amount} onClose={() => setAmount(null)} onSaved={onChanged} />}
    </Card>
  );
}

function FeedAmountDialog({ feed, onClose, onSaved }: { feed: Feed; onClose: () => void; onSaved: () => void }) {
  const [ml, setMl] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <Modal title="How much?" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={async () => {
      setBusy(true);
      try { await api.saveFeed({ ...feed, amountMl: Number(ml) || undefined }); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
    }}>Save</Button></>}>
      <Field label="Millilitres" help="Optional"><input autoFocus inputMode="numeric" value={ml} onChange={(e) => setMl(e.target.value)} /></Field>
    </Modal>
  );
}

function HotFlashes({ overview, readOnly, onChanged }: { overview: LifeStageOverview; readOnly: boolean; onChanged: () => void }) {
  const [night, setNight] = useState(() => { const h = new Date().getHours(); return h >= 22 || h < 7; });
  const todays = overview.hotFlashes.filter((h) => dayOf(h.at) === today());
  const months = overview.monthsSinceLastPeriod;
  const log = async (intensity: number) => {
    try { await api.addHotFlash({ at: Date.now(), intensity, night }); toast("Logged"); onChanged(); } catch (e) { errorToast(e); }
  };
  return (
    <Card title="Hot flashes" icon={<ThermometerSun size={18} />} className="span-2">
      <div className="stats">
        <Stat label="Today" value={todays.length} />
        {overview.hotFlashesPerDay != null && <Stat label="A day, on average" value={num(overview.hotFlashesPerDay, 1)} sub="over the last month" />}
        {months != null && <Stat label="Since your last period" value={months} unit={months === 1 ? "month" : "months"} sub={months >= 12 ? "a year or more: menopause" : `${12 - months} more to a year`} icon={<Hourglass size={14} />} />}
      </div>
      {!readOnly && (
        <>
          <div className="button-row">
            <Button onClick={() => log(1)}>Mild</Button>
            <Button onClick={() => log(2)}>Moderate</Button>
            <Button onClick={() => log(3)}>Strong</Button>
          </div>
          <Toggle label="At night (a night sweat)" checked={night} onChange={setNight} />
        </>
      )}
      {todays.length > 0 && <p className="muted small">Today at {todays.map((h) => clock(h.at)).join(", ")}.</p>}
      <p className="muted small">Your ring's temperature and your sleep show here alongside, so you can see how nights are going. A doctor can talk through what helps, including hormone therapy.</p>
      {overview.info.stage === "MENOPAUSE" && <p className="small">Any bleeding after menopause is worth telling your doctor about.</p>}
    </Card>
  );
}

export function stageSubtitle(info: LifeStageInfo): string {
  const s = STAGES.find((x) => x.id === info.stage)!;
  return info.since ? `${s.label} · since ${shortDate(info.since)}` : s.label;
}
