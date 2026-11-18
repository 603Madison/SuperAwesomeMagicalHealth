import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  ArrowDown, ArrowUp, CirclePause, ClipboardList, FlaskConical, HeartPulse, Hospital, Lock, LogIn, Pill, RefreshCw, Search, ShieldAlert,
  Stethoscope, Syringe, TriangleAlert, Unplug, X,
} from "lucide-react";
import { api } from "../lib/api";
import { NAMES } from "../lib/nav";
import {
  CONNECTIONS_SECTION, KIND_ORDER, KIND_SECTION, countLine, draftMedication, flagWord, groupByKind, itemLine, keepsText, reconnect,
  returnFrom, updatedText,
} from "../lib/records";
import { useSession } from "../lib/session";
import { RECORD_KINDS, RECORD_PROVIDERS, layoutKey } from "../lib/types";
import type { Medication, RecordConnect, RecordConnection, RecordFlag, RecordItem, RecordKind, RecordOrganization } from "../lib/types";
import { useArrange, type Section } from "../components/Arrange";
import { Button, Card, Empty, ErrorNotice, IconButton, Modal, PageHeader, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { useModules } from "../App";
import { MedDialog } from "./Meds";

/*
 * You › Medical records (/api/records): a hospital, clinic or practice's own record of the person,
 * read from its patient portal (Epic's MyChart, athenahealth). Connecting sends the browser to the
 * portal's own sign-in; the portal sends it back here with ?connected= or ?error=. Then each
 * portal is a card (when it was last brought up to date, what it brought), and the record follows a
 * kind at a time. Arranged under web.records. Only ever the person's own: never shared.
 */

const KIND_ICONS: Record<RecordKind, ReactNode> = {
  LAB: <FlaskConical size={18} />, VITAL: <HeartPulse size={18} />, MEDICATION: <Pill size={18} />, CONDITION: <Stethoscope size={18} />,
  ALLERGY: <ShieldAlert size={18} />, IMMUNIZATION: <Syringe size={18} />, PROCEDURE: <ClipboardList size={18} />,
};

/** How many rows a kind shows before "Show all". */
const FIRST_ROWS = 8;

export function Records() {
  const user = useSession((s) => s.user)!;
  const location = useLocation();
  const navigate = useNavigate();
  const records = useLoad(() => api.records(), []);
  const items = useLoad(() => api.recordItems(), []);
  const [portalError, setPortalError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const reload = () => { records.reload(); items.reload(); setNow(Date.now()); };

  // Back from the portal: say how it went, bring the page up to date, and tidy the address.
  const handled = useRef(false);
  useEffect(() => {
    if (handled.current) return;
    const back = returnFrom(location.search);
    if (!back.connected && !back.error) return;
    handled.current = true;
    if (back.connected) toast("Connected");
    if (back.error) setPortalError(back.error);
    navigate(`${location.pathname}${back.rest}${location.hash}`, { replace: true });
  }, [location, navigate]);

  const data = records.data;
  const connected = (data?.connections.length ?? 0) > 0;
  const grouped = groupByKind(items.data ?? []);
  const sections: Section[] = [
    { id: CONNECTIONS_SECTION, label: "Your portals", node: data && <Connections connections={data.connections} paused={data.paused} now={now} onChanged={reload} /> },
    ...KIND_ORDER.map((kind): Section => ({
      id: KIND_SECTION[kind], label: RECORD_KINDS[kind], when: connected,
      node: (grouped[kind]?.length ?? 0) > 0 && <KindCard kind={kind} items={grouped[kind]!} />,
    })),
  ];
  const arranged = useArrange(layoutKey("records"), NAMES.records, sections);
  const ready = !!data && data.providers.length > 0;

  return (
    <div className="page records-page">
      <PageHeader title={NAMES.records} subtitle="Your hospital or clinic's own record, from its patient portal" actions={ready ? arranged.button : null} />
      {portalError && (
        <div className="notice notice-error notice-row" role="alert">
          <span>{portalError}</span>
          <IconButton label="Dismiss" onClick={() => setPortalError(null)}><X size={18} /></IconButton>
        </div>
      )}
      {records.error && <ErrorNotice message={records.error} onRetry={reload} />}
      {records.loading && !data && <Spinner />}
      {data && data.providers.length === 0 && <NotSetUp admin={user.role === "ADMIN"} />}
      {ready && (
        <>
          {data.paused && (
            <p className="paused-note records-paused">
              <CirclePause size={16} aria-hidden="true" />
              <span>Paused in <Link to="/settings#privacy">{NAMES.privacy}</Link>. Nothing is brought in until you turn it back on.</span>
            </p>
          )}
          {items.error && connected && <ErrorNotice message={items.error} onRetry={items.reload} />}
          {arranged.render("grid grid-2 records-sections")}
        </>
      )}
    </div>
  );
}

/** The server can't connect anywhere yet: its admin sets the systems up first. */
function NotSetUp({ admin }: { admin: boolean }) {
  return (
    <Card className="records-calm">
      <Empty icon={<Hospital />} title="Not set up on this server yet">
        {admin
          ? <>Set up medical records in <Link to="/admin#records">Admin</Link> first: the app you register with Epic or athenahealth.</>
          : "Your server's admin hasn't set this up yet."}
      </Empty>
    </Card>
  );
}

/** Searching for a hospital, clinic or practice, and connecting to its portal. */
function ConnectCard({ first }: { first: boolean }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<RecordOrganization[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [going, setGoing] = useState<string | null>(null);
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) { setResults(null); setBusy(false); return; }
    let cancelled = false;
    setBusy(true);
    const timer = setTimeout(() => {
      api.recordOrganizations(q)
        .then((r) => { if (!cancelled) setResults(r); }, (e) => { if (!cancelled) errorToast(e); })
        .finally(() => { if (!cancelled) setBusy(false); });
    }, 300);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query]);

  const connect = async (o: RecordOrganization) => {
    setGoing(o.fhirBaseUrl);
    try {
      const { url } = await api.connectRecords({ organization: o.name, fhirBaseUrl: o.fhirBaseUrl, provider: o.provider });
      window.location.assign(url);
    } catch (e) { errorToast(e); setGoing(null); }
  };

  return (
    <Card title={first ? "Connect your patient portal" : "Connect another"} icon={<Hospital size={18} />} className={`records-connect ${first ? "span-2" : ""}`}>
      <div className="search-box">
        <Search size={20} aria-hidden="true" />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search for your hospital or clinic" aria-label="Search for your hospital or clinic" autoComplete="off" />
        {busy && <Spinner size={18} />}
      </div>
      <p className="records-promise"><Lock size={14} aria-hidden="true" /><span>You'll sign in on your portal's own page. Your record comes straight to this server; nothing else sees it.</span></p>
      {results && (
        results.length === 0 ? <p className="muted small">Nothing by that name. Try the health system's name, like the one on your bills.</p> : (
          <ul className="rows records-results">
            {results.map((o) => (
              <li key={`${o.provider}-${o.fhirBaseUrl}`}>
                <button type="button" className="row" disabled={going != null} onClick={() => void connect(o)}>
                  <div className="row-icon"><Hospital size={18} /></div>
                  <div className="row-main">
                    <div className="row-title">{o.name}</div>
                    <div className="row-sub">{RECORD_PROVIDERS[o.provider]}</div>
                  </div>
                  {going === o.fhirBaseUrl ? <Spinner size={18} /> : <span className="records-go">Connect</span>}
                </button>
              </li>
            ))}
          </ul>
        )
      )}
    </Card>
  );
}

