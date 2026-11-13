import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Archive, FileDown, FileText, Lock, Share2 } from "lucide-react";
import { useModules } from "../App";
import { api } from "../lib/api";
import { shiftDate, today } from "../lib/format";
import { SENSITIVE, type ReportSectionKey } from "../lib/types";
import { REPORT_SECTIONS } from "../components/features";
import { Button, Card, Field, PageHeader, Segmented, errorToast, toast } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";

type Range = "30" | "90" | "180" | "365" | "custom";

const RANGES: { value: Range; label: string }[] = [
  { value: "30", label: "30 days" }, { value: "90", label: "3 months" }, { value: "180", label: "6 months" }, { value: "365", label: "A year" }, { value: "custom", label: "Choose" },
];

function DateRange({ range, setRange, from, setFrom, to, setTo, everything }: {
  range: Range | "all"; setRange: (r: Range | "all") => void; from: string; setFrom: (d: string) => void; to: string; setTo: (d: string) => void; everything?: boolean;
}) {
  const options = everything ? [...RANGES.slice(0, 4), { value: "all" as const, label: "Everything" }, RANGES[4]] : RANGES;
  return (
    <div className="form-stack" style={{ gap: 10 }}>
      <Segmented<Range | "all"> label="How far back" value={range} onChange={(r) => {
        setRange(r);
        if (r !== "custom") { setTo(today()); setFrom(r === "all" ? "2000-01-01" : shiftDate(today(), -Number(r) + 1)); }
      }} options={options} />
      {range === "custom" && (
        <div className="two-col">
          <Field label="From"><input type="date" value={from} max={to} onChange={(e) => e.target.value && setFrom(e.target.value)} /></Field>
          <Field label="To"><input type="date" value={to} min={from} max={today()} onChange={(e) => e.target.value && setTo(e.target.value)} /></Field>
        </div>
      )}
    </div>
  );
}

export function Reports() {
  const [params] = useSearchParams();
  const on = useModules();
  const given = params.get("from") && params.get("to");
  const [range, setRange] = useState<Range | "all">(given ? "custom" : "90");
  const [from, setFrom] = useState(params.get("from") ?? shiftDate(today(), -89));
  const [to, setTo] = useState(params.get("to") ?? today());
  const offered = REPORT_SECTIONS.filter((s) => !s.module || on.includes(s.module));
  const [chosen, setChosen] = useState<ReportSectionKey[]>(offered.map((s) => s.id));
  const [busy, setBusy] = useState(false);

  const [exportRange, setExportRange] = useState<Range | "all">("all");
  const [exportFrom, setExportFrom] = useState("2000-01-01");
  const [exportTo, setExportTo] = useState(today());
  const [exporting, setExporting] = useState(false);
  const [fitting, setFitting] = useState(false);

  const toggle = (id: ReportSectionKey) => setChosen(chosen.includes(id) ? chosen.filter((s) => s !== id) : [...chosen, id]);

  const sections: Section[] = [
    { id: "report", label: "A report for your doctor", node: (
      <Card title="A report for your doctor" icon={<FileText size={18} />} className="span-2">
        <p className="muted">A few clear pages: averages, charts and what changed, for the time you choose. Print it, or bring it on your phone.</p>
        <DateRange range={range} setRange={setRange} from={from} setFrom={setFrom} to={to} setTo={setTo} />
        <Field label="What to include" help="Private features are in it only if you tick them; you choose what your doctor sees.">
          <div className="tag-list">
            {offered.map((s) => {
              const isOn = chosen.includes(s.id);
              const sensitive = s.module && SENSITIVE.includes(s.module);
              return (
                <button key={s.id} type="button" className={`chip ${isOn ? "is-on" : ""}`} aria-pressed={isOn} disabled={s.id === "OVERVIEW"} onClick={() => toggle(s.id)}>
                  {sensitive && <Lock size={14} />} {s.name}
                </button>
              );
            })}
          </div>
        </Field>
        <div className="button-row">
          <Button variant="primary" busy={busy} onClick={async () => {
            setBusy(true);
            try { await api.downloadReport(from, to, chosen.includes("OVERVIEW") ? chosen : ["OVERVIEW", ...chosen]); toast("Report downloaded"); } catch (e) { errorToast(e); }
            setBusy(false);
          }}><FileDown size={18} /> Download PDF</Button>
        </div>
      </Card>
    ) },
    { id: "export", label: "Export everything", node: (
      <Card title="Export everything" icon={<Archive size={18} />} className="span-2">
        <p className="muted">All your data as spreadsheet files (CSV), with a FHIR file: the standard many health record systems and apps can import. It's yours to keep, or to take somewhere else.</p>
        <DateRange everything range={exportRange} setRange={setExportRange} from={exportFrom} setFrom={setExportFrom} to={exportTo} setTo={setExportTo} />
        <p className="muted small"><Lock size={12} style={{ verticalAlign: -1 }} /> It includes your private features too, so keep the file somewhere safe.</p>
        <div className="button-row">
          <Button busy={exporting} onClick={async () => {
            setExporting(true);
            try { await api.downloadExport(exportFrom, exportTo); toast("Export downloaded"); } catch (e) { errorToast(e); }
            setExporting(false);
          }}><Archive size={18} /> Download .zip (CSV + FHIR)</Button>
        </div>
      </Card>
    ) },
    { id: "apps", label: "For other apps", node: (
      <Card title="For other apps" icon={<Share2 size={18} />} className="span-2">
        <ul className="export-list">
          <li>
            <div>
              <strong>Garmin Connect, Strava and others</strong>
              <p className="muted small">Workouts, and weight with body fat, as FIT files (the format sports apps import), for the export's dates.</p>
            </div>
            <Button busy={fitting} onClick={async () => {
              setFitting(true);
              try { await api.downloadFit(exportFrom, exportTo); toast("FIT files downloaded"); } catch (e) { errorToast(e); }
              setFitting(false);
            }}><FileDown size={18} /> Download FIT files</Button>
          </li>
          <li>
            <div>
              <strong>Health Connect (Android)</strong>
              <p className="muted small">The phone app can write to Health Connect, which Samsung Health, Google Fit and others read: turn it on in the app's settings and choose what to share.</p>
            </div>
          </li>
          <li>
            <div>
              <strong>Apple Health</strong>
              <p className="muted small">Not available: Apple Health only takes data from an iPhone app, and there isn't one.</p>
            </div>
          </li>
        </ul>
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("reports"), "Reports", sections);

  return (
    <div className="page">
      <PageHeader title="Reports & export" subtitle="A summary for your doctor, or all your data to keep" actions={arranged.button} />
      {arranged.render()}
    </div>
  );
}
