import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Watch } from "lucide-react";
import { api } from "../lib/api";
import { useSession } from "../lib/session";
import { Button } from "../components/ui";

/**
 * The watch app's settings page: the Pebble app opens it (through /pebble/config) with a
 * `return_to` address; once the person is signed in and agrees, the watch gets a key that can
 * only show today's numbers and log taps. The key goes only back to the Pebble app.
 */
export function WatchSetup() {
  const [params] = useSearchParams();
  const user = useSession((s) => s.user);
  const config = useSession((s) => s.config);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const returnTo = params.get("return_to") ?? "";
  // Only the Pebble app's own address: anywhere else could keep the key.
  const valid = returnTo.toLowerCase().startsWith("pebblejs://close");

  const connect = async () => {
    if (!valid) return;
    setBusy(true);
    try {
      const { token } = await api.watchKey();
      setDone(true);
      window.location.href = `${returnTo}#${encodeURIComponent(JSON.stringify({ server: window.location.origin, token }))}`;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="connect-icon"><Watch size={36} /></div>
        {!valid ? (
          <>
            <h1>Open this from the watch app</h1>
            <p className="login-sub">In the Pebble app on your phone, open the SuperAwesomeMagicalHealth watch app's settings. This page connects the watch from there.</p>
          </>
        ) : done ? (
          <>
            <h1>Your watch is connected</h1>
            <p className="login-sub">It'll show today's numbers in a moment.</p>
          </>
        ) : (
          <>
            <h1>Connect your watch?</h1>
            <p className="login-sub">
              Your Pebble watch will show today's scores, steps, water and doses from {config?.serverName ?? "this server"} for{" "}
              <strong>{user?.displayName}</strong> ({user?.username}), and can log a mood, water, coffee, toilet trips, doses and habits with a tap.
              It can't see anything else.
            </p>
            {error && <div className="notice notice-error">{error}</div>}
            <Button variant="primary" className="wide" busy={busy} onClick={connect}>Connect my watch</Button>
            <Button variant="ghost" className="wide" onClick={() => window.close()}>Cancel</Button>
            <p className="login-version">You can disconnect it any time in Settings › Signed in on.</p>
          </>
        )}
      </div>
    </div>
  );
}
