import { Children, useEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Link } from "react-router-dom";
import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { create } from "zustand";

// ---- Buttons ----

type Variant = "primary" | "tonal" | "ghost" | "danger";

export function Button({ variant = "tonal", busy, children, className = "", ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; busy?: boolean }) {
  return (
    <button {...rest} disabled={rest.disabled || busy} className={`btn btn-${variant} ${busy ? "is-busy" : ""} ${className}`}>
      {children}
    </button>
  );
}

export function IconButton({ label, children, className = "", active, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { label: string; active?: boolean }) {
  return (
    <button {...rest} aria-label={label} title={label} className={`icon-btn ${active ? "is-active" : ""} ${className}`}>
      {children}
    </button>
  );
}

// ---- Form fields ----

export function Field({ label, help, children }: { label: string; help?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {help && <span className="field-help">{help}</span>}
    </label>
  );
}

export function Toggle({ checked, onChange, label, help, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; help?: ReactNode; disabled?: boolean }) {
  return (
    <label className={`toggle ${disabled ? "is-disabled" : ""}`}>
      <span className="toggle-text">
        <span>{label}</span>
        {help && <span className="field-help">{help}</span>}
      </span>
      <input type="checkbox" role="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="toggle-track" aria-hidden />
    </label>
  );
}

// ---- Dialogs ----

export function Modal({ title, onClose, children, footer, wide, sheet, subtitle }: {
  title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean;
  /** On phones, a sheet from the bottom of the screen. */
  sheet?: boolean;
  subtitle?: ReactNode;
}) {
  const dialog = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close.current();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  // Focus goes into the dialog (unless something in it asked for it), and back where it was after.
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    if (!dialog.current?.contains(document.activeElement)) dialog.current?.focus();
    return () => { if (before && document.contains(before)) before.focus(); };
  }, []);
  return createPortal(
    <div className={`modal-backdrop ${sheet ? "has-sheet" : ""}`} onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={dialog} tabIndex={-1} className={`modal ${wide ? "modal-wide" : ""} ${sheet ? "modal-sheet" : ""}`} role="dialog" aria-modal aria-label={title}>
        <div className="modal-head">
          <div>
            <h2>{title}</h2>
            {subtitle && <p className="modal-subtitle">{subtitle}</p>}
          </div>
          <IconButton label="Close" onClick={onClose}><X size={20} /></IconButton>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

// ---- Menus ----

export interface MenuItem {
  label: string;
  icon?: ReactNode;
  onSelect?: () => void;
  danger?: boolean;
  /** A submenu, shown in place. */
  items?: MenuItem[];
  disabled?: boolean;
}

export function Menu({ trigger, items, label, buttonClass = "icon-btn" }: { trigger: ReactNode; items: MenuItem[]; label: string; buttonClass?: string }) {
  const [open, setOpen] = useState(false);
  const [stack, setStack] = useState<MenuItem[][]>([]);
  const [pos, setPos] = useState({ top: 0, left: 0, up: false });
  const button = useRef<HTMLButtonElement>(null);
  const list = stack.length ? stack[stack.length - 1] : items;

  useEffect(() => {
    if (!open) return;
    const close = () => { setOpen(false); setStack([]); };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    window.addEventListener("resize", close);
    document.addEventListener("scroll", close, true);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", close);
      document.removeEventListener("scroll", close, true);
    };
  }, [open]);

  const toggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (open) { setOpen(false); setStack([]); return; }
    const rect = button.current!.getBoundingClientRect();
    const up = rect.bottom > window.innerHeight - 280;
    setPos({ top: up ? rect.top : rect.bottom + 4, left: Math.min(rect.right, window.innerWidth - 8), up });
    setOpen(true);
  };

  return (
    <>
      <button ref={button} className={buttonClass} aria-label={label} title={label} aria-haspopup="menu" aria-expanded={open} onClick={toggle}>
        {trigger}
      </button>
      {open && createPortal(
        <div className="menu-backdrop" onMouseDown={() => { setOpen(false); setStack([]); }}>
          <div
            className={`menu ${pos.up ? "menu-up" : ""}`}
            role="menu"
            style={{ top: pos.up ? undefined : pos.top, bottom: pos.up ? window.innerHeight - pos.top + 4 : undefined, right: window.innerWidth - pos.left }}
            onMouseDown={(e) => e.stopPropagation()}
          >
            {stack.length > 0 && (
              <button className="menu-item menu-back" onClick={() => setStack(stack.slice(0, -1))}>‹ Back</button>
            )}
            {list.map((item, i) => (
              <button
                key={i}
                role="menuitem"
                disabled={item.disabled}
                className={`menu-item ${item.danger ? "is-danger" : ""}`}
                onClick={() => {
                  if (item.items) { setStack([...stack, item.items]); return; }
                  setOpen(false);
                  setStack([]);
                  item.onSelect?.();
                }}
              >
                {item.icon}
                <span>{item.label}</span>
                {item.items && <span className="menu-more">›</span>}
              </button>
            ))}
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}

// ---- Toasts ----

interface Toast {
  id: number;
  message: string;
  kind: "info" | "error";
}

const useToasts = create<{ toasts: Toast[] }>(() => ({ toasts: [] }));
let toastId = 0;

export function toast(message: string, kind: "info" | "error" = "info") {
  const id = ++toastId;
  useToasts.setState((s) => ({ toasts: [...s.toasts.slice(-3), { id, message, kind }] }));
  setTimeout(() => useToasts.setState((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), kind === "error" ? 6000 : 3500);
}

export function errorToast(e: unknown) {
  toast(e instanceof Error ? e.message : String(e), "error");
}

export function Toasts() {
  const toasts = useToasts((s) => s.toasts);
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.kind}`}>{t.message}</div>
      ))}
    </div>
  );
}

// ---- Bits ----

export function Spinner({ size = 20 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} aria-label="Loading" />;
}

export function Empty({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon && <div className="empty-icon">{icon}</div>}
      <h3>{title}</h3>
      {children && <div className="empty-text">{children}</div>}
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

/** Runs [load] when [deps] change; gives the result, any error, and a way to run it again. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): { data: T | undefined; error: string | null; loading: boolean; reload: () => void; setData: (d: T) => void } {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    load().then(
      (d) => { if (!cancelled) { setData(d); setError(null); setLoading(false); } },
      (e) => { if (!cancelled) { setError(e instanceof Error ? e.message : String(e)); setLoading(false); } },
    );
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, loading, reload: () => setTick((t) => t + 1), setData };
}

// ---- Health pieces ----

/**
 * A rounded card on the surface. Its heading row is always the same: the title (with its icon) on
 * the left; on the right, at most one action (a primary icon button), then a chevron that opens
 * the feature's page ([link], "Open Mind" for screen readers). A card with nothing in it is a slim
 * row: the title and its action.
 */
export function Card({ title, icon, action, link, children, className = "", onClick, id }: {
  title?: ReactNode; icon?: ReactNode; action?: ReactNode; link?: { to: string; label: string }; children?: ReactNode; className?: string; onClick?: () => void; id?: string;
}) {
  const slim = Children.toArray(children).length === 0;
  const head = (title || action || link) && (
    <div className="card-head">
      {title && <h3 className="card-heading">{icon}{title}</h3>}
      {(action || link) && (
        <div className="card-action">
          {action}
          {link && <Link className="icon-btn card-open" to={link.to} aria-label={link.label} title={link.label}><ChevronRight size={20} /></Link>}
        </div>
      )}
    </div>
  );
  if (onClick) {
    return (
      <section id={id} className={`panel-card is-clickable ${className}`} onClick={onClick} onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && (e.preventDefault(), onClick())} role="button" tabIndex={0}>
        {head}{children}
      </section>
    );
  }
  return <section id={id} className={`panel-card ${slim ? "is-slim" : ""} ${className}`}>{head}{children}</section>;
}

/** The one primary action in a card's heading row: a round icon button with its name for screen readers. */
export function CardAction({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return <button type="button" className="icon-btn card-primary" aria-label={label} title={label} onClick={onClick}>{children}</button>;
}

/** Choose one of a few: a row of pills. */
export function Segmented<T extends string | number>({ options, value, onChange, label }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <div className="tabs" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button key={String(o.value)} type="button" role="radio" aria-checked={o.value === value} className={o.value === value ? "is-active" : ""} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** ‹ Today › */
export function DateNav({ date, label, onChange, max }: { date: string; label: string; onChange: (d: string) => void; max: string }) {
  const shift = (days: number) => {
    const [y, m, d] = date.split("-").map(Number);
    const next = new Date(y, m - 1, d + days);
    const pad = (n: number) => String(n).padStart(2, "0");
    onChange(`${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}`);
  };
  return (
    <div className="date-nav">
      <IconButton label="Previous day" onClick={() => shift(-1)}><ChevronLeft size={20} /></IconButton>
      <label className="date-nav-label">
        <span>{label}</span>
        <input type="date" value={date} max={max} onChange={(e) => e.target.value && onChange(e.target.value)} aria-label="Choose a day" />
      </label>
      <IconButton label="Next day" disabled={date >= max} onClick={() => shift(1)}><ChevronRight size={20} /></IconButton>
    </div>
  );
}

/** A number with its label: the dashboard's small tiles. */
export function Stat({ label, value, unit, sub, icon }: { label: string; value: ReactNode; unit?: string; sub?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-label">{icon}{label}</div>
      <div className="stat-value">{value}{unit && <span className="stat-unit">{unit}</span>}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

export function ErrorNotice({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="notice notice-error notice-row" role="alert">
      <span>{message}</span>
      {onRetry && <Button variant="ghost" onClick={onRetry}>Try again</Button>}
    </div>
  );
}
