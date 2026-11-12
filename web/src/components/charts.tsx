import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import type { SleepStage, StageSpan } from "../lib/types";
import { clock, hm } from "../lib/format";

/*
 * Small hand-drawn SVG charts, in the family's look: thin marks, rounded bar ends on a single
 * baseline, hairline grids, labels in text colours (never the data's), and a tooltip on hover
 * and focus. Colours come from the stylesheet's chart tokens (--c1, --c2, --c3, validated for
 * colour-blind separation in light and dark).
 */

/** The width a chart has to draw in, following its container. */
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

/** Clean ticks between [min] and [max]: 0 / 50 / 100, 0 / 2,000 / 4,000. */
function ticks(min: number, max: number, count = 3): number[] {
  if (max <= min) return [min];
  const raw = (max - min) / count;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= raw) ?? raw;
  const first = Math.ceil(min / step) * step;
  const out: number[] = [];
  for (let v = first; v <= max + step * 1e-9; v += step) out.push(Number(v.toFixed(6)));
  return out;
}

const compact = (v: number) => (Math.abs(v) >= 10_000 ? `${Math.round(v / 1000)}K` : v.toLocaleString(undefined, { maximumFractionDigits: 1 }));

function Tooltip({ x, y, width, children }: { x: number; y: number; width: number; children: ReactNode }) {
  const left = Math.min(Math.max(x, 70), width - 70);
  return <div className="chart-tip" style={{ left, top: y }} role="status">{children}</div>;
}

// ---- Score ring ----

