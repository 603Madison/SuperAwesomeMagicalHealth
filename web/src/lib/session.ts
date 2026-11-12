import { create } from "zustand";
import { api, ApiError, setSignedOutHandler } from "./api";
import type { AuthConfig, Profile, User } from "./types";

interface Session {
  config: AuthConfig | null;
  user: User | null;
  profile: Profile | null;
  /** Still finding out whether someone is signed in. */
  loading: boolean;
}

export const useSession = create<Session>(() => ({ config: null, user: null, profile: null, loading: true }));

setSignedOutHandler(() => useSession.setState({ user: null, profile: null }));

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
    try {
      let profile = await api.profile();
      // The person's days begin and end where they are: the browser's time zone, until they choose one.
      if (!profile.timezone) {
        const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (zone) profile = await api.saveProfile({ ...profile, timezone: zone }).catch(() => profile);
      }
      useSession.setState({ profile });
    } catch {
      // The pages say what's wrong when they load.
    }
  },

  setProfile(profile: Profile) {
    useSession.setState({ profile });
  },

  async signOut() {
    await api.logout().catch(() => undefined);
    useSession.setState({ user: null, profile: null });
  },
};
