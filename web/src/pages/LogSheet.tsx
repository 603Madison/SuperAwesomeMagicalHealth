import { useId } from "react";
import { ArrowUpDown, MoreHorizontal, type LucideIcon } from "lucide-react";
import type { Module, QuickAction } from "../lib/types";
import { Button, Modal } from "../components/ui";

/*
 * Logging from Today: the quick row (a few round buttons, then More) and the + Log sheet with
 * everything that can be logged for the features that are on, grouped by feature.
 */

/** Something to log: its shared id (QuickAction), what it's called, and what it does. */
export interface LogAction {
  id: QuickAction;
  module: Module | null;
  /** In the sheet, and in Arrange. */
  label: string;
  /** Under its round button in the quick row: a word or two. */
  short: string;
  /** For screen readers in the quick row, when [label] doesn't contain [short]. */
  name?: string;
  icon: LucideIcon;
  run: () => void;
  /** Already done today (the check-in): the button shows as on. */
  on?: boolean;
}

/** A feature's actions in the sheet. */
export interface LogGroup {
  name: string;
  actions: LogAction[];
}

/** Today's quick row: up to five round buttons with a word under each, then More (the sheet). Never wraps. */
export function QuickRow({ actions, onMore }: { actions: LogAction[]; onMore: () => void }) {
  const title = useId();
  return (
    <section className="quick-log" aria-labelledby={title}>
      <h2 id={title} className="sr-only">Quick log</h2>
      <ul className="quick-row">
        {actions.map((a) => (
          <li key={a.id}>
            <button type="button" className={`quick-btn ${a.on ? "is-on" : ""}`} onClick={a.run} aria-label={a.name ?? a.label} title={a.label}>
              <span className="quick-icon" aria-hidden="true"><a.icon size={22} /></span>
              <span className="quick-label" aria-hidden="true">{a.short}</span>
            </button>
          </li>
        ))}
        <li>
          <button type="button" className="quick-btn quick-more" onClick={onMore} aria-haspopup="dialog" aria-label="More to log" title="Everything you can log">
            <span className="quick-icon" aria-hidden="true"><MoreHorizontal size={22} /></span>
            <span className="quick-label" aria-hidden="true">More</span>
          </button>
        </li>
      </ul>
    </section>
  );
}

/** + Log: every action for the features that are on, by feature. Choosing one closes the sheet and does it. */
export function LogSheet({ groups, onClose, onArrange }: { groups: LogGroup[]; onClose: () => void; onArrange: () => void }) {
  return (
    <Modal sheet title="Log" onClose={onClose} footer={
      <Button variant="ghost" onClick={() => { onClose(); onArrange(); }}><ArrowUpDown size={18} /> Arrange the quick row</Button>
    }>
      {groups.map((g) => (
        <section key={g.name} className="log-group">
          <h3 className="log-group-title">{g.name}</h3>
          <ul className="log-chips">
            {g.actions.map((a) => (
              <li key={a.id}>
                <button type="button" className="log-chip" onClick={() => { onClose(); a.run(); }}>
                  <a.icon size={18} aria-hidden="true" /> {a.label}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </Modal>
  );
}
