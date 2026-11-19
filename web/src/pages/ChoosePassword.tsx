import { useState } from "react";
import { KeyRound } from "lucide-react";
import { api } from "../lib/api";
import { session, useSession } from "../lib/session";
import { Button, Field } from "../components/ui";

/**
 * Right after signing in with a password an admin set (a new account's, or a reset): it's
 * temporary, so the person chooses their own before anything else opens.
 */
export function ChoosePassword() {
  const user = useSession((s) => s.user);
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!user) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.changePassword(current, next);
      session.setUser({ ...user, mustChangePassword: false });
      void session.loadProfile();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <img src="/icon.svg" alt="" className="login-logo" width={72} height={72} />
        <h1>Choose a new password</h1>
        <p className="login-sub">An admin set the one you signed in with. Choose your own, so only you know it.</p>
        {error && <div className="notice notice-error" role="alert">{error}</div>}
        <form onSubmit={submit} className="login-form">
          <Field label="The password you were given">
            <input type="password" autoComplete="current-password" autoFocus value={current} onChange={(e) => setCurrent(e.target.value)} required />
          </Field>
          <Field label="New password" help="At least 8 characters.">
            <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} required />
          </Field>
          <Button variant="primary" className="wide" busy={busy} type="submit"><KeyRound size={18} /> Save my password</Button>
        </form>
        <button className="link-btn" onClick={() => void session.signOut()}>Sign out</button>
      </div>
    </div>
  );
}