export function ScoreRing({ score, label, color, size = 128, onClick, sub }: { score?: number; label: string; color: string; size?: number; onClick?: () => void; sub?: string }) {
  const stroke = Math.max(8, size * 0.085);
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const [shown, setShown] = useState(0);
  // The ring fills in when the score arrives.
  useEffect(() => {
    const id = requestAnimationFrame(() => setShown(score ?? 0));
    return () => cancelAnimationFrame(id);
  }, [score]);
  const body = (
    <>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={score == null ? `${label}: not enough data yet` : `${label}: ${score}`}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--ring-track)" strokeWidth={stroke} />
        {score != null && (
          <circle
            cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
            strokeDasharray={`${(c * shown) / 100} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} className="ring-arc"
          />
        )}
        <text x="50%" y="50%" textAnchor="middle" dominantBaseline="central" className="ring-number" style={{ fontSize: size * 0.3 }}>
          {score ?? "–"}
        </text>
      </svg>
      <span className="ring-label"><span className="ring-key" style={{ background: color }} />{label}</span>
      {sub && <span className="ring-sub">{sub}</span>}
    </>
  );
  return onClick ? <button className="ring" onClick={onClick}>{body}</button> : <div className="ring">{body}</div>;
}

/** A score chart's floor: close enough below the lowest score that the line has room to move. */
export function scoreFloor(values: (number | null | undefined)[]): number {
  const known = values.filter((v): v is number => v != null);
  if (!known.length) return 0;
  return Math.max(0, Math.min(60, Math.floor((Math.min(...known) - 10) / 10) * 10));
}

// ---- Line chart ----

export interface LinePoint {
  x: number;
  y: number | null;
}

/**
 * A line over time with a soft wash under it; gaps where there's no value. A crosshair snaps to the
 * nearest point and the tooltip reads it out.
 */
export function LineChart({
  points, height = 180, color = "var(--c1)", formatX, formatY, yMin, yMax, area = true, band, label, dots = false,
}: {
  points: LinePoint[];
  height?: number;
  color?: string;
  formatX: (x: number) => string;
  formatY: (y: number) => string;
  yMin?: number;
  yMax?: number;
  area?: boolean;
  /** A shaded range, e.g. the goal or the usual. */
  band?: [number, number];
  label: string;
  dots?: boolean;
}) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const values = points.map((p) => p.y).filter((v): v is number => v != null);
  const pad = { top: 12, right: 12, bottom: 24, left: 40 };
  const w = Math.max(0, width - pad.left - pad.right);
  const h = height - pad.top - pad.bottom;
  if (values.length === 0) return <div ref={ref} className="chart-empty" style={{ height }}>Nothing recorded</div>;
  let lo = yMin ?? Math.min(...values, ...(band ?? []));
  let hi = yMax ?? Math.max(...values, ...(band ?? []));
  if (hi === lo) { hi += 1; lo -= 1; }
  if (yMin == null) lo -= (hi - lo) * 0.08;
  if (yMax == null) hi += (hi - lo) * 0.08;
  const xs = points.map((p) => p.x);
  const x0 = Math.min(...xs);
  const x1 = Math.max(...xs);
  const sx = (x: number) => pad.left + (x1 === x0 ? w / 2 : ((x - x0) / (x1 - x0)) * w);
  const sy = (y: number) => pad.top + h - ((y - lo) / (hi - lo)) * h;

  // Runs of points with values: each its own line, so gaps stay gaps.
  const runs: LinePoint[][] = [];
  points.forEach((p) => {
    if (p.y == null) { runs.push([]); return; }
    if (runs.length === 0) runs.push([]);
    runs[runs.length - 1].push(p);
  });
  const path = (run: LinePoint[]) => run.map((p, i) => `${i ? "L" : "M"}${sx(p.x).toFixed(1)},${sy(p.y!).toFixed(1)}`).join("");
  const yTicks = ticks(lo, hi, 3);
  const xTickCount = Math.max(2, Math.min(6, Math.floor(w / 90)));
  const xTicks = Array.from({ length: xTickCount }, (_, i) => x0 + ((x1 - x0) * i) / (xTickCount - 1));

  const nearest = (clientX: number, rect: DOMRect) => {
    const x = clientX - rect.left;
    let best = -1;
    let bestDist = Infinity;
    points.forEach((p, i) => {
      if (p.y == null) return;
      const d = Math.abs(sx(p.x) - x);
      if (d < bestDist) { bestDist = d; best = i; }
    });
    return best;
  };
  const hovered = hover != null ? points[hover] : null;

  return (
    <div ref={ref} className="chart" style={{ height }}>
      {width > 0 && (
        <svg
          width={width} height={height} role="img" aria-label={label}
          onPointerMove={(e) => setHover(nearest(e.clientX, e.currentTarget.getBoundingClientRect()))}
          onPointerLeave={() => setHover(null)}
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
            e.preventDefault();
            const withValues = points.map((p, i) => (p.y != null ? i : -1)).filter((i) => i >= 0);
            const at = hover == null ? -1 : withValues.indexOf(hover);
            const next = e.key === "ArrowRight" ? withValues[Math.min(withValues.length - 1, at + 1)] : withValues[Math.max(0, at - 1)];
            setHover(next ?? null);
          }}
          onBlur={() => setHover(null)}
        >
          {yTicks.map((t) => (
            <g key={t}>
              <line x1={pad.left} x2={pad.left + w} y1={sy(t)} y2={sy(t)} className="gridline" />
              <text x={pad.left - 8} y={sy(t)} className="axis" textAnchor="end" dominantBaseline="middle">{formatY(t)}</text>
            </g>
          ))}
          {band && <rect x={pad.left} width={w} y={sy(Math.min(hi, band[1]))} height={Math.max(0, sy(Math.max(lo, band[0])) - sy(Math.min(hi, band[1])))} className="chart-band" />}
          {xTicks.map((t, i) => (
            <text key={i} x={sx(t)} y={height - 6} className="axis" textAnchor={i === 0 ? "start" : i === xTicks.length - 1 ? "end" : "middle"}>{formatX(t)}</text>
          ))}
          {area && runs.filter((r) => r.length > 1).map((run, i) => (
            <path key={`a${i}`} d={`${path(run)}L${sx(run[run.length - 1].x)},${pad.top + h}L${sx(run[0].x)},${pad.top + h}Z`} fill={color} opacity={0.1} />
          ))}
          {runs.map((run, i) => run.length > 1
            ? <path key={i} d={path(run)} fill="none" stroke={color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
            : run.length === 1 ? <circle key={i} cx={sx(run[0].x)} cy={sy(run[0].y!)} r={3} fill={color} /> : null)}
          {dots && points.filter((p) => p.y != null).map((p, i) => <circle key={`d${i}`} cx={sx(p.x)} cy={sy(p.y!)} r={4} fill={color} stroke="var(--chart-surface)" strokeWidth={2} />)}
          {hovered && hovered.y != null && (
            <g pointerEvents="none">
              <line x1={sx(hovered.x)} x2={sx(hovered.x)} y1={pad.top} y2={pad.top + h} className="crosshair" />
              <circle cx={sx(hovered.x)} cy={sy(hovered.y)} r={5} fill={color} stroke="var(--chart-surface)" strokeWidth={2} />
            </g>
          )}
        </svg>
      )}
      {hovered && hovered.y != null && (
        <Tooltip x={sx(hovered.x)} y={Math.max(0, sy(hovered.y) - 54)} width={width}>
          <strong>{formatY(hovered.y)}</strong>
          <span>{formatX(hovered.x)}</span>
        </Tooltip>
      )}
    </div>
  );
}

// ---- Bar chart ----

export interface Bar {
  key: string;
  label: string;
  value: number | null;
  /** Shown in the tooltip under the value. */
  detail?: string;
}

/** Columns from one baseline; each column is its own hover target. [goal] draws a hairline to reach for. */
export function BarChart({
  bars, height = 160, color = "var(--c1)", formatY, formatTick = compact, goal, label, labelEvery = 1, highlight,
}: {
  bars: Bar[];
  height?: number;
  color?: string;
  formatY: (v: number) => string;
  /** The y axis's labels (compact numbers unless given). */
  formatTick?: (v: number) => string;
  goal?: number;
  label: string;
  labelEvery?: number;
  highlight?: string;
}) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const pad = { top: 12, right: 8, bottom: 24, left: 40 };
  const w = Math.max(0, width - pad.left - pad.right);
  const h = height - pad.top - pad.bottom;
  const values = bars.map((b) => b.value ?? 0);
  const hi = Math.max(1, ...values, goal ?? 0) * 1.08;
  const slot = bars.length ? w / bars.length : 0;
  const bw = Math.max(2, Math.min(24, slot - 2));
  const sy = (v: number) => pad.top + h - (v / hi) * h;
  const yTicks = ticks(0, hi, 3);
  const hovered = hover != null ? bars[hover] : null;
  const r = Math.min(4, bw / 2);

  return (
    <div ref={ref} className="chart" style={{ height }}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={label} onPointerLeave={() => setHover(null)}>
          {yTicks.map((t) => (
            <g key={t}>
              <line x1={pad.left} x2={pad.left + w} y1={sy(t)} y2={sy(t)} className="gridline" />
              <text x={pad.left - 8} y={sy(t)} className="axis" textAnchor="end" dominantBaseline="middle">{formatTick(t)}</text>
            </g>
          ))}
          {bars.map((b, i) => {
            const x = pad.left + i * slot + (slot - bw) / 2;
            const v = b.value ?? 0;
            const top = sy(v);
            const barH = pad.top + h - top;
            const lit = hover === i || highlight === b.key;
            return (
              <g key={b.key}>
                {b.value != null && v > 0 && (
                  <path
                    d={barH > r
                      ? `M${x},${pad.top + h}V${top + r}Q${x},${top} ${x + r},${top}H${x + bw - r}Q${x + bw},${top} ${x + bw},${top + r}V${pad.top + h}Z`
                      : `M${x},${pad.top + h}V${top}H${x + bw}V${pad.top + h}Z`}
                    fill={color} opacity={hover != null && !lit ? 0.55 : 1}
                  />
                )}
                {i % labelEvery === 0 && (
                  <text x={x + bw / 2} y={height - 6} className="axis" textAnchor="middle">{b.label}</text>
                )}
                {/* The hit area is the whole slot, taller than the bar. */}
                <rect
                  x={pad.left + i * slot} y={pad.top} width={slot} height={h} fill="transparent"
                  onPointerEnter={() => setHover(i)} onFocus={() => setHover(i)} onBlur={() => setHover(null)} tabIndex={-1}
                  aria-label={`${b.label}: ${b.value == null ? "nothing" : formatY(b.value)}`}
                />
              </g>
            );
          })}
          {goal != null && goal > 0 && (
            <g>
              <line x1={pad.left} x2={pad.left + w} y1={sy(goal)} y2={sy(goal)} className="goal-line" />
            </g>
          )}
        </svg>
      )}
      {hovered && (
        <Tooltip x={pad.left + (hover! + 0.5) * slot} y={Math.max(0, sy(hovered.value ?? 0) - 58)} width={width}>
          <strong>{hovered.value == null ? "Nothing" : formatY(hovered.value)}</strong>
          <span>{hovered.detail ?? hovered.label}</span>
        </Tooltip>
      )}
    </div>
  );
}

// ---- Sleep stages ----

const STAGE_ROWS: { stage: SleepStage; label: string; color: string }[] = [
  { stage: "AWAKE", label: "Awake", color: "var(--stage-awake)" },
  { stage: "REM", label: "REM", color: "var(--stage-rem)" },
  { stage: "LIGHT", label: "Light", color: "var(--stage-light)" },
  { stage: "DEEP", label: "Deep", color: "var(--stage-deep)" },
];

/** The night's stages as rows (awake at the top, deep at the bottom), the way Oura draws them. */
export function Hypnogram({ stages, start, end, height = 150 }: { stages: StageSpan[]; start: number; end: number; height?: number }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const pad = { top: 6, right: 8, bottom: 22, left: 52 };
  const w = Math.max(0, width - pad.left - pad.right);
  const rowH = (height - pad.top - pad.bottom) / STAGE_ROWS.length;
  const sx = (t: number) => pad.left + ((t - start) / Math.max(1, end - start)) * w;
  const rowOf = (s: SleepStage) => Math.max(0, STAGE_ROWS.findIndex((r) => r.stage === (s === "ASLEEP" ? "LIGHT" : s)));
  const hours: number[] = [];
  const first = new Date(start);
  first.setMinutes(0, 0, 0);
  for (let t = first.getTime() + 3_600_000; t < end; t += 3_600_000) hours.push(t);
  const step = Math.ceil(hours.length / Math.max(1, Math.floor(w / 56)));
  const hovered = hover != null ? stages[hover] : null;
  return (
    <div ref={ref} className="chart" style={{ height }}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={`Sleep stages from ${clock(start)} to ${clock(end)}`} onPointerLeave={() => setHover(null)}>
          {STAGE_ROWS.map((row, i) => (
            <g key={row.stage}>
              <line x1={pad.left} x2={pad.left + w} y1={pad.top + (i + 0.5) * rowH} y2={pad.top + (i + 0.5) * rowH} className="gridline" />
              <text x={pad.left - 10} y={pad.top + (i + 0.5) * rowH} className="axis" textAnchor="end" dominantBaseline="middle">{row.label}</text>
            </g>
          ))}
          {hours.filter((_, i) => i % step === 0).map((t) => (
            <text key={t} x={sx(t)} y={height - 5} className="axis" textAnchor="middle">{new Date(t).toLocaleTimeString(undefined, { hour: "numeric" })}</text>
          ))}
          {stages.map((s, i) => {
            const row = rowOf(s.stage);
            const x = sx(s.start);
            const bw = Math.max(1.5, sx(s.end) - x - 1);
            return (
              <rect
                key={i} x={x} y={pad.top + row * rowH + rowH * 0.18} width={bw} height={rowH * 0.64} rx={Math.min(4, bw / 2)}
                fill={STAGE_ROWS[row].color} opacity={hover != null && hover !== i ? 0.5 : 1}
                onPointerEnter={() => setHover(i)}
              />
            );
          })}
        </svg>
      )}
      {hovered && (
        <Tooltip x={sx((hovered.start + hovered.end) / 2)} y={pad.top + rowOf(hovered.stage) * rowH - 50} width={width}>
          <strong>{STAGE_ROWS[rowOf(hovered.stage)].label} · {hm((hovered.end - hovered.start) / 60_000)}</strong>
          <span>{clock(hovered.start)} – {clock(hovered.end)}</span>
        </Tooltip>
      )}
    </div>
  );
}

export function StageLegend({ deep, rem, light, awake }: { deep: number; rem: number; light: number; awake: number }) {
  const items = [
    { label: "Deep", value: deep, color: "var(--stage-deep)" },
    { label: "REM", value: rem, color: "var(--stage-rem)" },
    { label: "Light", value: light, color: "var(--stage-light)" },
    { label: "Awake", value: awake, color: "var(--stage-awake)" },
  ];
  return (
    <ul className="legend">
      {items.map((i) => (
        <li key={i.label}><span className="legend-key" style={{ background: i.color }} />{i.label} <strong>{hm(i.value)}</strong></li>
      ))}
    </ul>
  );
}

// ---- Sparkline and meters ----

/** A small line of recent values, the latest marked. */
export function Sparkline({ values, color = "var(--c1)", height = 36, label }: { values: (number | null)[]; color?: string; height?: number; label: string }) {
  const [ref, width] = useWidth();
  const known = values.filter((v): v is number => v != null);
  if (known.length < 2) return <div ref={ref} style={{ height }} />;
  const lo = Math.min(...known);
  const hi = Math.max(...known);
  const sx = (i: number) => 4 + (i / Math.max(1, values.length - 1)) * (width - 8);
  const sy = (v: number) => 4 + (height - 8) * (hi === lo ? 0.5 : 1 - (v - lo) / (hi - lo));
  let d = "";
  let pen = false;
  values.forEach((v, i) => {
    if (v == null) { pen = false; return; }
    d += `${pen ? "L" : "M"}${sx(i).toFixed(1)},${sy(v).toFixed(1)}`;
    pen = true;
  });
  const lastIndex = values.map((v, i) => (v != null ? i : -1)).filter((i) => i >= 0).pop()!;
  return (
    <div ref={ref} style={{ height }} className="sparkline">
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={label}>
          <path d={d} fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
          <circle cx={sx(lastIndex)} cy={sy(values[lastIndex]!)} r={4} fill={color} stroke="var(--chart-surface)" strokeWidth={2} />
        </svg>
      )}
    </div>
  );
}

/** How far towards a target: a rounded bar, its track a quieter step of the same colour. */
export function Meter({ value, max, color = "var(--c1)", label, over = false }: { value: number; max: number; color?: string; label: string; over?: boolean }) {
  const fraction = max > 0 ? Math.min(1, value / max) : 0;
  return (
    <div className="meter" role="meter" aria-label={label} aria-valuenow={Math.round(value)} aria-valuemin={0} aria-valuemax={Math.round(max)} style={{ ["--meter-color" as string]: color }}>
      <div className={`meter-fill ${over && value > max ? "is-over" : ""}`} style={{ width: `${fraction * 100}%` }} />
    </div>
  );
}
