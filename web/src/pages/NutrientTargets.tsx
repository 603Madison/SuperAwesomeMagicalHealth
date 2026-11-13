import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { GROUPS, NUTRIENTS, infoOf, referenceText, withUnit, type NutrientKey } from "../lib/micros";
import type { NutrientTargets } from "../lib/types";
import { Button, ErrorNotice, Spinner, errorToast, toast, useLoad } from "../components/ui";

/**
 * Settings › Nutrient targets: each nutrient's reference intake for the person (by the formula
 * they chose for calories) and its upper limit, with their own target where they've set one. A
 * blank goes back to the reference; Reset does the same for one.
 */
export function NutrientTargetsPanel() {
  const { data, error, reload, setData } = useLoad(() => api.nutrientTargets(), []);
  const [values, setValues] = useState<Partial<Record<NutrientKey, string>>>({});
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (data) setValues(Object.fromEntries(data.items.filter((i) => i.own != null).map((i) => [i.key, String(i.own)])));
  }, [data]);
  if (error) return <ErrorNotice message={error} onRetry={reload} />;
  if (!data) return <Spinner />;

  const save = async (next: Partial<Record<NutrientKey, string>>) => {
    const own: Record<string, number> = {};
    for (const [key, text] of Object.entries(next)) {
      const t = text?.trim().replace(",", ".");
      if (!t) continue;
      const v = Number(t);
      if (!Number.isFinite(v) || v <= 0) { errorToast(new Error(`${infoOf(key as NutrientKey).label} needs a number above 0, or leave it blank`)); return; }
      own[key] = v;
    }
    setBusy(true);
    try {
      const saved: NutrientTargets = await api.saveNutrientTargets(own);
      setData(saved);
      toast("Saved");
    } catch (e) { errorToast(e); }
    setBusy(false);
  };

  const byKey = new Map(data.items.map((i) => [i.key, i]));
  return (
    <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(values); }}>
      <p className="muted">{data.basis}. Female or male values follow the formula you picked for calorie estimates, above. Set your own for any of them, or leave one blank for the reference.</p>
      {GROUPS.map((g) => (
        <fieldset key={g.id} className="micro-inputs nutrient-target-group">
          <legend>{g.label}</legend>
          <div className="nutrient-targets">
            {NUTRIENTS.filter((x) => x.group === g.id).map((x) => {
              const item = byKey.get(x.key);
              if (!item) return null;
              const mine = item.own != null;
              return (
                <div key={x.key} className="nutrient-target">
                  <span className="nutrient-target-name">
                    <span>{x.label}</span>
                    <small>{mine ? `Yours: ${withUnit(item.own!, x.unit)} · ${referenceText(item).toLowerCase()}` : referenceText(item)}</small>
                  </span>
                  <span className="micro-input-box">
                    <input
                      inputMode="decimal" value={values[x.key] ?? ""} placeholder={item.reference != null ? String(Math.round(item.reference * 100) / 100) : "None"}
                      aria-label={`Your ${x.aim === "AT_MOST" ? "limit" : "target"} for ${x.label.toLowerCase()} (${x.unit})`}
                      onChange={(e) => setValues({ ...values, [x.key]: e.target.value })}
                    />
                    <span className="muted">{x.unit}</span>
                  </span>
                  {mine
                    ? <Button type="button" variant="ghost" className="btn-small" disabled={busy} onClick={() => { const next = { ...values, [x.key]: "" }; setValues(next); void save(next); }}>Reset</Button>
                    : <span aria-hidden="true" />}
                </div>
              );
            })}
          </div>
        </fieldset>
      ))}
      <p className="muted small">From the {data.source}.</p>
      <div><Button variant="primary" busy={busy} type="submit">Save targets</Button></div>
    </form>
  );
}
