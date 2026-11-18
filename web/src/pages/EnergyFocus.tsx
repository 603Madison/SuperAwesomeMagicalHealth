import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { BatteryMedium, Clock, Footprints, Moon, Pill, Plus, Settings2, Sparkles, Trash2, Utensils, Waves, X, Zap, type LucideIcon } from "lucide-react";
import { useModules } from "../App";
import { api } from "../lib/api";
import {
  ENERGY_ENDS, FOCUS_ENDS, STRIP_FROM, STRIP_TO, addReminder, canSave, doseRange, emptyDraft, headlines, hourLabel, hourRange, isThin, medicationChoice,
  medicationOptions, patternProgress, ratingText, scaleX, soFar, stripMarks, stripX, toCheck, toggleTag, type CheckDraft, type StripMark,
} from "../lib/energy";
import { dayZone, num, today } from "../lib/format";
import { clockTime, dateIn, hourIn, zonedTime } from "../lib/today";
import { CHECK_TAGS, type EnergyCheck, type EnergyDay, type EnergyFocus, type EnergyFocusSettings, type FactorKind, type Medication, type TimeBlock } from "../lib/types";
import { Meter } from "../components/charts";
import { Button, Card, ErrorNotice, Field, Modal, Spinner, errorToast, toast, useLoad } from "../components/ui";

/*
 * Energy and focus, for Autism & ADHD: a quick check-in (energy and focus, 1 to 5, the way research
 * measures how they really move through a day), today's check-ins and doses on a strip, and the
 * person's own patterns once there are enough, always as patterns rather than causes, with how it
 * works and the research behind it. The check-in's rows are shared with the + sheet and Today's
 * Right now ([QuickCheckDialog]).
 */

const pad2 = (n: number) => String(n).padStart(2, "0");

/** The hour on the person's clock at [ms], minutes as a fraction (9.75 for 9:45). */
function hourOf(ms: number, zone?: string): number {
  const h = hourIn(ms, zone);
  const top = zonedTime(dateIn(ms, zone), `${pad2(h)}:00`, zone);
  return h + Math.min(59.99, Math.max(0, (ms - top) / 60_000)) / 60;
}

const tagLabel = (id: string) => CHECK_TAGS.find((t) => t.id === id)?.label.toLowerCase() ?? id.toLowerCase();

// ---- The check-in ----

/** One 1–5 row: the numbers, with plain words at each end underneath. */
function CheckScale({ label, value, onChange, ends }: { label: string; value?: number; onChange: (v: number | undefined) => void; ends: { low: string; high: string } }) {
  const id = useId();
  return (
    <div className="field ef-scale" role="group" aria-labelledby={id}>
      <span className="field-label" id={id}>{label}{value != null && <span className="ef-scale-value">{value}</span>}</span>
      <div className="scale" role="radiogroup" aria-labelledby={id}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} type="button" role="radio" aria-checked={value === n} className={value === n ? "is-on" : ""}
            aria-label={`${n} of 5${n === 1 ? `, ${ends.low.toLowerCase()}` : n === 5 ? `, ${ends.high.toLowerCase()}` : ""}`}
            onClick={() => onChange(value === n ? undefined : n)}>{n}</button>
        ))}
      </div>
      <div className="range-ends" aria-hidden="true"><span>{ends.low}</span><span>{ends.high}</span></div>
    </div>
  );
}

