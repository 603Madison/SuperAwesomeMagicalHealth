import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { create } from "zustand";
import { AlertTriangle, Bot, Eraser, ExternalLink, FileSearch, KeyRound, Send, ShieldCheck, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import { useSession } from "../lib/session";
import type { AiConsent, AiMessage, AiLook, AiSettings, AiStatus, Module } from "../lib/types";
import { SENSITIVE } from "../lib/types";
import { FEATURES, featureName } from "../components/features";
import { Button, Card, Empty, Field, Modal, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";

/*
 * The optional AI assistant. Nothing here goes anywhere until the admin has turned it on and
 * the person has read what it means and said yes. Even then it sees only the features they
 * tick (private ones never pre-ticked), never photos, and every reply says what it looked at.
 */

const useAiStore = create<{ status: AiStatus | null; loaded: boolean }>(() => ({ status: null, loaded: false }));
let loading: Promise<void> | null = null;

export function loadAiStatus(force = false): Promise<void> {
  if (loading && !force) return loading;
  loading = api.aiStatus()
    .then((status) => useAiStore.setState({ status, loaded: true }))
    .catch(() => useAiStore.setState({ status: null, loaded: true }));
  return loading;
}

/** The status, loading it the first time it's asked for. */
export function useAiStatus(): AiStatus | null {
  const status = useAiStore((s) => s.status);
  const loaded = useAiStore((s) => s.loaded);
  if (!loaded && !loading) void loadAiStatus();
  return status;
}

/** Whether the assistant is on for this person: the admin turned it on and they said yes. */
export function useAiAvailable(): boolean {
  const s = useAiStatus();
  return !!s?.available && !!s.consent?.acknowledged;
}

/** Features the assistant may be offered; photos never are. */
const OFFERED = FEATURES.filter((f) => f.id !== "PHOTOS");

export function Assistant() {
  const status = useAiStatus();
  const loaded = useAiStore((s) => s.loaded);
  const [changing, setChanging] = useState(false);
  if (!loaded) return <div className="page"><PageHeader title="Assistant" /><Spinner /></div>;
  if (!status?.available) {
    return (
      <div className="page">
        <PageHeader title="Assistant" />
        <Card><Empty icon={<Bot size={36} />} title="The assistant isn't on">Your server's admin can turn on an AI assistant. It's off unless they do, and even then it's off for you unless you choose it.</Empty></Card>
      </div>
    );
  }
  if (!status.consent?.acknowledged || changing) {
    return <Consent status={status} onDone={() => { setChanging(false); void loadAiStatus(true); }} onCancel={status.consent?.acknowledged ? () => setChanging(false) : undefined} />;
  }
  return <Chat status={status} onChangeConsent={() => setChanging(true)} />;
}

/** What the assistant is, what it sends where, and the features it may look at. */
function Consent({ status, onDone, onCancel }: { status: AiStatus; onDone: () => void; onCancel?: () => void }) {
  const on = useSession((s) => s.profile?.modules ?? []);
  const was = status.consent;
  const [modules, setModules] = useState<Module[]>(was?.modules ?? on.filter((m) => !SENSITIVE.includes(m)));
  const [allowLogging, setAllowLogging] = useState(was?.allowLogging ?? false);
  const [understood, setUnderstood] = useState(false);
  const [sensitiveOk, setSensitiveOk] = useState(was?.sensitiveAcknowledged ?? false);
  const [busy, setBusy] = useState(false);
  const picksSensitive = modules.some((m) => SENSITIVE.includes(m));
  const save = async () => {
    setBusy(true);
    try {
      await api.saveAiConsent({ modules, allowLogging, acknowledged: true, sensitiveAcknowledged: picksSensitive && sensitiveOk });
      toast("The assistant is on for you");
      onDone();
    } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  const toggle = (m: Module) => setModules(modules.includes(m) ? modules.filter((x) => x !== m) : [...modules, m]);
  return (
    <div className="page">
      <PageHeader title="Before you use the assistant" subtitle="Please read this: it's about where your health information goes" />
      <div className="grid grid-2">
        <Card title="What happens" icon={<AlertTriangle size={18} />} className="span-2 warning-card">
          <ul className="plain-list">
            <li>Your questions, and the parts of your health data the assistant looks at to answer them, are sent to <strong>{status.provider}</strong>, a company outside this server{status.model ? `, to its ${status.model} model` : ""}.</li>
            <li>They're handled under {status.provider}'s <a href={status.termsUrl} target="_blank" rel="noreferrer">commercial terms <ExternalLink size={12} /></a> and <a href={status.privacyUrl} target="_blank" rel="noreferrer">privacy policy <ExternalLink size={12} /></a>, and the admin's account settings with them.</li>
            <li>It only looks at the features you tick below. It never sees your progress photos.</li>
            <li>Every reply shows what it looked at, and <Link to="/assistant/sent">What it's sent</Link> keeps a list. Your conversations stay in this browser.</li>
            <li>It can be wrong. It isn't a doctor, and it isn't for emergencies.</li>
          </ul>
          {status.dataNotice && <p className="muted small">{status.dataNotice}</p>}
          <p><strong>If you'd rather your health information didn't leave your server, don't turn this on.</strong> Everything else in the app works without it.</p>
        </Card>
        <Card title="What it may look at" className="span-2">
          <div className="tag-list">
            {OFFERED.filter((f) => on.includes(f.id)).map((f) => (
              <button key={f.id} type="button" aria-pressed={modules.includes(f.id)} className={`chip ${modules.includes(f.id) ? "is-on" : ""} ${SENSITIVE.includes(f.id) ? "is-private" : ""}`} onClick={() => toggle(f.id)}>
                <f.icon size={14} /> {f.name}{SENSITIVE.includes(f.id) ? " (private)" : ""}
              </button>
            ))}
          </div>
          {picksSensitive && (
            <div className="notice notice-warn">
              <Toggle label="I understand that private features I've ticked (like mind, cycle, bladder and bowel or medications) will be sent to Anthropic when the assistant looks at them"
                checked={sensitiveOk} onChange={setSensitiveOk} />
            </div>
          )}
          <Toggle label="Let it log things for me"
            help={allowLogging ? "It can add entries you ask for, like a meal or a mood, if your server's admin allows it" : "Off: it only reads"}
            checked={allowLogging} onChange={setAllowLogging} />
          <hr className="rule" />
          <Toggle label="I've read this, and I want to use the assistant" checked={understood} onChange={setUnderstood} />
          <div className="button-row">
            {onCancel && <Button variant="ghost" onClick={onCancel}>Cancel</Button>}
            <Button variant="primary" busy={busy} disabled={!understood || (picksSensitive && !sensitiveOk)} onClick={save}><ShieldCheck size={16} /> Turn it on for me</Button>
          </div>
        </Card>
      </div>
    </div>
  );
}

const storeKey = (userId: string) => `samh.assistant.${userId}`;

function loadConversation(userId: string): { messages: AiMessage[]; looks: Record<number, AiLook[]> } {
  try {
    const raw = localStorage.getItem(storeKey(userId));
    if (raw) return JSON.parse(raw);
  } catch { /* private window or blocked storage: start fresh */ }
  return { messages: [], looks: {} };
}

function Chat({ status, onChangeConsent }: { status: AiStatus; onChangeConsent: () => void }) {
  const user = useSession((s) => s.user)!;
  const [conversation, setConversation] = useState(() => loadConversation(user.id));
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [used, setUsed] = useState(status.usedToday);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    try { localStorage.setItem(storeKey(user.id), JSON.stringify(conversation)); } catch { /* fine */ }
    end.current?.scrollIntoView({ block: "end" });
  }, [conversation, user.id]);
  const send = async () => {
    const question = text.trim();
    if (!question || busy) return;
    const messages: AiMessage[] = [...conversation.messages, { role: "user", text: question }];
    setConversation({ ...conversation, messages });
    setText("");
    setBusy(true);
    try {
      const reply = await api.aiChat(messages);
      setUsed(reply.usedToday);
      // What it looked at, by the index of the reply it goes with.
      setConversation({ messages: [...messages, { role: "assistant", text: reply.text }], looks: { ...conversation.looks, [messages.length]: reply.looked } });
    } catch (e) {
      errorToast(e);
      setConversation({ ...conversation, messages });
    } finally { setBusy(false); }
  };
  const withdraw = async () => {
    try { await api.withdrawAiConsent(); localStorage.removeItem(storeKey(user.id)); toast("The assistant is off for you"); void loadAiStatus(true); } catch (e) { errorToast(e); }
  };
  const left = Math.max(0, status.dailyLimit - used);
  return (
    <div className="page assistant-page">
      <PageHeader title="Assistant" subtitle={`${left} of ${status.dailyLimit} questions left today`} actions={<>
        <Link className="btn btn-ghost" to="/assistant/sent"><FileSearch size={16} /> What it's sent</Link>
        <Button variant="ghost" onClick={onChangeConsent}>What it may look at</Button>
        <Button variant="ghost" onClick={() => setConversation({ messages: [], looks: {} })}><Eraser size={16} /> Clear</Button>
      </>} />
      <div className="ai-banner" role="note"><AlertTriangle size={16} /> Your questions and what it looks at go to {status.provider}. It sees: {(status.consent?.modules ?? []).map(featureName).join(", ") || "nothing yet"}.</div>
      <div className="chat">
        {conversation.messages.length === 0 && (
          <div className="chat-empty">
            <Bot size={32} />
            <p>Ask about your own data: “How did I sleep this week compared with last?”, “What seems to go with my headaches?”, “Help me get ready for my appointment.”</p>
          </div>
        )}
        {conversation.messages.map((m, i) => (
          <div key={i} className={`chat-message is-${m.role}`}>
            <div className="chat-bubble">{m.text.split("\n").map((line, j) => <p key={j}>{line}</p>)}</div>
            {m.role === "assistant" && (conversation.looks[i] ?? []).length > 0 && (
              <div className="looked">
                <span className="muted small">Looked at:</span>
                {conversation.looks[i].map((l, k) => <span key={k} className="chip is-static small" title={l.summary}>{l.module ? featureName(l.module) : l.tool}</span>)}
              </div>
            )}
          </div>
        ))}
        {busy && <div className="chat-message is-assistant"><div className="chat-bubble"><Spinner /></div></div>}
        <div ref={end} />
      </div>
      <form className="chat-input" onSubmit={(e) => { e.preventDefault(); void send(); }}>
        <textarea rows={2} value={text} onChange={(e) => setText(e.target.value)} placeholder="Ask about your health…" aria-label="Your question"
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); void send(); } }} disabled={left === 0} />
        <Button variant="primary" type="submit" busy={busy} disabled={!text.trim() || left === 0} aria-label="Send"><Send size={18} /></Button>
      </form>
      <p className="muted small">It can be wrong, and it isn't a doctor. In an emergency, call your local emergency number. <button className="link-btn" onClick={withdraw}>Turn the assistant off for me</button></p>
    </div>
  );
}