/** Each connected portal, as a card, and the way to connect one (another). */
function Connections({ connections, paused, now, onChanged }: { connections: RecordConnection[]; paused: boolean; now: number; onChanged: () => void }) {
  if (connections.length === 0 && paused) return null;
  return (
    <section className="block span-2 records-connections" aria-label="Your portals">
      <div className="grid grid-2">
        {connections.map((c) => <ConnectionCard key={c.id} c={c} paused={paused} now={now} onChanged={onChanged} />)}
        {!paused && <ConnectCard first={connections.length === 0} />}
      </div>
    </section>
  );
}

function ConnectionCard({ c, paused, now, onChanged }: { c: RecordConnection; paused: boolean; now: number; onChanged: () => void }) {
  const [syncing, setSyncing] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const counts = countLine(c.counts);
  const update = async () => {
    setSyncing(true);
    try { await api.syncRecords(c.id); toast(`${c.organization}: up to date`); onChanged(); } catch (e) { errorToast(e); }
    setSyncing(false);
  };
  const signIn = async (connect: RecordConnect) => {
    setSigningIn(true);
    try { window.location.assign((await api.connectRecords(connect)).url); } catch (e) { errorToast(e); setSigningIn(false); }
  };
  return (
    <article className="panel-card records-connection">
      <div className="records-connection-head">
        <div className="row-icon"><Hospital size={18} /></div>
        <div className="row-main">
          <h3 className="records-org">{c.organization}</h3>
          <div className="row-sub">{RECORD_PROVIDERS[c.provider]}</div>
        </div>
      </div>
      <p className="records-when">{updatedText(c, now)} · {keepsText(c)}</p>
      {c.problem && <div className="notice notice-warn records-problem"><TriangleAlert size={16} aria-hidden="true" /><span>{c.problem}</span></div>}
      {counts && <p className="muted small">{counts}</p>}
      <div className="button-row">
        {c.keepsUpdating && <Button busy={syncing} disabled={paused} onClick={() => void update()}><RefreshCw size={16} /> Update now</Button>}
        {(!c.keepsUpdating || c.problem) && (
          <Button variant={c.keepsUpdating ? "tonal" : "primary"} busy={signingIn} disabled={paused} onClick={() => void signIn(reconnect(c))}><LogIn size={16} /> Sign in again</Button>
        )}
        <Button variant="ghost" onClick={() => setDisconnecting(true)}><Unplug size={16} /> Disconnect</Button>
      </div>
      {disconnecting && <DisconnectDialog c={c} counts={counts} onClose={() => setDisconnecting(false)} onDone={onChanged} />}
    </article>
  );
}

