import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { ArrowDown, ArrowUp, ArrowUpDown, GripVertical } from "lucide-react";
import { arrange, type ScreenLayout } from "../lib/types";
import { mergeOrder, resetScreen, saveScreen, useScreenLayout } from "../lib/layout";
import { useReadOnly } from "../lib/viewing";
import { Button, Modal, errorToast } from "./ui";

/*
 * Arranging a screen: the person moves its sections up and down and hides the ones they don't
 * use, from the Arrange button in the page's header. Saved on the server (lib/layout.ts) and shown
 * at once.
 *
 * A page lists its sections in the default order, each with a stable id (saved: keep it), a name
 * for the Arrange panel, whether it's offered at all (its feature or field is on), and what it
 * shows (null when there's nothing right now). `useArrange` gives back the sections to show, in
 * the person's order, and the button (with its panel). Keys: layoutKey("food") for the web's own
 * pages; LAYOUT_TODAY, LAYOUT_QUICK and LAYOUT_NAV are shared with the phone app.
 */

/** Something the Arrange panel lists: a stable id (saved) and its name. [group] keeps it among its own. */
export interface ArrangeItem {
  id: string;
  label: string;
  group?: string;
}

export interface Section extends ArrangeItem {
  /** Offered at all (its feature or field is on). Listed in Arrange even while it has nothing to show. */
  when?: boolean;
  /** What it shows: a card, or a few in a fragment. Null or false when there's nothing right now. */
  node: ReactNode;
}

const COULDNT_SAVE = "Couldn't save that. Check the connection and try again.";

/** The sections of a screen to show, in the person's order, with the Arrange button for its header. */
export function useArrange(screen: string, title: string, sections: Section[], options: { groups?: string[] } = {}) {
  const saved = useScreenLayout(screen);
  const readOnly = useReadOnly();
  const [open, setOpen] = useState(false);
  const offered = sections.filter((s) => s.when !== false);
  const hidden = new Set(saved?.hidden ?? []);
  const shown = arrange(offered, (s) => s.id, saved).filter((s) => !hidden.has(s.id));
  const button = readOnly || offered.length === 0 ? null : (
    <>
      <ArrangeButton title={title} onClick={() => setOpen(true)} />
      {open && <ArrangePanel screen={screen} title={title} items={offered} groups={options.groups} onClose={() => setOpen(false)} />}
    </>
  );
  const nodes = shown.filter((s) => s.node != null && s.node !== false).map((s) => <Fragment key={s.id}>{s.node}</Fragment>);
  const allHidden = offered.length > 0 && shown.length === 0;
  return {
    button,
    shown,
    nodes,
    /** The sections in a grid, or a line saying everything's hidden. */
    render: (className = "grid grid-2") => (
      <>
        {allHidden && !readOnly && <p className="muted arranged-empty">Everything here is hidden. <button type="button" className="link-btn" onClick={() => setOpen(true)}>Arrange</button> to show it again.</p>}
        {nodes.length > 0 && <div className={`${className} arranged`}>{nodes}</div>}
      </>
    ),
  };
}

/** A link like /food#drinks lands on that section once the page has it ([ready]). */
export function useHashScroll(ready: boolean) {
  const { hash } = useLocation();
  useEffect(() => {
    if (!ready || !hash) return;
    const id = decodeURIComponent(hash.slice(1));
    const timer = setTimeout(() => document.getElementById(id)?.scrollIntoView({ block: "start", behavior: "smooth" }), 60);
    return () => clearTimeout(timer);
  }, [ready, hash]);
}

/** The header's Arrange button: an icon and "Arrange". Hidden while viewing someone's share. */
export function ArrangeButton({ title, onClick, compact }: { title: string; onClick: () => void; compact?: boolean }) {
  if (useReadOnly()) return null;
  return (
    <Button variant="ghost" className={`arrange-btn ${compact ? "is-compact" : ""}`} onClick={onClick} aria-haspopup="dialog" aria-label={`Arrange ${title}`} title={`Arrange ${title}`}>
      <ArrowUpDown size={18} /> <span>Arrange</span>
    </Button>
  );
}

/**
 * The panel: each section with up and down, a switch to show or hide it, and a handle to drag it
 * (mouse or touch). Everything works with the buttons, for keyboards and screen readers.
 * Changes save as they're made; "Put back as it was" resets the screen.
 *
 * [defaultHidden]: what's hidden until the person arranges the screen (the quick actions past the
 * first few). [hiddenText]: what hiding does here ("Hidden", or "Under More").
 */
