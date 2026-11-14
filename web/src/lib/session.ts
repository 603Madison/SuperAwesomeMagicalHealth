import { create } from "zustand";
import { api, ApiError, setSignedOutHandler } from "./api";
import { forgetConversations } from "./assistant";
import { claimPush, disablePush } from "./push";
import { startViewing, useViewing } from "./viewing";
import type { AuthConfig, Profile, User } from "./types";

interface Session {
  config: AuthConfig | null;
  user: User | null;
  profile: Profile | null;
  /** Still finding out whether someone is signed in. */
  loading: boolean;
}

export const useSession = create<Session>(() => ({ config: null, user: null, profile: null, loading: true }));

// The server says the sign-in is gone: nobody's data shows, and the sign-in page comes up.
setSignedOutHandler(() => {
  startViewing(null);
  useSession.setState({ user: null, profile: null });
});

/**
 * Calls [reset] whenever who's signed in changes (signing out, the sign-in running out, someone
 * else signing in), with the new person's id or null: for anything kept for the person, so the
 * next one never sees it.
 */
export function onUserChange(reset: (id: string | null) => void) {
  let last = useSession.getState().user?.id ?? null;
  useSession.subscribe((s) => {
    const id = s.user?.id ?? null;
    if (id === last) return;
    last = id;
    reset(id);
  });
}

onUserChange((id) => {
  // A share is only ever looked at by the person it was shared with.
  const share = useViewing.getState().share;
  if (share && share.viewerId !== id) startViewing(null);
  if (id) void claimPush(id);
  else forgetConversations();
});

/** How long signing out waits for the browser's push service before going ahead anyway. */
const PUSH_WAIT_MS = 4000;

export const session = {
  async start() {
    const [config, me] = await Promise.all([
      api.authConfig().catch(() => null),
      api.me().catch((e) => (e instanceof ApiError && e.status === 401 ? null : Promise.reject(e))).catch(() => null),
    ]);
    useSession.setState({ config, user: me?.user ?? null, loading: false });
    if (me) void session.loadProfile();
  },

  signedIn(user: User) {
    useSession.setState({ user });
    void session.loadProfile();
  },

  setUser(user: User) {
    useSession.setState({ user });
  },

  async loadProfile() {
    const asked = useSession.getState().user?.id;
    try {
      let profile = await api.profile();
      // The person's days begin and end where they are: the browser's time zone, until they choose one.
      if (!profile.timezone) {
        const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (zone) profile = await api.saveProfile({ ...profile, timezone: zone }).catch(() => profile);
      }
      // Only for whoever's still signed in.
      if (useSession.getState().user?.id === asked) useSession.setState({ profile });
    } catch {
      // The pages say what's wrong when they load.
    }
  },

  setProfile(profile: Profile) {
    useSession.setState({ profile });
  },

  /**
   * Signs out here: back to the person's own data, reminders in this browser stopped (while the
   * server still knows who's asking), the assistant's conversations forgotten, then the sign-in.
   */
  async signOut() {
    const user = useSession.getState().user;
    startViewing(null);
    if (user) {
      await Promise.race([
        disablePush(user.id).catch(() => undefined),
        new Promise((resolve) => setTimeout(resolve, PUSH_WAIT_MS)),
      ]);
    }
    forgetConversations();
    await api.logout().catch(() => undefined);
    useSession.setState({ user: null, profile: null });
  },
};
