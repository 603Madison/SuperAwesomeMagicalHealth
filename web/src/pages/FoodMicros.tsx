import { useState } from "react";
import { Leaf } from "lucide-react";
import type { FoodDay, Nutrients } from "../lib/types";
import { GROUPS, NUTRIENTS, carriedText, coverageText, rows as microRows, type NutrientKey, type Row, amountOf, withUnit } from "../lib/micros";
import { Meter } from "../components/charts";
import { Button, Card } from "../components/ui";
import { today } from "../lib/format";

/*
 * Vitamins and minerals on the Food page (lib/micros.ts does the sums and the words): the day's
 * section, what a food carries, and "More nutrients" when adding one. Only while they show
 * (useMicros: the field, and never in eating disorder recovery).
 */

/**
 * The day's vitamins, minerals, fibre and the finer fats against their targets: the main few, or
 * every one by group. A nutrient no food carried says "No data", never 0; a line says how many of
 * the day's foods came with any.
 */
export function MicrosCard({ day }: { day: FoodDay }) {
  const [all, setAll] = useState(false);
  const rows = microRows(day.totals, day.target, all);
  const coverage = coverageText(day.entries, day.date === today());
  return (
    <Card
      id="micros" title="Vitamins & minerals" icon={<Leaf size={18} />} className="span-2 micros-card"
      action={<Button variant="ghost" className="btn-small" aria-expanded={all} onClick={() => setAll(!all)}>{all ? "Show fewer" : "Show all"}</Button>}
    >
      {coverage ? <p className="muted small">{coverage}</p> : <p className="muted small">Nothing logged yet. Foods found by search or barcode often bring their vitamins and minerals.</p>}
      {all
        ? GROUPS.map((g) => (
            <div key={g.id} className="micro-group">
              <h4 className="micro-group-title">{g.label}</h4>
              <MicroRows rows={rows.filter((r) => r.info.group === g.id)} />
            </div>
          ))
        : <MicroRows rows={rows} />}
      {day.target.microBasis && <p className="muted small">Targets: {day.target.microBasis.charAt(0).toLowerCase() + day.target.microBasis.slice(1)}. Change them in Settings.</p>}
    </Card>
  );
}

function MicroRows({ rows }: { rows: Row[] }) {
  return (
    <div className="micros">
      {rows.map((r) => (
        <div key={r.info.key} className={`micro is-${r.state}`}>
          <div className="micro-head">
            <span className="micro-name">{r.info.label}</span>
            <span className={`micro-amount ${r.state === "none" ? "is-none" : ""}`}>{r.text}</span>
          </div>
          {/* A quiet bar: calm colours either way, limits included. */}
          {r.fraction != null
            ? <Meter value={r.amount ?? 0} max={r.target!.amount} color={r.info.aim === "AT_MOST" ? "var(--c1)" : "var(--c2)"} label={`${r.info.label} against ${r.info.aim === "AT_MOST" ? "your limit" : "your target"}`} />
            : <span className="micro-track" aria-hidden="true" />}
          {r.note && <span className="micro-note">{r.note}</span>}
        </div>
      ))}
    </div>
  );
}

/** What a food carries, in a line ("Fibre 2.7 g · Iron 4.2 mg · and 3 more"), for search results; nothing without any. */
export function CarriedLine({ nutrients }: { nutrients: Nutrients }) {
  const text = carriedText(nutrients, 3);
  return text ? <div className="row-sub micro-line">{text}</div> : null;
}

/** Everything a food's amount carries, for its details: a small list by group, or a line saying there's none. */
export function CarriedList({ nutrients }: { nutrients: Nutrients }) {
  const known = NUTRIENTS.filter((x) => amountOf(nutrients, x.key) != null);
  if (known.length === 0) return <p className="muted small">No vitamins or minerals came with this food.</p>;
  return (
    <details className="field-group micro-details">
      <summary>Vitamins & minerals <span className="muted">({known.length})</span></summary>
      <dl className="micro-list">
        {known.map((x) => (
          <div key={x.key}><dt>{x.label}</dt><dd>{withUnit(amountOf(nutrients, x.key)!, x.unit)}</dd></div>
        ))}
      </dl>
    </details>
  );
}

/**
 * "More nutrients": fibre, sugar, the fats, minerals and vitamins of a serving, each optional (a
 * blank stays unknown, never 0). [values] are as typed, by nutrient.
 */
export function MoreNutrients({ values, onChange, open }: { values: Partial<Record<NutrientKey, string>>; onChange: (v: Partial<Record<NutrientKey, string>>) => void; open?: boolean }) {
  const filled = Object.values(values).filter((v) => v?.trim()).length;
  return (
    <details className="field-group more-nutrients" open={open}>
      <summary>More nutrients{filled ? <span className="muted"> ({filled})</span> : null}</summary>
      <p className="muted small">All optional: fill in what the label says. Leave the rest blank.</p>
      {GROUPS.map((g) => (
        <fieldset key={g.id} className="micro-inputs">
          <legend>{g.label}</legend>
          {NUTRIENTS.filter((x) => x.group === g.id).map((x) => (
            <label key={x.key} className="micro-input">
              <span>{x.label}</span>
              <span className="micro-input-box">
                <input inputMode="decimal" value={values[x.key] ?? ""} onChange={(e) => onChange({ ...values, [x.key]: e.target.value })} aria-label={`${x.label} (${x.unit})`} />
                <span className="muted">{x.unit}</span>
              </span>
            </label>
          ))}
        </fieldset>
      ))}
    </details>
  );
}
