import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Check, ChefHat, ExternalLink, Plus } from "lucide-react";
import { api } from "../lib/api";
import { canAdd, connectedLine, planLine, recipeLine } from "../lib/mealie";
import type { Food, MealiePlan, MealieRecipes, MealieStatus } from "../lib/types";
import { onUserChange } from "../lib/session";
import { useReadOnly } from "../lib/viewing";
import { Button, Card, Field, Spinner, errorToast, toast, useLoad } from "../components/ui";

/*
 * Mealie, the self-hosted recipe manager, in Food: connecting it (Settings › Connections), its
 * recipes in the food search, and the day's meal plan on the Food page. Nothing here is asked while
 * viewing a share: the server never answers one about Mealie.
 */

let known: MealieStatus | undefined;
const listeners = new Set<(s: MealieStatus) => void>();
let asking: Promise<MealieStatus> | null = null;
/** Counts sign-ins, so an answer for the last person never counts for the next. */
let generation = 0;

function ask(): Promise<MealieStatus> {
  const mine = generation;
  asking ??= api.mealie().catch(() => ({ connected: false }) as MealieStatus).then((s) => {
    if (mine !== generation) return s;
    known = s;
    asking = null;
    listeners.forEach((l) => l(s));
    return s;
  });
  return asking;
}

// Someone else signed in (or nobody is): their Mealie, asked afresh.
onUserChange(() => {
  generation++;
  known = undefined;
  asking = null;
});

function tell(s: MealieStatus) {
  known = s;
  listeners.forEach((l) => l(s));
}

/** The person's Mealie: undefined until known; never connected while viewing a share. */
export function useMealie(): MealieStatus | undefined {
  const readOnly = useReadOnly();
  const [status, setStatus] = useState<MealieStatus | undefined>(readOnly ? { connected: false } : known);
  useEffect(() => {
    if (readOnly) return;
    listeners.add(setStatus);
    if (known) setStatus(known); else void ask();
    return () => { listeners.delete(setStatus); };
  }, [readOnly]);
  return readOnly ? { connected: false } : status;
}