export function ArrangePanel({ screen, title, items, groups, onClose, defaultHidden = [], hiddenText = "Hidden", showLabel = (label) => `Show ${label}`, intro, top }: {
  screen: string; title: string; items: ArrangeItem[]; groups?: string[]; onClose: () => void;
  defaultHidden?: string[]; hiddenText?: string; showLabel?: (label: string) => string; intro?: ReactNode;
  /** Above the list: a switch between related screens (Today's cards and its quick actions). */
  top?: ReactNode;
}) {
  const saved = useScreenLayout(screen);
  const ordered = arrange(items, (i) => i.id, saved);
  const hiddenNow = new Set(saved ? saved.hidden : defaultHidden);
  const [said, setSaid] = useState("");
  const [drag, setDrag] = useState<{ id: string; to: number } | null>(null);
  const rows = useRef(new Map<string, HTMLLIElement>());
  const sectionGroups = groups ?? [undefined as unknown as string];
  const inGroup = (g: string | undefined) => ordered.filter((i) => (groups ? i.group === g : true));

  const commit = (order: ArrangeItem[], hidden: Set<string>) => {
    const ids = order.map((i) => i.id);
    const offered = new Set(ids);
    // Sections not offered now (a feature that's off, or one only the phone app has) keep their place and state.
    const next: ScreenLayout = {
      order: mergeOrder(saved?.order ?? [], ids),
      hidden: [...(saved?.hidden ?? []).filter((id) => !offered.has(id)), ...ids.filter((id) => hidden.has(id))],
    };
    saveScreen(screen, next).catch(() => errorToast(COULDNT_SAVE));
  };

  /** The whole order with [group]'s items replaced by [groupOrder]. */
  const withGroup = (group: string | undefined, groupOrder: ArrangeItem[]) => {
    const queue = [...groupOrder];
    return ordered.map((i) => (!groups || i.group === group ? queue.shift()! : i));
  };

  const move = (item: ArrangeItem, delta: -1 | 1) => {
    const list = inGroup(item.group);
    const at = list.indexOf(item);
    const to = at + delta;
    if (to < 0 || to >= list.length) return;
    const next = [...list];
    next.splice(at, 1);
    next.splice(to, 0, item);
    commit(withGroup(item.group, next), hiddenNow);
    setSaid(`${item.label}: ${to + 1} of ${list.length}`);
  };

  const toggle = (item: ArrangeItem, show: boolean) => {
    const hidden = new Set(hiddenNow);
    if (show) hidden.delete(item.id); else hidden.add(item.id);
    commit(ordered, hidden);
    setSaid(`${item.label}: ${show ? "shown" : hiddenText.toLowerCase()}`);
  };

  // Dragging by the handle, with a mouse or a finger: the line shows where it will go.
  const dropAt = (item: ArrangeItem, y: number) => {
    let to = 0;
    for (const other of inGroup(item.group)) {
      if (other.id === item.id) continue;
      const r = rows.current.get(other.id)?.getBoundingClientRect();
      if (r && y > r.top + r.height / 2) to++;
    }
    return to;
  };
  const endDrag = (item: ArrangeItem) => {
    if (!drag) return;
    const list = inGroup(item.group).filter((i) => i.id !== item.id);
    list.splice(drag.to, 0, item);
    setDrag(null);
    if (list.some((i, n) => i !== inGroup(item.group)[n])) {
      commit(withGroup(item.group, list), hiddenNow);
      setSaid(`${item.label}: ${drag.to + 1} of ${list.length}`);
    }
  };

  return (
    <Modal sheet title={`Arrange ${title}`} onClose={onClose} subtitle={intro ?? "Move what matters most to the top, and hide what you don't use."} footer={<>
      <Button variant="ghost" disabled={!saved} onClick={() => { resetScreen(screen).catch(() => errorToast(COULDNT_SAVE)); setSaid("Put back as it was"); }}>Put back as it was</Button>
      <Button variant="primary" onClick={onClose}>Done</Button>
    </>}>
      {top}
      <p className="sr-only" aria-live="polite">{said}</p>
      {sectionGroups.map((g) => {
        const list = inGroup(g);
        if (list.length === 0) return null;
        const others = drag ? list.filter((i) => i.id !== drag.id) : list;
        return (
          <section key={g ?? "all"} className="arrange-group" aria-label={g}>
            {groups && <h3 className="arrange-group-title">{g}</h3>}
            <ul className="arrange-list">
              {list.map((item, n) => {
                const isHidden = hiddenNow.has(item.id);
                const dragging = drag?.id === item.id;
                // The drop line: above the row that will follow the dragged one, or below the last.
                const lineBefore = drag && !dragging && others.indexOf(item) === drag.to;
                const lineAfter = drag && !dragging && drag.to === others.length && item === others[others.length - 1];
                return (
                  <li key={item.id} ref={(el) => { if (el) rows.current.set(item.id, el); else rows.current.delete(item.id); }}
                    className={`arrange-row ${isHidden ? "is-hidden" : ""} ${dragging ? "is-dragging" : ""} ${lineBefore ? "drop-before" : ""} ${lineAfter ? "drop-after" : ""}`}>
                    <span className="arrange-handle" aria-hidden="true"
                      onPointerDown={(e) => { e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId); setDrag({ id: item.id, to: n }); }}
                      onPointerMove={(e) => { if (drag?.id === item.id) { const to = dropAt(item, e.clientY); if (to !== drag.to) setDrag({ id: item.id, to }); } }}
                      onPointerUp={() => endDrag(item)}
                      onPointerCancel={() => setDrag(null)}>
                      <GripVertical size={18} />
                    </span>
                    <span className="arrange-name">
                      <span>{item.label}</span>
                      {isHidden && <span className="arrange-state">{hiddenText}</span>}
                    </span>
                    <button type="button" className="icon-btn" aria-label={`Move ${item.label} up`} title="Move up" aria-disabled={n === 0} onClick={() => move(item, -1)}><ArrowUp size={18} /></button>
                    <button type="button" className="icon-btn" aria-label={`Move ${item.label} down`} title="Move down" aria-disabled={n === list.length - 1} onClick={() => move(item, 1)}><ArrowDown size={18} /></button>
                    <label className="toggle arrange-switch">
                      <input type="checkbox" role="switch" checked={!isHidden} aria-label={showLabel(item.label)} onChange={(e) => toggle(item, e.target.checked)} />
                      <span className="toggle-track" aria-hidden />
                    </label>
                  </li>
                );
              })}
            </ul>
          </section>
        );
      })}
    </Modal>
  );
}
