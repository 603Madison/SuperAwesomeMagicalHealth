import { useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeftRight, Layers, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, isoDate, num } from "../lib/format";
import { F, useFields } from "../lib/fields";
import {
  asChange, changeDraft, changeDue, kindWord, productPattern, putOnEntry, putOnOrder, recentPutOns, sinceLine, usualLine, wearable,
} from "../lib/continence";
import type { ContinenceEntry, ContinenceSettings, ContinenceWearing, ProductStats, Supply } from "../lib/types";
import { Button, Card, Field, Modal, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { FullnessPicker, ScaleRow } from "../components/FieldKit";

/*
 * What's being worn, and a change as one step: the "Wearing now" card at the top of the diary,
 * the Change sheet (what came off and how it was, what went on, when), and how each product goes.
 * Today's quick log and Up next open the same sheet. While viewing a share, nothing changes.
 */

/** Whether the diary keeps track of products for this person: the fields, or something already on. */
export function tracksProducts(has: (key: string) => boolean, wearing?: ContinenceWearing): boolean {
  return has(F.CONTINENCE_PROTECTION) || has(F.CONTINENCE_SUPPLIES) || wearing?.since != null;
}

/** The diary's "Wearing now": what's on, since when and the usual, with Change; or "What are you wearing?" with a picker. */
export function WearingCard({ wearing, supplies, settings, recent, readOnly, onChange, onPutOn }: {
  wearing?: ContinenceWearing; supplies: Supply[]; settings?: ContinenceSettings; recent: string[]; readOnly: boolean;
  onChange: () => void; onPutOn: (supplyId: string) => void;
}) {
  const now = Date.now();
  if (wearing?.supplyId) {
    const kind = kindWord(wearing.kind ?? supplies.find((s) => s.id === wearing.supplyId)?.kind);
    const usual = usualLine(wearing);
    return (
      <Card title="Wearing now" icon={<Layers size={18} />} className="wearing-card span-2">
        <div className="wearing">
          <div className="wearing-text">
            <p className="wearing-name">{wearing.name ?? "A product"}{kind && <span className="wearing-kind">{kind}</span>}</p>
            <p className="wearing-since">{sinceLine(wearing, clock, now)}</p>
            {usual && <p className="wearing-usual">{usual}{changeDue(wearing, now) ? ". Time for a check when you're ready." : "."}</p>}
            {!usual && changeDue(wearing, now) && <p className="wearing-usual">Time for a check when you're ready.</p>}
          </div>
          {!readOnly && <Button variant="primary" className="wearing-change" onClick={onChange}><ArrowLeftRight size={18} aria-hidden="true" /> Change</Button>}
        </div>
      </Card>
    );
  }
  if (readOnly && !wearing?.nothingOn) return null;
  const offer = putOnOrder(supplies, settings?.defaultSupplyId, recent);
  return (
    <Card title={wearing?.nothingOn ? "Wearing now" : "What are you wearing?"} icon={<Layers size={18} />} className="wearing-card span-2">
      {wearing?.nothingOn && (
        <div className="wearing-text">
          <p className="wearing-name">Nothing on for now</p>
          <p className="wearing-since">{sinceLine(wearing, clock, now)}</p>
        </div>
      )}
      {!readOnly && (offer.length > 0 ? (
        <>
          <p className="muted small wearing-ask">{wearing?.nothingOn ? "Put something on:" : "Tap what you have on, and the diary keeps the time."}</p>
          <div className="tag-list" role="group" aria-label="Put on">
            {offer.map((s) => <button key={s.id} type="button" className="chip" onClick={() => onPutOn(s.id!)}><Plus size={16} aria-hidden="true" /> {s.name}</button>)}
          </div>
        </>
      ) : (
        <p className="muted small">Add your pads, briefs or pull-ups in <Link to="/supplies">Supplies</Link>, and this keeps track of what you're wearing and for how long.</p>
      ))}
    </Card>
  );
}

const local = (ms: number) => { const d = new Date(ms); return `${isoDate(d)}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; };

/**
 * A change as one step, on one screen: what came off (what's on, already picked) and how it was,
 * what went on (the same again, picked; the usual and the last used first; or nothing for now),
 * and when. Saving logs one change with all of it.
 */
export function ChangeSheet({ initial, supplies, settings, recent, onClose, onSaved }: {
  initial: ContinenceEntry; supplies: Supply[]; settings?: ContinenceSettings; recent: string[]; onClose: () => void; onSaved: () => void;
}) {
  const { has } = useFields();
  const [e, setE] = useState(() => asChange(initial));
  const [busy, setBusy] = useState(false);
  const worn = wearable(supplies);
  // The one taken off may have been put away since: it's still shown.
  const offOptions = e.supplyId && !worn.some((s) => s.id === e.supplyId) ? [...worn, ...supplies.filter((s) => s.id === e.supplyId)] : worn;
  const onOptions = putOnOrder(supplies, settings?.defaultSupplyId, recent);
  const off = supplies.find((s) => s.id === e.supplyId);
  const lost = e.padWeightG != null && off?.dryWeightG != null ? Math.max(0, e.padWeightG - off.dryWeightG) : null;
  const how = !!e.supplyId && (has(F.CONTINENCE_PROTECTION) || has(F.CONTINENCE_CHANGES) || has(F.CONTINENCE_SKIN) || has(F.CONTINENCE_PAD_WEIGHT));
  const save = async () => {
    setBusy(true);
    try {
      await api.saveContinence({ ...e, kind: "PAD_CHANGE", putOnSupplyId: e.nothingOn ? undefined : e.putOnSupplyId });
      const on = supplies.find((s) => s.id === e.putOnSupplyId);
      toast(e.nothingOn ? "Change logged: nothing on for now" : on ? `Change logged: ${on.name} on` : "Change logged");
      onSaved();
      onClose();
    } catch (err) { errorToast(err); setBusy(false); }
  };
  const remove = async () => {
    try { await api.deleteContinence(e.id!); toast("Change deleted"); onSaved(); onClose(); } catch (err) { errorToast(err); }
  };
  const chip = (key: string, label: string, on: boolean, pick: () => void, tag?: string) => (
    <button key={key} type="button" role="radio" aria-checked={on} className={`chip ${on ? "is-on" : ""}`} onClick={pick}>
      {label}{tag && <span className="chip-tag">{tag}</span>}
    </button>
  );
  return (
    <Modal wide sheet title={initial.id ? "Edit change" : "Change"} onClose={onClose} footer={<>
      {initial.id && <Button variant="ghost" className="change-delete" onClick={remove}><Trash2 size={16} aria-hidden="true" /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button>
      <Button variant="primary" busy={busy} onClick={save}>Save</Button>
    </>}>
      <section className="change-step" aria-label="Taking off">
        <h3 className="change-label">Taking off</h3>
        {offOptions.length > 0 ? (
          <div className="tag-list" role="radiogroup" aria-label="Taking off">
            {offOptions.map((s) => chip(s.id!, s.name, e.supplyId === s.id, () => setE({ ...e, supplyId: s.id })))}
            {chip("none", "Nothing", !e.supplyId, () => setE({ ...e, supplyId: undefined, fullness: undefined, padWeightG: undefined }))}
          </div>
        ) : <p className="muted small">Add your pads, briefs or pull-ups in <Link to="/supplies">Supplies</Link> to pick them here.</p>}
      </section>

      {how && (
        <section className="subsection change-how" aria-label="How it was">
          <h3 className="change-label">How it was</h3>
          {has(F.CONTINENCE_PROTECTION) && (
            <FullnessPicker value={e.fullness} onChange={(fullness) => setE({ ...e, fullness, leakedThrough: fullness === "LEAKED" ? true : e.leakedThrough })} />
          )}
          {has(F.CONTINENCE_CHANGES) && (
            <div className="change-toggles">
              <Toggle label="It leaked through" checked={!!e.leakedThrough} onChange={(leakedThrough) => setE({ ...e, leakedThrough })} />
              <Toggle label="Changed clothes" checked={!!e.changedClothes} onChange={(changedClothes) => setE({ ...e, changedClothes })} />
              <Toggle label="Changed bedding" checked={!!e.changedBedding} onChange={(changedBedding) => setE({ ...e, changedBedding })} />
            </div>
          )}
          {has(F.CONTINENCE_SKIN) && <ScaleRow label="Skin" min={0} max={3} low="Fine" high="Sore" value={e.skin} onChange={(skin) => setE({ ...e, skin })} />}
          {has(F.CONTINENCE_PAD_WEIGHT) && (
            <Field label="Weighed (grams)" help={off?.dryWeightG != null ? `Dry it weighs ${num(off.dryWeightG)} g` : "Add the dry weight to the product to see what was lost"}>
              <input inputMode="decimal" value={e.padWeightG ?? ""} onChange={(ev) => setE({ ...e, padWeightG: ev.target.value ? Number(ev.target.value.replace(",", ".")) : undefined })} />
            </Field>
          )}
          {lost != null && <p className="loss-line" aria-live="polite">About <strong>{num(lost)} g</strong> lost (roughly {num(lost)} ml).</p>}
        </section>
      )}

      <section className="change-step" aria-label="Putting on">
        <h3 className="change-label">Putting on</h3>
        <div className="tag-list" role="radiogroup" aria-label="Putting on">
          {onOptions.map((s) => chip(s.id!, s.name, !e.nothingOn && e.putOnSupplyId === s.id, () => setE({ ...e, putOnSupplyId: s.id, nothingOn: false }), s.id === settings?.defaultSupplyId ? "usual" : undefined))}
          {chip("nothing", "Nothing for now", !!e.nothingOn, () => setE({ ...e, putOnSupplyId: undefined, nothingOn: true }))}
        </div>
      </section>

      <div className="change-when">
        <Field label="When"><input type="datetime-local" value={local(e.at)} onChange={(ev) => ev.target.value && setE({ ...e, at: new Date(ev.target.value).getTime() })} /></Field>
        <Field label="Note"><input value={e.note ?? ""} onChange={(ev) => setE({ ...e, note: ev.target.value || undefined })} placeholder="Optional" /></Field>
      </div>
    </Modal>
  );
}

/** The Change sheet from anywhere (Today's quick log, Up next): it loads what it needs itself. */
export function QuickChange({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const data = useLoad(() => Promise.all([
    api.supplies().catch(() => [] as Supply[]),
    api.continenceSettings().catch(() => undefined),
    api.continence(3).catch(() => [] as ContinenceEntry[]),
    api.continenceWearing().catch(() => ({}) as ContinenceWearing),
  ]), []);
  if (!data.data) {
    return <Modal title="Change" onClose={onClose}>{data.error ? <p className="muted">{data.error}</p> : <Spinner />}</Modal>;
  }
  const [supplies, settings, entries, wearing] = data.data;
  const recent = recentPutOns(entries);
  return <ChangeSheet initial={changeDraft(wearing, settings, supplies, recent)} supplies={supplies} settings={settings} recent={recent} onClose={onClose} onSaved={onSaved} />;
}

/** Logs "put on" alone (from "What are you wearing?"). */
export async function putOn(supplyId: string, supplies: Supply[]) {
  await api.saveContinence(putOnEntry(supplyId));
  toast(`${supplies.find((s) => s.id === supplyId)?.name ?? "It"} on`);
}

/** How each product goes: "Night briefs: usually 7 h, often full, leaked through 1 in 12". */
export function ProductsCard({ stats, className = "" }: { stats: ProductStats[]; className?: string }) {
  if (stats.length === 0) return null;
  return (
    <Card title="How your products go" icon={<Layers size={18} />} className={`products-card ${className}`}>
      <ul className="rows">
        {stats.map((p) => (
          <li key={p.supplyId} className="row">
            <div className="row-main">
              <div className="row-title">{p.name}</div>
              <div className="row-sub wraps">{productPattern(p)}</div>
            </div>
            <span className="muted small products-count">{p.changes} {p.changes === 1 ? "change" : "changes"}</span>
          </li>
        ))}
      </ul>
      <p className="muted small">From the last 90 days of changes. A continence nurse can help if a product often leaks through.</p>
    </Card>
  );
}