/** Disconnecting forgets the portal's access; what it brought in goes too, unless the person keeps it. */
function DisconnectDialog({ c, counts, onClose, onDone }: { c: RecordConnection; counts: string | null; onClose: () => void; onDone: () => void }) {
  const [keep, setKeep] = useState(false);
  const [busy, setBusy] = useState(false);
  const go = async () => {
    setBusy(true);
    try {
      await api.disconnectRecords(c.id, keep);
      toast(keep ? `Disconnected ${c.organization}; what it brought in is kept` : `Disconnected ${c.organization}`);
      onDone();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`Disconnect ${c.organization}?`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="danger" busy={busy} onClick={() => void go()}>Disconnect</Button></>}>
      <p>This server forgets its access, and nothing more is brought in from it. You can connect again any time.</p>
      <div className="records-choices" role="radiogroup" aria-label="What it brought in">
        <label className="records-choice">
          <input type="radio" name="keep" checked={!keep} onChange={() => setKeep(false)} />
          <span><strong>Remove what it brought in</strong><span className="muted small">{counts ? `${counts}, lab results in Medications too` : "Anything it brought in, lab results in Medications too"}</span></span>
        </label>
        <label className="records-choice">
          <input type="radio" name="keep" checked={keep} onChange={() => setKeep(true)} />
          <span><strong>Keep it here</strong><span className="muted small">It stays, as it was when you disconnected</span></span>
        </label>
      </div>
    </Modal>
  );
}

/** One kind of the record, newest first: a lab's flag in words, a medication's way into Medications. */
function KindCard({ kind, items }: { kind: RecordKind; items: RecordItem[] }) {
  const [all, setAll] = useState(false);
  const [adding, setAdding] = useState<Medication | null>(null);
  const medsOn = useModules().includes("MEDICATIONS");
  const meds = useLoad(() => (kind === "MEDICATION" && medsOn ? api.meds(true) : Promise.resolve([] as Medication[])), [kind, medsOn]);
  const mine = new Set((meds.data ?? []).map((m) => m.name.trim().toLowerCase()));
  const shown = all ? items : items.slice(0, FIRST_ROWS);
  return (
    <Card title={RECORD_KINDS[kind]} icon={KIND_ICONS[kind]} className="records-kind" id={`records-${KIND_SECTION[kind]}`}
      action={<span className="muted small">{items.length}</span>}>
      <ul className="rows">
        {shown.map((item) => {
          const med = kind === "MEDICATION" && medsOn ? item.medication : undefined;
          const have = med && mine.has(med.name.trim().toLowerCase());
          return (
            <li key={item.id} className="row records-row">
              <div className="row-main">
                <div className="row-title">{item.title}</div>
                {itemLine(item) && <div className="row-sub">{itemLine(item)}</div>}
                {med && (have
                  ? <div className="row-sub">In your medications</div>
                  : <button type="button" className="link-btn row-link" onClick={() => setAdding(draftMedication(med))}><Pill size={14} aria-hidden="true" /> Add to my medications</button>)}
              </div>
              {item.flag && <Flag flag={item.flag} />}
            </li>
          );
        })}
      </ul>
      {items.length > FIRST_ROWS && (
        <button type="button" className="link-btn records-more" onClick={() => setAll(!all)}>{all ? "Show fewer" : `Show all ${items.length}`}</button>
      )}
      {adding && <MedDialog med={adding} onClose={() => setAdding(null)} onSaved={meds.reload} />}
    </Card>
  );
}

/** A lab's flag: a word first, with an arrow, never colour alone. */
function Flag({ flag }: { flag: RecordFlag }) {
  const Icon = flag === "HIGH" ? ArrowUp : flag === "LOW" ? ArrowDown : TriangleAlert;
  return <span className="tone-chip tone-warn records-flag"><Icon size={14} aria-hidden="true" />{flagWord(flag)}</span>;
}