/** Energy and focus (a tap each), then anything else going on and a note, both optional. */
export function CheckFields({ draft, onChange }: { draft: CheckDraft; onChange: (d: CheckDraft) => void }) {
  const tagsId = useId();
  return (
    <>
      <CheckScale label="Energy" ends={ENERGY_ENDS} value={draft.energy} onChange={(energy) => onChange({ ...draft, energy })} />
      <CheckScale label="Focus" ends={FOCUS_ENDS} value={draft.focus} onChange={(focus) => onChange({ ...draft, focus })} />
      <div className="field" role="group" aria-labelledby={tagsId}>
        <span className="field-label" id={tagsId}>Anything else going on? <span className="ef-optional">Optional</span></span>
        <div className="tag-list">
          {CHECK_TAGS.map((t) => (
            <button key={t.id} type="button" className={`chip ${draft.tags.includes(t.id) ? "is-on" : ""}`} aria-pressed={draft.tags.includes(t.id)}
              onClick={() => onChange({ ...draft, tags: toggleTag(draft.tags, t.id) })}>{t.label}</button>
          ))}
        </div>
      </div>
      <Field label="Note (optional)">
        <input value={draft.note} maxLength={1000} placeholder="A word or two, if you like" onChange={(e) => onChange({ ...draft, note: e.target.value })} />
      </Field>
    </>
  );
}

/** The check-in on its own, from the + sheet and Today's Right now. */
export function QuickCheckDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [draft, setDraft] = useState<CheckDraft>(emptyDraft);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    if (!canSave(draft)) return;
    setBusy(true);
    try {
      await api.addEnergyCheck(toCheck(draft, Date.now()));
      toast("Checked in. Thank you");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Energy and focus" subtitle="How are things right now?" onClose={onClose} footer={<>
      <Button variant="ghost" onClick={onClose}>Cancel</Button>
      <Button variant="primary" busy={busy} disabled={!canSave(draft)} onClick={save}>Save</Button>
    </>}>
      <CheckFields draft={draft} onChange={setDraft} />
    </Modal>
  );
}

// ---- The section ----

/** Autism & ADHD's Energy & focus: check in, today, your patterns, how this works, and settings. */
export function EnergyFocusSection({ readOnly, logged = 0 }: { readOnly: boolean; logged?: number }) {
  const load = useLoad(() => api.energyFocus(42), [logged]);
  const ef = load.data;
  if (load.error && !ef) return <ErrorNotice message={load.error} onRetry={load.reload} />;
  if (!ef) return <Spinner />;
  return (
    <div className="grid grid-2 ef">
      {!readOnly && <CheckInCard onSaved={load.reload} />}
      <TodayCard ef={ef} readOnly={readOnly} onChanged={load.reload} wide={readOnly} />
      <PatternsCard ef={ef} />
      <Card className="span-2 ef-about-card">
        <details className="ef-about">
          <summary>How this works</summary>
          <p>{ef.about}</p>
          {ef.sources.length > 0 && (
            <>
              <h4 className="subsection-title">The research behind it</h4>
              <ol className="ef-sources">{ef.sources.map((s) => <li key={s}>{s}</li>)}</ol>
            </>
          )}
        </details>
      </Card>
      {!readOnly && <SettingsCard ef={ef} onSaved={load.reload} />}
    </div>
  );
}

function CheckInCard({ onSaved }: { onSaved: () => void }) {
  const [draft, setDraft] = useState<CheckDraft>(emptyDraft);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    if (!canSave(draft)) return;
    setBusy(true);
    try {
      await api.addEnergyCheck(toCheck(draft, Date.now()));
      toast("Checked in. Thank you");
      setDraft(emptyDraft());
      onSaved();
    } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  return (
    <Card title="Check in" icon={<Zap size={18} />} id="energyfocus-check">
      <p className="muted small">How are things right now? A tap for each is plenty.</p>
      <CheckFields draft={draft} onChange={setDraft} />
      <div><Button variant="primary" busy={busy} disabled={!canSave(draft)} onClick={save}>Save</Button></div>
    </Card>
  );
}

/** Energy as a circle, focus as a diamond, a dose as a triangle: told apart by shape as well as colour. */
function Key({ kind }: { kind: StripMark["kind"] }) {
  return <i className={`ef-mark is-${kind}`} aria-hidden="true" />;
}

function Legend({ dose }: { dose?: string | null }) {
  return (
    <ul className="legend ef-legend">
      <li><Key kind="energy" />Energy</li>
      <li><Key kind="focus" />Focus</li>
      {dose && <li><Key kind="dose" />{dose}</li>}
    </ul>
  );
}