/** Everything the assistant has sent to the provider: when, which features, how much. */
export function AssistantSent() {
  const audit = useLoad(() => api.aiAudit(30), []);
  return (
    <div className="page">
      <PageHeader title="What the assistant has sent" subtitle="The last 30 days: each question, which features it looked at, and how much went" actions={<Link className="btn btn-ghost" to="/assistant">Back</Link>} />
      <Card>
        {audit.loading && <Spinner />}
        {audit.data && audit.data.length === 0 && <p className="muted">Nothing has been sent.</p>}
        {audit.data && audit.data.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>What</th><th>Features</th><th>Model</th><th>Amount</th></tr></thead>
              <tbody>
                {[...audit.data].sort((a, b) => b.at - a.at).map((a, i) => (
                  <tr key={i}>
                    <td>{when(a.at)}</td><td>{a.kind === "breakdown" ? "Break down a task" : "Question"}</td>
                    <td>{a.modules.map(featureName).join(", ") || "None"}</td><td>{a.model}</td>
                    <td>{a.inputTokens + a.outputTokens > 0 ? `${a.inputTokens.toLocaleString()} in, ${a.outputTokens.toLocaleString()} out` : "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

/** Admin › AI: off unless turned on here, after reading what it means. */
export function AiAdminCard() {
  const load = useLoad(() => api.adminAi().catch(() => null), []);
  const [s, setS] = useState<AiSettings | null>(null);
  const [key, setKey] = useState("");
  const [warn, setWarn] = useState(false);
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (load.data && !s) setS(load.data); }, [load.data, s]);
  if (load.loading && !load.data) return <section className="panel" id="ai"><h2>AI assistant</h2><Spinner /></section>;
  if (!s) return null;
  const save = async (next: AiSettings) => {
    setBusy(true);
    try { setS(await api.saveAdminAi(next)); toast("Saved"); void loadAiStatus(true); } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  const saveKey = async () => {
    try { await api.setAiKey(key.trim()); setKey(""); setS({ ...s, hasKey: true }); toast("Key saved"); } catch (e) { errorToast(e); }
  };
  const test = async () => {
    try { const r = await api.testAi(); toast(r.message, r.ok ? "info" : "error"); } catch (e) { errorToast(e); }
  };
  return (
    <section className="panel" id="ai">
      <h2>AI assistant</h2>
      <p className="muted">Off unless you turn it on. When it's on, each person still chooses whether to use it, and which features it may look at. What it looks at is sent to Anthropic.</p>
      <Toggle label="Offer the AI assistant on this server" checked={s.enabled} onChange={(enabled) => { if (enabled && !s.acknowledgedAt) setWarn(true); else void save({ ...s, enabled }); }} />
      <div className="two-col">
        <Field label="Anthropic API key" help={s.hasKey ? "Saved. It's never shown again; paste a new one to replace it." : "Not set"}>
          <div className="inline-form">
            <input type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder={s.hasKey ? "••••••••" : "sk-ant-…"} aria-label="Anthropic API key" />
            <Button onClick={saveKey} disabled={!key.trim()}><KeyRound size={16} /> Save key</Button>
          </div>
        </Field>
        <Field label="Model"><input value={s.model} onChange={(e) => setS({ ...s, model: e.target.value })} /></Field>
      </div>
      <div className="three-col">
        <Field label="Effort" help="Higher thinks harder, and costs more">
          <select value={s.effort} onChange={(e) => setS({ ...s, effort: e.target.value })}><option value="low">Low</option><option value="medium">Medium</option><option value="high">High</option></select>
        </Field>
        <Field label="Questions a person may ask a day"><input inputMode="numeric" value={s.dailyLimit} onChange={(e) => setS({ ...s, dailyLimit: Number(e.target.value) || 0 })} /></Field>
        <Toggle label="People may let it log entries" checked={s.allowLogging} onChange={(allowLogging) => setS({ ...s, allowLogging })} />
      </div>
      <div className="button-row">
        <Button variant="primary" busy={busy} onClick={() => save(s)}>Save</Button>
        <Button variant="ghost" disabled={!s.hasKey} onClick={test}>Test</Button>
        {s.hasKey && <Button variant="ghost" onClick={async () => { try { await api.deleteAiKey(); setS({ ...s, hasKey: false, enabled: false }); toast("Key removed"); } catch (e) { errorToast(e); } }}><Trash2 size={16} /> Remove key</Button>}
      </div>
      {warn && (
        <Modal title="Turning on the AI assistant" onClose={() => setWarn(false)} footer={<>
          <Button variant="ghost" onClick={() => setWarn(false)}>Cancel</Button>
          <Button variant="primary" disabled={!ack} onClick={() => { setWarn(false); void save({ ...s, enabled: true, acknowledgedAt: Date.now() }); }}>Turn it on</Button>
        </>}>
          <p>When people use the assistant, their questions and the health data it looks at to answer are sent to <strong>Anthropic</strong>, a company outside this server, using your API key and under your account's terms with Anthropic.</p>
          <p>That can include private health information (mental health, periods, bladder and bowel, medications) for anyone who chooses to let it. Each person has to opt in themselves and choose what it may see; it never sees photos.</p>
          <Toggle label="I understand, and I'm comfortable offering this on this server" checked={ack} onChange={setAck} />
        </Modal>
      )}
    </section>
  );
}

/** Settings' small reminder that the assistant is on for you, with a way to turn it off. */
export function AssistantSettingsCard() {
  const status = useAiStatus();
  if (!status?.available) return null;
  const consent: AiConsent | undefined = status.consent;
  return (
    <section className="panel" id="assistant">
      <h2>AI assistant</h2>
      {consent?.acknowledged ? (
        <>
          <p>On for you. It may look at: {consent.modules.map(featureName).join(", ") || "nothing"}. What it looks at goes to {status.provider}.</p>
          <div className="button-row">
            <Link className="btn btn-tonal" to="/assistant">Open</Link>
            <Link className="btn btn-ghost" to="/assistant/sent">What it's sent</Link>
            <Button variant="ghost" onClick={async () => { try { await api.withdrawAiConsent(); toast("The assistant is off for you"); void loadAiStatus(true); } catch (e) { errorToast(e); } }}>Turn off for me</Button>
          </div>
        </>
      ) : (
        <>
          <p className="muted">Your server offers an AI assistant. It's off for you. Turning it on sends your questions and what it looks at to {status.provider}.</p>
          <Link className="btn btn-ghost" to="/assistant">Read more</Link>
        </>
      )}
    </section>
  );
}
