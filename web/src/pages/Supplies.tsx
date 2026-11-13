import { useState } from "react";
import { Link } from "react-router-dom";
import { History, Minus, Package, Pencil, Plus, ShoppingBag } from "lucide-react";
import { api } from "../lib/api";
import { num, shortDate, when } from "../lib/format";
import type { Supply, SupplyAdjustment, SupplyCategory, SupplyKind, SupplyStatus } from "../lib/types";
import { SUPPLY_KINDS, supplyKindLabel } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { Button, Card, CardAction, Empty, ErrorNotice, Field, Modal, PageHeader, Spinner, Stat, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { useModules } from "../App";

const CATEGORY_LABEL: Record<SupplyCategory, string> = { CONTINENCE: "Bladder and bowel", PERIOD: "Periods", OTHER: "Other" };

const blank = (kind: SupplyKind = "PAD"): Supply => ({ name: "", kind, onHand: 0, reusable: false, active: true });

/** Pads, briefs, period products and the rest: how many are on hand, how fast they go, and when to restock. */
export function Supplies() {
  const readOnly = useReadOnly();
  const status = useLoad(() => api.supplyStatus(), []);
  const [editing, setEditing] = useState<Supply | null>(null);
  const [restock, setRestock] = useState<SupplyStatus | null>(null);
  const [history, setHistory] = useState<Supply | null>(null);
  const list = status.data ?? [];
  const on = useModules();
  const inCategory = (c: SupplyCategory) => list.filter((s) => SUPPLY_KINDS.find((k) => k.id === s.supply.kind)?.category === c);
  const sections: Section[] = (["CONTINENCE", "PERIOD", "OTHER"] as SupplyCategory[]).map((category) => {
    const items = inCategory(category);
    return {
      id: category.toLowerCase(), label: CATEGORY_LABEL[category],
      when: category === "PERIOD" ? on.includes("CYCLE") || items.length > 0 : category === "CONTINENCE" ? on.includes("CONTINENCE") || items.length > 0 : true,
      node: items.length > 0 && (
        <section className="block span-2" aria-label={CATEGORY_LABEL[category]}>
          <h2 className="section-title">{CATEGORY_LABEL[category]}</h2>
          <div className="grid grid-2">
            {items.map((s) => (
              <Card key={s.supply.id} className={`supply-card ${s.low ? "is-low" : ""}`} title={<>{s.supply.name}{s.low && <span className="badge badge-warn">Running low</span>}</>} icon={<Package size={18} />}
                action={!readOnly && !s.supply.reusable && <CardAction label={`Restock ${s.supply.name}`} onClick={() => setRestock(s)}><ShoppingBag size={18} /></CardAction>}>
                <p className="muted small">{[supplyKindLabel(s.supply.kind), s.supply.brand, s.supply.size, s.supply.absorbency].filter(Boolean).join(" · ")}</p>
                <div className="stats">
                  <Stat label="On hand" value={s.supply.reusable ? "Reusable" : num(s.supply.onHand)} />
                  {!s.supply.reusable && <Stat label="Used a day" value={s.perDay != null ? num(s.perDay, 1) : "–"} sub="last two weeks" />}
                  {!s.supply.reusable && <Stat label="Lasts" value={s.daysLeft != null ? `${s.daysLeft} days` : "–"} sub={s.lastRestocked ? `restocked ${shortDate(s.lastRestocked)}` : undefined} />}
                </div>
                {!readOnly && (
                  <div className="button-row">
                    {!s.supply.reusable && <Button variant="ghost" onClick={async () => {
                      try { await api.adjustSupply(s.supply.id!, { at: Date.now(), delta: -1, reason: "USED" }); status.reload(); } catch (e) { errorToast(e); }
                    }}><Minus size={16} /> Used one</Button>}
                    <Button variant="ghost" onClick={() => setHistory(s.supply)}><History size={16} /> History</Button>
                    <Button variant="ghost" onClick={() => setEditing(s.supply)}><Pencil size={16} /> Edit</Button>
                  </div>
                )}
              </Card>
            ))}
          </div>
        </section>
      ),
    };
  });
  const arranged = useArrange(layoutKey("supplies"), "Supplies", sections);
  return (
    <div className="page">
      <PageHeader title="Supplies" subtitle="What you have on hand, how fast it goes, and when to buy more"
        actions={<>
          {!readOnly && <Button variant="primary" onClick={() => setEditing(blank())}><Plus size={18} /> Add a product</Button>}
          {arranged.button}
        </>} />
      {status.error && <ErrorNotice message={status.error} onRetry={status.reload} />}
      {status.loading && !status.data && <Spinner />}
      {status.data && list.length === 0 && (
        <Card><Empty icon={<Package size={32} />} title="Nothing here yet">
          Add the pads, briefs, liners or period products you use: the diary counts them down, with a nudge before you run out.
        </Empty></Card>
      )}
      {arranged.render()}
      <p className="muted small">Pad and brief changes in the <Link to="/bladder">Bladder &amp; bowel</Link> diary, and period products on <Link to="/cycle">Cycle</Link> days, are taken off the count as you log them.</p>
      {editing && <SupplyDialog supply={editing} onClose={() => setEditing(null)} onSaved={status.reload} />}
      {restock && <RestockDialog status={restock} onClose={() => setRestock(null)} onSaved={status.reload} />}
      {history && <HistoryDialog supply={history} onClose={() => setHistory(null)} />}
    </div>
  );
}

function SupplyDialog({ supply, onClose, onSaved }: { supply: Supply; onClose: () => void; onSaved: () => void }) {
  const [s, setS] = useState(supply);
  const [busy, setBusy] = useState(false);
  const numberOrUndefined = (v: string) => (v.trim() ? Number(v.replace(",", ".")) : undefined);
  const save = async () => {
    if (!s.name.trim()) return;
    setBusy(true);
    try { await api.saveSupply({ ...s, name: s.name.trim() }); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={supply.id ? "Edit product" : "Add a product"} onClose={onClose} footer={<>
      {supply.id && <Button variant="ghost" onClick={async () => { try { await api.saveSupply({ ...s, active: false }); toast("Put away"); onSaved(); onClose(); } catch (e) { errorToast(e); } }}>Put away</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!s.name.trim()} onClick={save}>Save</Button>
    </>}>
      <div className="two-col">
        <Field label="Name"><input autoFocus value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} placeholder="e.g. Night pull-ups" /></Field>
        <Field label="Kind">
          <select value={s.kind} onChange={(e) => setS({ ...s, kind: e.target.value as SupplyKind, reusable: ["CUP", "REUSABLE_UNDERWEAR", "PERIOD_UNDERWEAR"].includes(e.target.value) })}>
            {(["CONTINENCE", "PERIOD", "OTHER"] as SupplyCategory[]).map((c) => (
              <optgroup key={c} label={CATEGORY_LABEL[c]}>{SUPPLY_KINDS.filter((k) => k.category === c).map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}</optgroup>
            ))}
          </select>
        </Field>
      </div>
      <div className="two-col">
        <Field label="Brand"><input value={s.brand ?? ""} onChange={(e) => setS({ ...s, brand: e.target.value || undefined })} /></Field>
        <Field label="Size"><input value={s.size ?? ""} onChange={(e) => setS({ ...s, size: e.target.value || undefined })} placeholder="e.g. M" /></Field>
      </div>
      <div className="two-col">
        <Field label="Absorbency" help="As the pack says"><input value={s.absorbency ?? ""} onChange={(e) => setS({ ...s, absorbency: e.target.value || undefined })} placeholder="e.g. Maxi, 6 drops, overnight" /></Field>
        <Field label="Dry weight (g)" help="For weighing used ones"><input inputMode="decimal" value={s.dryWeightG ?? ""} onChange={(e) => setS({ ...s, dryWeightG: numberOrUndefined(e.target.value) })} /></Field>
      </div>
      <Toggle label="Reusable" help="Not counted down" checked={s.reusable} onChange={(reusable) => setS({ ...s, reusable })} />
      {!s.reusable && (
        <div className="three-col">
          <Field label="On hand"><input inputMode="numeric" value={s.onHand} onChange={(e) => setS({ ...s, onHand: Number(e.target.value) || 0 })} /></Field>
          <Field label="In a pack"><input inputMode="numeric" value={s.perPack ?? ""} onChange={(e) => setS({ ...s, perPack: numberOrUndefined(e.target.value) })} /></Field>
          <Field label="Warn at" help="Default: about a week's worth"><input inputMode="numeric" value={s.lowAt ?? ""} onChange={(e) => setS({ ...s, lowAt: numberOrUndefined(e.target.value) })} /></Field>
        </div>
      )}
      <Field label="Notes"><input value={s.notes ?? ""} onChange={(e) => setS({ ...s, notes: e.target.value || undefined })} placeholder="e.g. where to buy, what fits best" /></Field>
    </Modal>
  );
}

/** "Bought 2 packs", or a count correction after checking the cupboard. */
function RestockDialog({ status, onClose, onSaved }: { status: SupplyStatus; onClose: () => void; onSaved: () => void }) {
  const s = status.supply;
  const [packs, setPacks] = useState(1);
  const [count, setCount] = useState<string>("");
  const perPack = s.perPack ?? 0;
  const adjust = async (a: SupplyAdjustment, message: string) => {
    try { await api.adjustSupply(s.id!, a); toast(message); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal title={`Restock ${s.name}`} onClose={onClose}>
      {perPack > 0 ? (
        <div className="form-stack">
          <Field label="Packs bought">
            <div className="stepper">
              <Button variant="tonal" aria-label="One fewer pack" onClick={() => setPacks(Math.max(1, packs - 1))}><Minus size={16} /></Button>
              <strong>{packs}</strong>
              <Button variant="tonal" aria-label="One more pack" onClick={() => setPacks(packs + 1)}><Plus size={16} /></Button>
            </div>
          </Field>
          <Button variant="primary" onClick={() => adjust({ at: Date.now(), delta: packs * perPack, reason: "RESTOCKED" }, `Added ${packs * perPack}`)}>
            Bought {packs} {packs === 1 ? "pack" : "packs"} ({packs * perPack})
          </Button>
        </div>
      ) : (
        <Field label="How many you bought"><input inputMode="numeric" value={count} onChange={(e) => setCount(e.target.value)} /></Field>
      )}
      {perPack === 0 && <Button variant="primary" disabled={!Number(count)} onClick={() => adjust({ at: Date.now(), delta: Number(count), reason: "RESTOCKED" }, `Added ${count}`)}>Add them</Button>}
      <hr className="rule" />
      <CountFix status={status} onDone={(n) => adjust({ at: Date.now(), delta: n - s.onHand, reason: "COUNTED" }, "Count updated")} />
    </Modal>
  );
}

function CountFix({ status, onDone }: { status: SupplyStatus; onDone: (n: number) => void }) {
  const [n, setN] = useState(String(status.supply.onHand));
  return (
    <div className="inline-form">
      <Field label="Or set the count" help="After checking the cupboard"><input inputMode="numeric" value={n} onChange={(e) => setN(e.target.value)} /></Field>
      <Button onClick={() => onDone(Number(n) || 0)}>Set</Button>
    </div>
  );
}

const REASON: Record<string, string> = { USED: "Used", RESTOCKED: "Restocked", COUNTED: "Counted", GAVE_AWAY: "Gave away" };

function HistoryDialog({ supply, onClose }: { supply: Supply; onClose: () => void }) {
  const log = useLoad(() => api.supplyLog(supply.id!, 90), [supply.id]);
  return (
    <Modal title={`${supply.name}: the last 90 days`} onClose={onClose}>
      {log.loading && <Spinner />}
      {log.data && log.data.length === 0 && <p className="muted">Nothing yet.</p>}
      {log.data && log.data.length > 0 && (
        <ul className="rows">
          {[...log.data].sort((a, b) => b.at - a.at).map((a, i) => (
            <li key={a.id ?? i} className="row">
              <div className="row-main"><div className="row-title">{REASON[a.reason]} {a.delta > 0 ? `+${a.delta}` : a.delta}</div><div className="row-sub">{when(a.at)}{a.note ? ` · ${a.note}` : ""}</div></div>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}

/** On Today: anything running low. */
export function LowSuppliesCard({ items }: { items: SupplyStatus[] }) {
  if (items.length === 0) return null;
  return (
    <Card title="Running low" icon={<Package size={18} />} link={{ to: "/supplies", label: "Open Supplies" }}>
      <ul className="rows">
        {items.map((s) => (
          <li key={s.supply.id} className="row">
            <div className="row-main">
              <div className="row-title">{s.supply.name}</div>
              <div className="row-sub">{s.supply.onHand} left{s.daysLeft != null ? ` · about ${s.daysLeft} ${s.daysLeft === 1 ? "day" : "days"}` : ""}</div>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}