function TodayCard({ ef, readOnly, onChanged, wide }: { ef: EnergyFocus; readOnly: boolean; onChanged: () => void; wide: boolean }) {
  const zone = dayZone();
  const checks = [...ef.today].sort((a, b) => b.at - a.at);
  const dose = ef.dosesToday.length > 0 || ef.medication ? `A dose${ef.medication ? ` of ${ef.medication}` : ""}` : null;
  const remove = async (c: EnergyCheck) => {
    if (!c.id) return;
    try { await api.deleteEnergyCheck(c.id); toast("Check-in deleted"); onChanged(); } catch (e) { errorToast(e); }
  };
  return (
    <Card title="Today" icon={<Clock size={18} />} className={wide ? "span-2" : ""}>
      <DayStrip checks={ef.today} doses={ef.dosesToday} zone={zone} />
      <Legend dose={dose} />
      {checks.length === 0 ? <p className="muted small">Nothing yet today. Check in whenever you think of it.</p> : (
        <ul className="rows ef-today">
          {checks.map((c) => (
            <li key={c.id ?? c.at} className="row">
              <div className="row-main">
                <div className="row-title">{ratingText(c)}</div>
                <div className="row-sub">{[clockTime(c.at, zone), ...c.tags.map(tagLabel), c.note].filter(Boolean).join(" · ")}</div>
              </div>
              {!readOnly && c.id && (
                <div className="row-actions">
                  <Button variant="ghost" aria-label={`Delete the check-in at ${clockTime(c.at, zone)}`} onClick={() => void remove(c)}><Trash2 size={16} /></Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** The width a drawing has, following its box. */
function useWidth(): [React.RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

/**
 * Today from 6 AM to midnight: each check-in's energy (circles, --c1) and focus (diamonds, --c2)
 * on the 1–5 scale, and each dose (a triangle and a line, --c3); later today is shaded. The list
 * under it says the same in words.
 */
function DayStrip({ checks, doses, zone, height = 140 }: { checks: EnergyCheck[]; doses: number[]; zone?: string; height?: number }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState<string | null>(null);
  const marks = stripMarks(checks, doses, (ms) => hourOf(ms, zone));
  const p = { top: 18, right: 12, bottom: 24, left: 22 };
  const w = Math.max(0, width - p.left - p.right);
  const h = height - p.top - p.bottom;
  const sx = (x: number) => p.left + x * w;
  const sy = (v: number) => p.top + h - ((v - 1) / 4) * h;
  const every = w >= 420 ? 3 : 6;
  const ticks: number[] = [];
  for (let t = STRIP_FROM; t <= STRIP_TO; t += every) ticks.push(t);
  const now = stripX(hourOf(Date.now(), zone));
  const series = (kind: "energy" | "focus", dx: number) => {
    const points = marks.filter((m) => m.kind === kind);
    return { points, path: points.map((m, i) => `${i ? "L" : "M"}${(sx(m.x) + dx).toFixed(1)},${sy(m.value!).toFixed(1)}`).join("") };
  };
  const energy = series("energy", -4);
  const focus = series("focus", 4);
  const groups = [...new Map(marks.filter((m) => m.kind !== "dose").map((m) => [m.key, m])).values()];
  const hovered = hover ? checks.find((c) => (c.id ?? String(c.at)) === hover) : null;
  const hoveredMark = hover ? groups.find((g) => g.key === hover) : null;
  const shown = marks.filter((m) => m.kind !== "dose").length;
  const summary = `Today from 6 AM to midnight: ${checks.length === 0 ? "no check-ins yet" : `${checks.length} check-in${checks.length === 1 ? "" : "s"}`}` +
    (doses.length ? ` and ${doses.length} dose${doses.length === 1 ? "" : "s"}` : "") + (shown === 0 && checks.length > 0 ? ", before 6 AM" : "");
  return (
    <div ref={ref} className="chart ef-strip" style={{ height }}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={summary} onPointerLeave={() => setHover(null)}>
          {now != null && now < 1 && <rect x={sx(now)} y={p.top} width={sx(1) - sx(now)} height={h} className="ef-later" />}
          {[1, 3, 5].map((v) => (
            <g key={v}>
              <line x1={p.left} x2={p.left + w} y1={sy(v)} y2={sy(v)} className="gridline" />
              <text x={p.left - 8} y={sy(v)} className="axis" textAnchor="end" dominantBaseline="middle">{v}</text>
            </g>
          ))}
          {ticks.map((t) => (
            <text key={t} x={sx((t - STRIP_FROM) / (STRIP_TO - STRIP_FROM))} y={height - 6} className="axis"
              textAnchor={t === STRIP_FROM ? "start" : t === STRIP_TO ? "end" : "middle"}>{hourLabel(t)}</text>
          ))}
          {marks.filter((m) => m.kind === "dose").map((m) => {
            const x = sx(m.x);
            return (
              <g key={m.key} className="ef-dose">
                <title>{`A dose at ${clockTime(m.at, zone)}`}</title>
                <line x1={x} x2={x} y1={p.top} y2={p.top + h} />
                <path d={`M${x - 6},${p.top - 13}L${x + 6},${p.top - 13}L${x},${p.top - 3}Z`} />
              </g>
            );
          })}
          {energy.points.length > 1 && <path d={energy.path} className="ef-line is-energy" />}
          {focus.points.length > 1 && <path d={focus.path} className="ef-line is-focus" />}
          {energy.points.map((m) => <circle key={`e${m.key}`} cx={sx(m.x) - 4} cy={sy(m.value!)} r={5} className="ef-dot is-energy" />)}
          {focus.points.map((m) => {
            const x = sx(m.x) + 4;
            const y = sy(m.value!);
            return <path key={`f${m.key}`} d={`M${x},${y - 6.5}L${x + 6.5},${y}L${x},${y + 6.5}L${x - 6.5},${y}Z`} className="ef-dot is-focus" />;
          })}
          {/* Each check-in's hover target: a column wider than its marks. */}
          {groups.map((g) => (
            <rect key={`h${g.key}`} x={sx(g.x) - 14} y={p.top - 4} width={28} height={h + 8} fill="transparent"
              onPointerEnter={() => setHover(g.key)} aria-hidden="true" />
          ))}
        </svg>
      )}
      {hovered && hoveredMark && (
        <div className="chart-tip ef-tip" style={{ left: Math.min(Math.max(sx(hoveredMark.x), 80), width - 80), top: 0 }} role="status">
          <strong>{ratingText(hovered)}</strong>
          <span>{[clockTime(hovered.at, zone), ...hovered.tags.map(tagLabel)].join(" · ")}</span>
        </div>
      )}
    </div>
  );
}

const FACTOR_ICONS: Record<FactorKind, LucideIcon> = { SLEEP: Moon, MEDICATION: Pill, MOVEMENT: Footprints, MEAL: Utensils, STRESS: Waves };

function PatternsCard({ ef }: { ef: EnergyFocus }) {
  const lines = headlines(ef);
  return (
    <Card title="Your patterns" icon={<Sparkles size={18} />} className="span-2">
      {!ef.enough ? (
        <div className="ef-progress">
          <p>{ef.needed ?? "A few more check-ins and your patterns start to show."}</p>
          <Meter value={patternProgress(ef.checks, ef.days) * 100} max={100} color="var(--c2)" label="How far towards your patterns" />
          <p className="muted small">{soFar(ef.checks, ef.days)} A few a day, at different times, shows the most.</p>
        </div>
      ) : (
        <>
          {lines.length > 0 && <ul className="ef-summary">{lines.map((l) => <li key={l}>{l}</li>)}</ul>}
          <Legend />
          <div className="ef-charts">
            <section aria-labelledby="ef-by-hour">
              <h4 className="subsection-title" id="ef-by-hour">By time of day</h4>
              <BlockChart blocks={ef.byHour} name={hourRange} best={ef.bestFocus} />
            </section>
            {ef.sinceDose.length > 0 && (
              <section aria-labelledby="ef-since-dose">
                <h4 className="subsection-title" id="ef-since-dose">Since your {ef.medication ?? "ADHD medication"}</h4>
                <BlockChart blocks={ef.sinceDose} name={doseRange} />
              </section>
            )}
          </div>
          {ef.factors.length > 0 && (
            <div className="insights ef-factors">
              {ef.factors.map((f) => {
                const Icon = FACTOR_ICONS[f.kind] ?? Sparkles;
                return (
                  <article key={f.kind} className="insight tone-info">
                    <div className="insight-icon"><Icon size={20} /></div>
                    <div><h4>{f.title}</h4><p>{f.sentence}</p></div>
                  </article>
                );
              })}
            </div>
          )}
        </>
      )}
      <p className="muted small ef-caption">Patterns in your own check-ins, not proof of cause.</p>
    </Card>
  );
}

/**
 * Blocks of hours as rows: each with its average energy (circle) and focus (diamond) along the 1–5
 * scale, and the numbers. A block with only a check-in or two is shown faded.
 */
function BlockChart({ blocks, name, best }: { blocks: TimeBlock[]; name: (from: number, to: number) => string; best?: TimeBlock }) {
  const thin = blocks.some(isThin);
  const pct = (v: number) => `${(scaleX(v) * 100).toFixed(1)}%`;
  return (
    <div className="ef-blocks">
      <div className="ef-block ef-block-scale" aria-hidden="true">
        <span className="ef-block-name" />
        <span className="ef-block-ticks">{[1, 2, 3, 4, 5].map((v) => <span key={v} style={{ left: pct(v) }}>{v}</span>)}</span>
        <span className="ef-block-values" />
      </div>
      <ul className="ef-block-list">
        {blocks.map((b) => {
          const isBest = best != null && best.from === b.from && best.to === b.to;
          const label = name(b.from, b.to);
          return (
            <li key={`${b.from}-${b.to}`} className={`ef-block ${isThin(b) ? "is-thin" : ""} ${isBest ? "is-best" : ""}`}>
              <span className="ef-block-name">{label}{isBest && <span className="ef-best">best focus</span>}</span>
              <span className="ef-track" aria-hidden="true">
                {b.energy != null && <i className="ef-mark is-energy" style={{ left: pct(b.energy) }} />}
                {b.focus != null && <i className="ef-mark is-focus" style={{ left: pct(b.focus) }} />}
              </span>
              <span className="ef-block-values">
                <span className="sr-only">{`${label}: `}</span>
                {b.energy != null && <span><Key kind="energy" /><span className="sr-only">energy </span>{num(b.energy, 1)}</span>}
                {b.focus != null && <span><Key kind="focus" /><span className="sr-only">focus </span>{num(b.focus, 1)}</span>}
                <span className="sr-only">{`, from ${b.count} check-in${b.count === 1 ? "" : "s"}`}</span>
              </span>
            </li>
          );
        })}
      </ul>
      {thin && <p className="muted small">Faded: only a check-in or two so far, too few to say much.</p>}
    </div>
  );
}

function SettingsCard({ ef, onSaved }: { ef: EnergyFocus; onSaved: () => void }) {
  const medsOn = useModules().includes("MEDICATIONS");
  const meds = useLoad(() => (medsOn ? api.meds(true).catch(() => [] as Medication[]) : Promise.resolve([] as Medication[])), [medsOn]);
  const [settings, setSettings] = useState<EnergyFocusSettings>(ef.settings);
  const [time, setTime] = useState("");
  useEffect(() => setSettings(ef.settings), [ef.settings]);
  const choice = medicationChoice({ ...ef, settings }, meds.data ?? []);
  const options = medicationOptions(meds.data ?? [], settings.medicationId);
  const save = async (next: EnergyFocusSettings, said?: string) => {
    const before = settings;
    setSettings(next);
    try {
      setSettings(await api.saveEnergyFocusSettings(next));
      if (said) toast(said);
      onSaved();
    } catch (e) { setSettings(before); errorToast(e); }
  };
  const add = () => {
    const next = addReminder(settings.reminders, time);
    if (next.length === settings.reminders.length) return;
    setTime("");
    void save({ ...settings, reminders: next }, "Reminder added");
  };
  const show = (t: string) => clockTime(zonedTime("2026-01-05", t, "UTC"), "UTC");
  const help = choice.how === "chosen" ? "You chose this one."
    : choice.how === "found" ? "Found from its name. Choose another if it isn't the one."
    : "If you take one, choose it to see your focus by hours since a dose.";
  return (
    <Card title="Settings" icon={<Settings2 size={18} />} className="span-2">
      <div className="ef-settings">
        {medsOn && (
          <Field label="Your ADHD medication" help={meds.loading && !meds.data ? undefined : help}>
            <select value={choice.id} disabled={!meds.data} onChange={(e) => void save({ ...settings, medicationId: e.target.value || undefined }, "Saved")}>
              {options.map((m) => <option key={m.id} value={m.id}>{m.name}{m.dose ? ` (${m.dose})` : ""}</option>)}
              <option value="">None of these</option>
            </select>
          </Field>
        )}
        <div className="field" role="group" aria-labelledby="ef-reminders">
          <span className="field-label" id="ef-reminders">Reminders to check in</span>
          {settings.reminders.length > 0 && (
            <div className="tag-list">
              {settings.reminders.map((t) => (
                <span key={t} className="chip is-static">
                  {show(t)}
                  <button type="button" className="chip-x" aria-label={`Remove the reminder at ${show(t)}`}
                    onClick={() => void save({ ...settings, reminders: settings.reminders.filter((x) => x !== t) }, "Reminder removed")}><X size={14} /></button>
                </span>
              ))}
            </div>
          )}
          {settings.reminders.length < 6 && (
            <form className="ef-add-time" onSubmit={(e) => { e.preventDefault(); add(); }}>
              <input type="time" value={time} aria-label="A time to be reminded" onChange={(e) => setTime(e.target.value)} />
              <Button type="submit" disabled={!time}><Plus size={16} /> Add</Button>
            </form>
          )}
          <span className="field-help">
            {settings.reminders.length === 0 ? "None unless you add some. " : ""}A gentle nudge at each time, in Right now and as a notification if you have those on.
          </span>
        </div>
      </div>
    </Card>
  );
}

// ---- The social battery ----

/** Today's social battery, 0 empty … 10 full: its own small card. */
export function SocialBatteryCard({ readOnly, logged = 0 }: { readOnly: boolean; logged?: number }) {
  const date = today();
  const load = useLoad(() => api.energy(1).catch(() => [] as EnergyDay[]), [date, logged]);
  const saved = load.data?.find((d) => d.date === date)?.socialBattery;
  const [value, setValue] = useState<number | undefined>(undefined);
  const timer = useRef<number | undefined>(undefined);
  const last = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const shown = value ?? saved;
  const commit = (v: number) => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(async () => {
      if (last.current === v) return;
      try { await api.saveSocialBattery(date, v); last.current = v; } catch (e) { errorToast(e); }
    }, 500);
  };
  const change = (v: number) => { setValue(v); commit(v); };
  return (
    <Card title="Social battery" icon={<BatteryMedium size={18} />} id="social">
      {readOnly ? <p>{saved != null ? `${saved} of 10 today` : "Not set today."}</p> : (
        <div className="field ef-social">
          <span className="field-label" id="ef-social">{shown != null ? `Today: ${shown} of 10` : "How full is it today?"}</span>
          <input type="range" min={0} max={10} step={1} value={shown ?? 5} aria-labelledby="ef-social" className={shown == null ? "is-unset" : ""}
            aria-valuetext={shown != null ? `${shown} of 10` : "Not set"}
            onChange={(e) => change(Number(e.target.value))}
            onPointerUp={(e) => change(Number(e.currentTarget.value))} />
          <div className="range-ends" aria-hidden="true"><span>Empty</span><span>Full</span></div>
          <span className="field-help">After people and plans, how much is left. It's yours to set, as often as you like.</span>
        </div>
      )}
    </Card>
  );
}

