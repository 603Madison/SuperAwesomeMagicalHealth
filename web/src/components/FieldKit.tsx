import { useState } from "react";
import { Eye, EyeOff, Plus, Trash2, X } from "lucide-react";
import type { CustomField, CustomFieldType, Fullness, Module } from "../lib/types";
import { FULLNESS } from "../lib/types";
import { ALL_FIELDS, setField, useFieldStore } from "../lib/fields";
import { Button, Field, errorToast } from "./ui";

/*
 * Pieces every detailed log form shares: chip pickers that take the person's own words, 0–n
 * scales, the fullness picker, custom fields, and the settings editors for fields and lists.
 */

/** Chips to pick one or several from, with "Add your own" when [allowOwn]. */
export function ChipPicker({ label, options, value, onChange, multi = true, allowOwn = true, help }: {
  label: string; options: string[]; value: string[]; onChange: (v: string[]) => void; multi?: boolean; allowOwn?: boolean; help?: string;
}) {
  const [own, setOwn] = useState("");
  const all = [...new Set([...options, ...value])];
  const toggle = (o: string) => {
    if (multi) onChange(value.includes(o) ? value.filter((x) => x !== o) : [...value, o]);
    else onChange(value.includes(o) ? [] : [o]);
  };
  const add = () => {
    const t = own.trim();
    if (!t) return;
    if (!value.includes(t)) onChange(multi ? [...value, t] : [t]);
    setOwn("");
  };
  return (
    <Field label={label} help={help}>
      <div className="tag-list" role="group" aria-label={label}>
        {all.map((o) => <button key={o} type="button" className={`chip ${value.includes(o) ? "is-on" : ""}`} aria-pressed={value.includes(o)} onClick={() => toggle(o)}>{o}</button>)}
        {allowOwn && (
          <span className="chip-add">
            <input value={own} placeholder="Your own…" aria-label={`Add your own to ${label}`} onChange={(e) => setOwn(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} />
            {own.trim() && <button type="button" className="icon-btn" aria-label="Add" onClick={add}><Plus size={16} /></button>}
          </span>
        )}
      </div>
    </Field>
  );
}

/** One choice from a few labelled buttons; tapping the chosen one clears it. */
export function OneOf<T extends string | number>({ label, options, value, onChange, help }: {
  label: string; options: { id: T; label: string }[]; value: T | undefined; onChange: (v: T | undefined) => void; help?: string;
}) {
  return (
    <Field label={label} help={help}>
      <div className="tag-list" role="radiogroup" aria-label={label}>
        {options.map((o) => (
          <button key={String(o.id)} type="button" role="radio" aria-checked={value === o.id} className={`chip ${value === o.id ? "is-on" : ""}`}
            onClick={() => onChange(value === o.id ? undefined : o.id)}>{o.label}</button>
        ))}
      </div>
    </Field>
  );
}

/** A row of numbered buttons [min]..[max], with words at each end. */
export function ScaleRow({ label, value, onChange, min = 1, max = 5, low, high, help }: {
  label: string; value: number | undefined; onChange: (v: number | undefined) => void; min?: number; max?: number; low?: string; high?: string; help?: string;
}) {
  const steps = Array.from({ length: max - min + 1 }, (_, i) => min + i);
  // Long scales (0–10) are a slider: a row of eleven buttons doesn't fit a phone.
  if (steps.length > 7) {
    const v = value ?? min;
    return (
      <Field label={`${label}${value != null ? `: ${value}` : ""}`} help={help}>
        <input type="range" min={min} max={max} value={v} aria-label={label} aria-valuetext={value != null ? `${value}` : "Not set"}
          onChange={(e) => onChange(Number(e.target.value))} />
        {(low || high) && <div className="range-ends"><span>{low ?? min}</span><span>{high ?? max}</span></div>}
      </Field>
    );
  }
  return (
    <Field label={`${label}${value != null ? `: ${value}` : ""}`} help={help}>
      <div className="scale-row">
        {low && <span className="muted small">{low}</span>}
        <div className="scale" role="radiogroup" aria-label={label}>
          {steps.map((n) => (
            <button key={n} type="button" role="radio" aria-checked={value === n} aria-label={`${n}`} className={value === n ? "is-on" : ""}
              onClick={() => onChange(value === n ? undefined : n)}>{n}</button>
          ))}
        </div>
        {high && <span className="muted small">{high}</span>}
      </div>
    </Field>
  );
}

/** How full a pad or brief was, as filling levels you can see at a glance. */
export function FullnessPicker({ value, onChange, label = "How full it was" }: { value?: Fullness; onChange: (v: Fullness | undefined) => void; label?: string }) {
  return (
    <Field label={label}>
      <div className="fullness" role="radiogroup" aria-label={label}>
        {FULLNESS.map((f) => (
          <button key={f.id} type="button" role="radio" aria-checked={value === f.id} className={`fullness-level ${value === f.id ? "is-on" : ""}`}
            onClick={() => onChange(value === f.id ? undefined : f.id)}>
            <span className="fullness-pad" aria-hidden="true"><span className="fullness-fill" style={{ height: `${Math.min(100, f.level * 22)}%` }} />{f.id === "LEAKED" && <span className="fullness-drop" />}</span>
            <span>{f.label}</span>
          </button>
        ))}
      </div>
    </Field>
  );
}

/** Inputs for the person's own fields, values kept as strings by key. */
export function CustomFieldInputs({ fields, values, onChange }: { fields: CustomField[]; values: Record<string, string>; onChange: (v: Record<string, string>) => void }) {
  if (fields.length === 0) return null;
  const set = (key: string, v: string | undefined) => {
    const next = { ...values };
    if (v == null || v === "") delete next[key]; else next[key] = v;
    onChange(next);
  };
  return (
    <>
      {fields.map((f) => {
        const v = values[f.key];
        switch (f.type) {
          case "NUMBER":
            return <Field key={f.key} label={f.unit ? `${f.label} (${f.unit})` : f.label}><input inputMode="decimal" value={v ?? ""} onChange={(e) => set(f.key, e.target.value)} /></Field>;
          case "SCALE":
            return <ScaleRow key={f.key} label={f.label} min={f.min} max={f.max} value={v != null ? Number(v) : undefined} onChange={(n) => set(f.key, n?.toString())} />;
          case "CHOICE":
            return <OneOf key={f.key} label={f.label} options={f.options.map((o) => ({ id: o, label: o }))} value={v} onChange={(o) => set(f.key, o)} />;
          case "MULTI":
            return <ChipPicker key={f.key} label={f.label} options={f.options} allowOwn={false} value={v ? v.split("|") : []} onChange={(list) => set(f.key, list.join("|"))} />;
          case "YES_NO":
            return <OneOf key={f.key} label={f.label} options={[{ id: "yes", label: "Yes" }, { id: "no", label: "No" }]} value={v} onChange={(o) => set(f.key, o)} />;
          default:
            return <Field key={f.key} label={f.label}><input value={v ?? ""} onChange={(e) => set(f.key, e.target.value)} /></Field>;
        }
      })}
    </>
  );
}

const TYPE_LABELS: { id: CustomFieldType; label: string }[] = [
  { id: "TEXT", label: "Words" }, { id: "NUMBER", label: "A number" }, { id: "SCALE", label: "A scale" },
  { id: "CHOICE", label: "One of a list" }, { id: "MULTI", label: "Several of a list" }, { id: "YES_NO", label: "Yes or no" },
];

/** Making your own fields: a name, a kind, and the choices for lists. */
export function CustomFieldsBuilder({ fields, onChange }: { fields: CustomField[]; onChange: (f: CustomField[]) => void }) {
  const [draft, setDraft] = useState<CustomField>({ key: "", label: "", type: "TEXT", options: [], min: 0, max: 10 });
  const [options, setOptions] = useState("");
  const add = () => {
    const label = draft.label.trim();
    if (!label) return;
    const key = `own.${label.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "")}-${Date.now().toString(36).slice(-4)}`;
    onChange([...fields, { ...draft, label, key, options: options.split(",").map((o) => o.trim()).filter(Boolean) }]);
    setDraft({ key: "", label: "", type: "TEXT", options: [], min: 0, max: 10 });
    setOptions("");
  };
  return (
    <div className="form-stack">
      {fields.length > 0 && (
        <ul className="rows">
          {fields.map((f) => (
            <li key={f.key} className="row">
              <div className="row-main"><div className="row-title">{f.label}</div><div className="row-sub">{TYPE_LABELS.find((t) => t.id === f.type)?.label}{f.options.length ? `: ${f.options.join(", ")}` : ""}{f.type === "SCALE" ? ` ${f.min}–${f.max}` : ""}</div></div>
              <div className="row-actions"><Button variant="ghost" aria-label={`Remove ${f.label}`} onClick={() => onChange(fields.filter((x) => x.key !== f.key))}><Trash2 size={16} /></Button></div>
            </li>
          ))}
        </ul>
      )}
      <div className="two-col">
        <Field label="A field of your own"><input value={draft.label} placeholder="e.g. Fluids before bed" onChange={(e) => setDraft({ ...draft, label: e.target.value })} /></Field>
        <Field label="What it holds">
          <select value={draft.type} onChange={(e) => setDraft({ ...draft, type: e.target.value as CustomFieldType })}>
            {TYPE_LABELS.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select>
        </Field>
      </div>
      {(draft.type === "CHOICE" || draft.type === "MULTI") && <Field label="Choices" help="Separated by commas"><input value={options} onChange={(e) => setOptions(e.target.value)} /></Field>}
      {draft.type === "SCALE" && (
        <div className="two-col">
          <Field label="From"><input inputMode="numeric" value={draft.min} onChange={(e) => setDraft({ ...draft, min: Number(e.target.value) || 0 })} /></Field>
          <Field label="To"><input inputMode="numeric" value={draft.max} onChange={(e) => setDraft({ ...draft, max: Number(e.target.value) || 10 })} /></Field>
        </div>
      )}
      {draft.type === "NUMBER" && <Field label="Unit" help="Optional"><input value={draft.unit ?? ""} onChange={(e) => setDraft({ ...draft, unit: e.target.value || undefined })} /></Field>}
      <Button onClick={add} disabled={!draft.label.trim()}><Plus size={16} /> Add the field</Button>
    </div>
  );
}

/** A list of the person's own words (triggers, places, symptoms…), to add to or take from. */
export function WordList({ label, words, onChange, placeholder, help }: { label: string; words: string[]; onChange: (w: string[]) => void; placeholder?: string; help?: string }) {
  const [text, setText] = useState("");
  const add = () => {
    const t = text.trim();
    if (t && !words.includes(t)) onChange([...words, t]);
    setText("");
  };
  return (
    <Field label={label} help={help}>
      <div className="tag-list">
        {words.map((w) => (
          <span key={w} className="chip is-static">{w}
            <button type="button" className="chip-x" aria-label={`Remove ${w}`} onClick={() => onChange(words.filter((x) => x !== w))}><X size={14} /></button>
          </span>
        ))}
        <span className="chip-add">
          <input value={text} placeholder={placeholder ?? "Add…"} aria-label={`Add to ${label}`} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} />
          {text.trim() && <button type="button" className="icon-btn" aria-label="Add" onClick={add}><Plus size={16} /></button>}
        </span>
      </div>
    </Field>
  );
}

/**
 * Each of a feature's fields with whether it shows, and why: the person's conditions, the
 * defaults, or their own choice. Changing one saves straight away.
 */
export function FieldsEditor({ module, keys }: { module: Module; keys?: string[] }) {
  const layout = useFieldStore((s) => s.layout);
  const [busy, setBusy] = useState<string | null>(null);
  const fields = ALL_FIELDS.filter((f) => f.module === module && (!keys || keys.includes(f.key)));
  if (!layout) return <p className="muted">Loading…</p>;
  const save = async (key: string, on: boolean) => {
    setBusy(key);
    try {
      await setField(key, on);
    } catch (e) {
      errorToast(e);
    } finally {
      setBusy(null);
    }
  };
  return (
    <ul className="rows field-rows">
      {fields.map((f) => {
        const on = layout.effective.includes(f.key);
        const reason = layout.because[f.key];
        return (
          <li key={f.key} className="row">
            <div className="row-main">
              <div className="row-title">{f.label}</div>
              <div className="row-sub">{on ? reasonText(reason) : layout.hide.includes(f.key) ? "Hidden by you" : "Not showing"}</div>
            </div>
            <div className="row-actions">
              <Button variant={on ? "tonal" : "ghost"} busy={busy === f.key} aria-pressed={on} aria-label={`${on ? "Hide" : "Show"} ${f.label}`} onClick={() => save(f.key, !on)}>
                {on ? <Eye size={16} /> : <EyeOff size={16} />} {on ? "Showing" : "Hidden"}
              </Button>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

function reasonText(reason?: string): string {
  if (!reason || reason === "default") return "Shown to start";
  if (reason === "you") return "Shown by you";
  return `For ${reason}`;
}
