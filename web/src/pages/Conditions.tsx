import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Check, ClipboardList, Pencil, Plus, Search, Sparkles, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { session } from "../lib/session";
import { loadFields, ALL_FIELDS, fieldLabel } from "../lib/fields";
import type { ConditionDefinition, ConditionKind, ConditionStatus, Module, MyCondition } from "../lib/types";
import { CONDITION_KINDS, CONDITION_STATUSES } from "../lib/types";
import { FEATURES, featureName } from "../components/features";
import { Button, Card, Empty, ErrorNotice, Field, Modal, PageHeader, Spinner, errorToast, toast, useLoad } from "../components/ui";

/**
 * What you're tracking: the conditions and goals you live with or want to keep an eye on. Each
 * turns on the features and the fields in them that fit, so the app shows what helps you and
 * nothing else. Private, and kept encrypted.
 */
export function Conditions() {
  const catalog = useLoad(() => api.conditionCatalog(), []);
  const mine = useLoad(() => api.conditions(), []);
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState<ConditionKind | "ALL">("ALL");
  const [adding, setAdding] = useState<MyCondition | null>(null);
  const [own, setOwn] = useState(false);
  const picked = mine.data?.conditions ?? [];

  const save = async (list: MyCondition[], message: string) => {
    try {
      const saved = await api.saveConditions({ conditions: list });
      mine.setData(saved);
      toast(message);
      // Picking a condition can turn features on, and changes which fields show.
      void session.loadProfile();
      void loadFields(true);
    } catch (e) { errorToast(e); }
  };

  const found = useMemo(() => {
    const words = query.trim().toLowerCase();
    return (catalog.data ?? []).filter((c) =>
      (kind === "ALL" || c.kind === kind) &&
      (!words || [c.name, c.group, c.about, ...c.aliases].some((t) => t.toLowerCase().includes(words))));
  }, [catalog.data, query, kind]);
  const groups = useMemo(() => {
    const byGroup = new Map<string, ConditionDefinition[]>();
    found.forEach((c) => byGroup.set(c.group, [...(byGroup.get(c.group) ?? []), c]));
    return [...byGroup.entries()];
  }, [found]);

  const start = (c: ConditionDefinition) =>
    setAdding({ id: c.id, name: c.name, kind: c.kind, modules: [], fields: [], status: c.kind === "CONDITION" ? "DIAGNOSED" : undefined });

  return (
    <div className="page">
      <PageHeader title="What you're tracking" subtitle="Your conditions and goals decide what the app shows, so you see what helps you and nothing else"
        actions={<Button variant="primary" onClick={() => setOwn(true)}><Plus size={18} /> Your own</Button>} />
      {(catalog.error || mine.error) && <ErrorNotice message={catalog.error ?? mine.error ?? ""} onRetry={() => { catalog.reload(); mine.reload(); }} />}

      <Card title="Yours" icon={<ClipboardList size={18} />}>
        {mine.loading && !mine.data ? <Spinner /> : picked.length === 0 ? (
          <p className="muted">Nothing yet. Search below, or add your own. You can change these any time, and the features you already use stay on.</p>
        ) : (
          <ul className="rows">
            {picked.map((c) => {
              const def = catalog.data?.find((d) => d.id === c.id);
              const modules = def?.modules ?? c.modules;
              return (
                <li key={c.id} className="row">
                  <div className="row-icon"><Check size={18} /></div>
                  <div className="row-main">
                    <div className="row-title">{c.name}</div>
                    <div className="row-sub">
                      {[c.status ? CONDITION_STATUSES.find((s) => s.id === c.status)?.label : null, c.since ? `since ${sinceText(c.since)}` : null, c.notes].filter(Boolean).join(" · ") || CONDITION_KINDS.find((k) => k.id === c.kind)?.label.replace(/s$/, "")}
                    </div>
                    {modules.length > 0 && <div className="tag-list tight">{modules.map((m) => <span key={m} className="chip is-static small">{featureName(m)}</span>)}</div>}
                  </div>
                  <div className="row-actions">
                    <Button variant="ghost" aria-label={`Edit ${c.name}`} onClick={() => setAdding(c)}><Pencil size={16} /></Button>
                    <Button variant="ghost" aria-label={`Remove ${c.name}`} onClick={() => save(picked.filter((x) => x.id !== c.id), `${c.name} removed`)}><Trash2 size={16} /></Button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
        {picked.length > 0 && <p className="muted small">Removing one hides the fields it brought, but not the features: turn those off in Settings › Features if you like. Nothing you've logged is lost.</p>}
      </Card>

      <div className="search-box conditions-search">
        <Search size={18} aria-hidden="true" />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search: overactive bladder, endometriosis, ADHD, losing weight…" aria-label="Search conditions and goals" />
      </div>
      <div className="tabs" role="tablist" aria-label="Kind">
        {[{ id: "ALL" as const, label: "Everything" }, ...CONDITION_KINDS].map((k) => (
          <button key={k.id} role="tab" aria-selected={kind === k.id} className={kind === k.id ? "is-active" : ""} onClick={() => setKind(k.id)}>{k.label}</button>
        ))}
      </div>
      {catalog.loading && !catalog.data && <Spinner />}
      {catalog.data && groups.length === 0 && (
        <Card><Empty icon={<Search size={32} />} title="Nothing by that name">Add it as your own: you choose which features and fields it brings.</Empty></Card>
      )}
      {groups.map(([group, items]) => (
        <section key={group} className="condition-group">
          <h3 className="section-title">{group}</h3>
          <div className="grid grid-3">
            {items.map((c) => {
              const has = picked.some((p) => p.id === c.id);
              return (
                <article key={c.id} className={`panel-card condition-card ${has ? "is-picked" : ""}`}>
                  <div className="condition-head">
                    <h4>{c.name}</h4>
                    {has ? <span className="badge badge-accent"><Check size={14} /> Tracking</span>
                      : <Button variant="tonal" onClick={() => start(c)} aria-label={`Add ${c.name}`}><Plus size={16} /> Add</Button>}
                  </div>
                  <p className="muted small">{c.about}</p>
                  {c.modules.length > 0 && <div className="tag-list tight">{c.modules.map((m) => <span key={m} className="chip is-static small">{featureName(m)}</span>)}</div>}
                  {c.fields.length > 0 && <p className="muted small">Shows: {c.fields.slice(0, 4).map((f) => lowerFirst(fieldLabel(f))).join(", ")}{c.fields.length > 4 ? ` and ${c.fields.length - 4} more` : ""}</p>}
                </article>
              );
            })}
          </div>
        </section>
      ))}

      {adding && (
        <ConditionDialog condition={adding} isNew={!picked.some((p) => p.id === adding.id)} onClose={() => setAdding(null)}
          onSave={(c) => save([...picked.filter((p) => p.id !== c.id), c], picked.some((p) => p.id === c.id) ? "Saved" : `${c.name} added`)} />
      )}
      {own && <OwnDialog onClose={() => setOwn(false)} onSave={(c) => save([...picked, c], `${c.name} added`)} />}
    </div>
  );
}

/** "How urgent it was" → "how urgent it was", but "LH level" stays as it is. */
const lowerFirst = (s: string) => (s.length > 1 && s[1] === s[1].toUpperCase() && /[A-Z]/.test(s[1]) ? s : s.charAt(0).toLowerCase() + s.slice(1));

/** "2023-04" → "April 2023"; anything else as written. */
function sinceText(since: string): string {
  const m = since.match(/^(\d{4})-(\d{2})/);
  if (!m) return since;
  return new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

function ConditionDialog({ condition, isNew, onClose, onSave }: { condition: MyCondition; isNew: boolean; onClose: () => void; onSave: (c: MyCondition) => void }) {
  const [c, setC] = useState(condition);
  return (
    <Modal title={isNew ? `Add ${condition.name}` : condition.name} onClose={onClose} footer={<>
      <Button variant="ghost" onClick={onClose}>Cancel</Button>
      <Button variant="primary" onClick={() => { onSave(c); onClose(); }}>{isNew ? "Add" : "Save"}</Button>
    </>}>
      {c.kind === "CONDITION" && (
        <Field label="Where you are with it">
          <div className="tag-list" role="radiogroup" aria-label="Where you are with it">
            {CONDITION_STATUSES.map((s) => (
              <button key={s.id} type="button" role="radio" aria-checked={c.status === s.id} className={`chip ${c.status === s.id ? "is-on" : ""}`}
                onClick={() => setC({ ...c, status: c.status === s.id ? undefined : (s.id as ConditionStatus) })}>{s.label}</button>
            ))}
          </div>
        </Field>
      )}
      <Field label="Since" help="Optional: a month is plenty"><input type="month" value={c.since?.slice(0, 7) ?? ""} onChange={(e) => setC({ ...c, since: e.target.value || undefined })} /></Field>
      <Field label="Notes" help="For you, and your report if you include it"><textarea rows={2} value={c.notes ?? ""} onChange={(e) => setC({ ...c, notes: e.target.value || undefined })} /></Field>
    </Modal>
  );
}

/** Your own condition or goal: its name, and which features and fields it should bring. */
function OwnDialog({ onClose, onSave }: { onClose: () => void; onSave: (c: MyCondition) => void }) {
  const [name, setName] = useState("");
  const [kind, setKind] = useState<ConditionKind>("CONDITION");
  const [modules, setModules] = useState<Module[]>([]);
  const [fields, setFields] = useState<string[]>([]);
  const choosable = ALL_FIELDS.filter((f) => modules.includes(f.module));
  const save = () => {
    const n = name.trim();
    if (!n) return;
    onSave({ id: `custom:${n.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`, name: n, kind, modules, fields });
    onClose();
  };
  return (
    <Modal wide title="Your own" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!name.trim()} onClick={save}>Add</Button></>}>
      <div className="two-col">
        <Field label="Name"><input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Ehlers-Danlos, training for a 10k" /></Field>
        <Field label="It's a">
          <select value={kind} onChange={(e) => setKind(e.target.value as ConditionKind)}>
            {CONDITION_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label.replace(/s$/, "").toLowerCase()}</option>)}
          </select>
        </Field>
      </div>
      <Field label="Features it needs">
        <div className="tag-list">
          {FEATURES.map((f) => (
            <button key={f.id} type="button" aria-pressed={modules.includes(f.id)} className={`chip ${modules.includes(f.id) ? "is-on" : ""}`}
              onClick={() => setModules(modules.includes(f.id) ? modules.filter((m) => m !== f.id) : [...modules, f.id])}><f.icon size={14} /> {f.name}</button>
          ))}
        </div>
      </Field>
      {choosable.length > 0 && (
        <Field label="Fields to show" help="Pick the details you want to record">
          <div className="tag-list">
            {choosable.map((f) => (
              <button key={f.key} type="button" aria-pressed={fields.includes(f.key)} className={`chip ${fields.includes(f.key) ? "is-on" : ""}`}
                onClick={() => setFields(fields.includes(f.key) ? fields.filter((k) => k !== f.key) : [...fields, f.key])}>{f.label}</button>
            ))}
          </div>
        </Field>
      )}
    </Modal>
  );
}

/** On Today, until something's picked: a gentle start. */
export function FirstRunPrompt({ onDismiss }: { onDismiss: () => void }) {
  const navigate = useNavigate();
  return (
    <section className="panel-card first-run">
      <div className="first-run-icon"><Sparkles size={22} /></div>
      <div>
        <h2>What would you like to keep track of?</h2>
        <p className="muted">Tell the app about the conditions and goals that matter to you (a bladder condition, endometriosis, ADHD, losing weight, transition…) and it shows the right tools, and only those.</p>
        <div className="button-row">
          <Button variant="primary" onClick={() => navigate("/conditions")}>Choose</Button>
          <Button variant="ghost" onClick={onDismiss}>Not now</Button>
        </div>
      </div>
    </section>
  );
}