/** Settings › Connections › Mealie: its address and an API token, checked with Mealie before it's saved. */
export function MealieSettings() {
  const status = useMealie();
  const [editing, setEditing] = useState(false);
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  if (!status) return <Spinner />;
  const connect = async (keepToken: boolean) => {
    setBusy(true);
    setProblem(null);
    try {
      const s = await api.connectMealie({ url: url.trim() || status.url || "", token: keepToken ? undefined : token.trim() });
      tell(s);
      setToken("");
      setEditing(false);
      toast("Mealie connected");
    } catch (e) {
      setProblem(e instanceof Error ? e.message : "Couldn't connect to Mealie");
    }
    setBusy(false);
  };
  const disconnect = async () => {
    if (!confirm("Disconnect Mealie? Recipes you've logged stay in your foods.")) return;
    try { await api.disconnectMealie(); tell({ connected: false }); toast("Mealie disconnected"); } catch (e) { errorToast(e); }
  };
  if (status.connected && !editing) {
    return (
      <>
        <p className="mealie-connected"><Check size={16} aria-hidden="true" /> {connectedLine(status)}</p>
        <p className="muted small">Your recipes come up when you search for food, and the day's meal plan shows on <Link to="/food">Food</Link>. Nothing is ever changed in Mealie.</p>
        <div className="button-row">
          <Button variant="tonal" onClick={() => { setUrl(status.url ?? ""); setToken(""); setEditing(true); }}>Change address or token</Button>
          <Button variant="ghost" onClick={disconnect}>Disconnect</Button>
        </div>
      </>
    );
  }
  const changing = status.connected;
  return (
    <form className="mealie-form" onSubmit={(e) => { e.preventDefault(); void connect(changing && !token.trim()); }}>
      {!changing && <p className="muted">Bring your Mealie recipes and meal plan into Food. Each recipe's nutrition comes along, per serving.</p>}
      <Field label="Mealie's address" help="The address you open Mealie at, like http://mealie.home:9000">
        <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://mealie.home:9000" inputMode="url" autoComplete="url" required={!changing} />
      </Field>
      <Field label="API token" help={changing ? "Leave it empty to keep the one you have" : "In Mealie: your profile, then API tokens. Make one for this app"}>
        <input type="password" value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" spellCheck={false} required={!changing} />
      </Field>
      {problem && <p className="notice notice-error" role="alert">{problem}</p>}
      <div className="button-row">
        <Button variant="primary" type="submit" busy={busy}>{changing ? "Save" : "Connect"}</Button>
        {changing && <Button variant="ghost" onClick={() => { setEditing(false); setProblem(null); }}>Cancel</Button>}
      </div>
    </form>
  );
}

/**
 * The day's meal plan from Mealie, each planned meal added to its meal with one tap. Food keys it
 * by [date], so a card only ever shows, adds to and reloads its own day.
 */
export function MealPlanCard({ date, numbers, readOnly, onChanged }: { date: string; numbers: boolean; readOnly: boolean; onChanged: () => void }) {
  // useLoad drops an answer that comes after the day has changed.
  const { data: plan, reload } = useLoad<MealiePlan>(
    () => api.mealiePlan(date).catch(() => ({ date, entries: [], problem: "The meal plan isn't available just now" })),
    [date],
  );
  const [busy, setBusy] = useState<number | null>(null);
  if (!plan || plan.date !== date || (plan.entries.length === 0 && !plan.problem)) return null;
  const add = async (i: number) => {
    const e = plan.entries[i];
    if (!e.recipe?.food) return;
    setBusy(i);
    try {
      await api.logFood({ date: plan.date, meal: e.meal, servings: 1, food: e.recipe.food });
      toast(`${e.title} added`);
      onChanged();
      reload();
    } catch (err) { errorToast(err); }
    setBusy(null);
  };
  return (
    <Card id="mealplan" title="Meal plan" icon={<ChefHat size={18} />} className="span-2 mealplan-card">
      {plan.problem ? <p className="muted small">{plan.problem}</p> : (
        <ul className="rows">
          {plan.entries.map((e, i) => (
            <li key={`${e.meal}-${e.title}-${i}`} className="row">
              <div className="row-main">
                <div className="row-title">{e.title}</div>
                <div className="row-sub wraps">{planLine(e, numbers)}</div>
              </div>
              {e.logged ? <span className="mealplan-logged"><Check size={16} aria-hidden="true" /> Logged</span>
                : !readOnly && canAdd(e) ? (
                  <Button variant="tonal" className="btn-small" busy={busy === i} aria-label={`Add ${e.title}`} onClick={() => add(i)}>
                    <Plus size={16} aria-hidden="true" /> Add
                  </Button>
                ) : e.recipe?.link && !e.recipe.food ? (
                  <a className="btn btn-ghost btn-small" href={e.recipe.link} target="_blank" rel="noreferrer" aria-label={`Open ${e.title} in Mealie`}><ExternalLink size={16} aria-hidden="true" /></a>
                ) : null}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** "Your Mealie" in the food search: recipes with nutrition are chosen like any food; the rest say why not. */
export function MealieResults({ query, numbers, onChoose, onFound }: {
  query: string; numbers: boolean; onChoose: (f: Food) => void;
  /** Whether Mealie found anything, so the search doesn't say "Nothing found". */
  onFound?: (any: boolean) => void;
}) {
  const status = useMealie();
  const [found, setFound] = useState<MealieRecipes | null>(null);
  const q = query.trim();
  useEffect(() => { onFound?.(!!found && found.items.length > 0); }, [found, onFound]);
  useEffect(() => {
    if (!status?.connected || q.length < 2) { setFound(null); return; }
    let cancelled = false;
    const timer = setTimeout(() => {
      api.mealieRecipes(q).then((r) => { if (!cancelled) setFound(r); }, () => { if (!cancelled) setFound({ items: [], problem: "Mealie didn't answer just now" }); });
    }, 350);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [q, status?.connected]);
  if (!found || (found.items.length === 0 && !found.problem)) return null;
  return (
    <>
      <div className="result-group-title">Your Mealie</div>
      {found.problem && <p className="muted small">{found.problem}</p>}
      {found.items.map((r) => r.food ? (
        <button key={r.slug} className="row" onClick={() => onChoose(r.food!)}>
          <div className="row-main">
            <div className="row-title">{r.name}</div>
            <div className="row-sub">{recipeLine(r, numbers)}</div>
          </div>
          {numbers && <div className="row-end">{Math.round(r.food.nutrients.calories)}<div className="muted">kcal</div></div>}
        </button>
      ) : (
        <div key={r.slug} className="row is-quiet">
          <div className="row-main">
            <div className="row-title">{r.name}</div>
            <div className="row-sub">{recipeLine(r, numbers)}{r.link && <> · <a href={r.link} target="_blank" rel="noreferrer">Open in Mealie</a></>}</div>
          </div>
        </div>
      ))}
    </>
  );
}
