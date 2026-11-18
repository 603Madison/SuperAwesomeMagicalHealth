import { api } from "../lib/api";
import type { DoseLog } from "../lib/types";
import { errorToast, toast } from "./ui";

/**
 * Says a dose was marked, with Undo for a few seconds: wherever a dose is marked (Today, Care, +,
 * Medications), a tap too many can be taken back. [changed] reloads what showed it.
 */
export function markedToast(message: string, saved: DoseLog, changed: () => void) {
  const id = saved.id;
  toast(message, "info", id ? {
    label: "Undo",
    run: async () => {
      try {
        await api.deleteDose(id);
        toast("Undone");
        changed();
      } catch (e) { errorToast(e); }
    },
  } : undefined);
}
