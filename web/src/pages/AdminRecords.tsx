import { useEffect, useState } from "react";
import { Copy } from "lucide-react";
import type { AdminSettings, PortalApp, RecordProvider, RecordsSettings } from "../lib/types";
import { Button, Field, Toggle, toast } from "../components/ui";
import { useHashScroll } from "../components/Arrange";

/*
 * Admin › Medical records: the app this server registered with each health record system, so people
 * can connect their own patient portals (You › Medical records). One row per system: on or off, its
 * client ID, and its client secret (write-only: "Saved", with Change and Remove), with a short "How
 * to get these". The redirect URI to register is shown once, at the top. Saved with the rest of the
 * server's settings; the secrets go apart, as recordSecrets.
 */

export type RecordSecrets = Partial<Record<RecordProvider, string>>;

const NONE: RecordsSettings = { epic: { enabled: false, clientId: "" }, athena: { enabled: false, clientId: "" } };

const SYSTEMS: { id: RecordProvider; key: keyof RecordsSettings; label: string; help: string }[] = [
  { id: "EPIC", key: "epic", label: "Epic (MyChart)", help: "Hospitals and health systems on Epic, like Mass General Brigham" },
  { id: "ATHENA", key: "athena", label: "athenahealth", help: "Practices on athenahealth, where people choose theirs as they sign in" },
];

export function RecordsAdmin({ initial, records, onChange, secrets, onSecrets }: {
  initial: AdminSettings;
  records: RecordsSettings | undefined;
  onChange: (records: RecordsSettings) => void;
  /** Secrets to save: a new one, or "" to remove the saved one; one left out is kept. */
  secrets: RecordSecrets;
  onSecrets: (secrets: RecordSecrets) => void;
}) {
  // Admin › Medical records from You › Medical records lands here (/admin#records).
  useHashScroll(true);
  const now = records ?? NONE;
  const saved = new Set(initial.recordSecretsSet ?? []);
  const redirect = initial.recordsRedirectUri ?? "";
  const setApp = (key: keyof RecordsSettings, change: Partial<PortalApp>) => onChange({ ...now, [key]: { ...now[key], ...change } });
  const setSecret = (id: RecordProvider, value: string | undefined) => {
    const next = { ...secrets };
    if (value === undefined) delete next[id]; else next[id] = value;
    onSecrets(next);
  };
  return (
    <section className="panel" id="records" aria-labelledby="records-admin">
      <h2 id="records-admin">Medical records</h2>
      <p className="muted">So people can connect their hospital or clinic's patient portal and see their own record here. Register an app with each system once; nothing is ever written back.</p>
      {redirect && (
        <div className="copy-row" aria-label="Redirect URI">
          <code>{redirect}</code>
          <Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(redirect); toast("Copied"); }}><Copy size={16} /> Copy</Button>
        </div>
      )}
      <div className="records-admin-list">
        {SYSTEMS.map((s) => (
          <SystemRow key={s.id} system={s} app={now[s.key]} saved={saved.has(s.id)} secret={secrets[s.id]} loaded={initial}
            onApp={(change) => setApp(s.key, change)} onSecret={(v) => setSecret(s.id, v)} />
        ))}
      </div>
    </section>
  );
}

function SystemRow({ system, app, saved, secret, loaded, onApp, onSecret }: {
  system: (typeof SYSTEMS)[number]; app: PortalApp; saved: boolean; secret: string | undefined; loaded: AdminSettings;
  onApp: (change: Partial<PortalApp>) => void; onSecret: (value: string | undefined) => void;
}) {
  const [changing, setChanging] = useState(false);
  const removing = secret === "";
  // Saved (or loaded again): back to "Saved".
  useEffect(() => setChanging(false), [loaded]);
  return (
    <div className="records-admin-row">
      <Toggle label={system.label} help={system.help} checked={app.enabled} onChange={(enabled) => onApp({ enabled })} />
      {app.enabled && (
        <div className="two-col">
          <Field label="Client ID">
            <input value={app.clientId} onChange={(e) => onApp({ clientId: e.target.value.trim() })} autoComplete="off" autoCapitalize="none" spellCheck={false} />
          </Field>
          <Field label="Client secret" help={removing || (saved && !changing) ? undefined : saved ? "Leave it empty to keep the saved one." : "Optional: with one, records update themselves daily."}>
            {removing ? (
              <div className="secret-state"><span>Removed when you save</span><Button variant="ghost" onClick={() => onSecret(undefined)}>Undo</Button></div>
            ) : saved && !changing ? (
              <div className="secret-state">
                <span className="tone-chip tone-good">Saved</span>
                <Button variant="ghost" onClick={() => setChanging(true)}>Change</Button>
                <Button variant="ghost" onClick={() => onSecret("")}>Remove</Button>
              </div>
            ) : (
              <input type="password" autoComplete="new-password" value={secret ?? ""} placeholder={saved ? "A new secret" : ""}
                aria-label={`${system.label} client secret`} onChange={(e) => onSecret(e.target.value || undefined)} />
            )}
          </Field>
        </div>
      )}
      <details className="records-howto">
        <summary>How to get these</summary>
        {system.id === "EPIC" ? (
          <ol>
            <li>At <strong>fhir.epic.com</strong>, sign in and open <strong>Build Apps</strong>, then create an app.</li>
            <li>Audience: <strong>Patients</strong>. Choose the R4 read APIs for Patient, Observation (labs and vital signs), MedicationRequest, Condition, AllergyIntolerance, Immunization and Procedure.</li>
            <li>Add the redirect URI above. For lasting access, make it a confidential client with refresh tokens, and put its secret here.</li>
            <li>Mark it ready for production. Epic passes it on to its organizations, which can take a few days.</li>
          </ol>
        ) : (
          <ol>
            <li>In <strong>athenahealth's developer portal</strong>, create a patient-facing app for the certified FHIR APIs (R4).</li>
            <li>Add the redirect URI above, and copy its client ID here; its secret too, for lasting access.</li>
          </ol>
        )}
      </details>
    </div>
  );
}
