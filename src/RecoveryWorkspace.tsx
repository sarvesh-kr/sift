import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { invoke, isTauri } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleHelp,
  Database,
  Download,
  FileSearch,
  FolderOpen,
  HardDrive,
  History,
  LoaderCircle,
  Search,
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react";
import {
  FileThumbnail,
  PreviewProvider,
  useFileLayout,
  ViewOptions,
} from "./FilePresentation";
import { bytes } from "./model";
import type { Category, FileEntry } from "./model";
import { version } from "../package.json";

type RecoveryFile = {
  id: string;
  name: string;
  category: Category;
  format: string;
  offset: number;
  size: number;
  evidence: string;
  sha256: string;
  originalPath?: string | null;
  recoverable?: boolean;
};
type RecoveryReport = {
  source: string;
  sourceBytes: number;
  scannedBytes: number;
  files: RecoveryFile[];
  rejectedHeaders: number;
  cancelled: boolean;
  limited: boolean;
  elapsedMs: number;
  method: string;
};
type Progress = {
  bytes: number;
  total: number;
  found: number;
  phase: string;
  unit?: string;
};
const progressAmount = (value: number, unit?: string) =>
  unit === "records" ? `${value.toLocaleString()} records` : bytes(value);
type ExportResult = {
  directory: string;
  exported: { candidate: RecoveryFile; path: string }[];
  failed: { id: string; error: string }[];
  cancelled: boolean;
  manifest: string | null;
  warning: string | null;
};
const native = isTauri();
export function ModeHome({
  onSelect,
}: {
  onSelect: (mode: "clean" | "recover") => void;
}) {
  return (
    <main className="mode-home">
      <header className="mode-brand">
        <span className="brand-mark">
          <span />
          <span />
          <span />
        </span>
        <strong>
          sift<span>.</span>
        </strong>
        <span className="badge">v{version}</span>
      </header>
      <div className="mode-intro">
        <div className="eyebrow">YOUR SPACE. YOUR NEXT CHAPTER.</div>
        <h1>
          Make room.
          <br />
          <span>Bring something back.</span>
        </h1>
        <p>
          One thoughtful workspace for the files you have.
          <br />
          Another for the ones you’re looking for.
        </p>
      </div>
      <div className="mode-cards">
        <button
          className="mode-card"
          aria-label="Clean data"
          onClick={() => onSelect("clean")}
        >
          <span className="mode-icon">
            <Sparkles size={29} />
          </span>
          <span className="eyebrow">CLARITY, THEN SPACE</span>
          <h2>Clean data</h2>
          <p>
            Explore your storage, recognize your files, compare copies, and
            choose what goes.
          </p>
          <span className="mode-enter">
            Open cleanup workspace <ArrowRight size={19} />
          </span>
        </button>
        <button
          className="mode-card recovery-card"
          aria-label="Recover data"
          onClick={() => onSelect("recover")}
        >
          <span className="mode-icon">
            <History size={29} />
          </span>
          <span className="eyebrow">A SECOND LOOK</span>
          <h2>Recover data</h2>
          <p>
            Find permanently deleted NTFS files, review their original paths,
            and recover remaining contents to another drive.
          </p>
          <span className="mode-enter">
            Open recovery workspace <ArrowRight size={19} />
          </span>
        </button>
      </div>
      <footer className="mode-footer">
        <ShieldCheck size={16} />
        <span>On your device. No account. Every decision stays yours.</span>
      </footer>
    </main>
  );
}
function Dialog({
  title,
  onClose,
  children,
  disabled = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  disabled?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current;
    dialog?.showModal();
    const trap = (event: KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const elements = [
        ...(dialog?.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]',
        ) || []),
      ];
      if (!elements.length) {
        event.preventDefault();
        return;
      }
      if (event.shiftKey && document.activeElement === elements[0]) {
        event.preventDefault();
        elements.at(-1)?.focus();
      } else if (
        !event.shiftKey &&
        document.activeElement === elements.at(-1)
      ) {
        event.preventDefault();
        elements[0].focus();
      }
    };
    dialog?.addEventListener("keydown", trap);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      dialog?.removeEventListener("keydown", trap);
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="recovery-dialog"
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        if (!disabled) onClose();
      }}
    >
      <button
        className="icon-button modal-close"
        aria-label="Close recovery dialog"
        onClick={onClose}
        disabled={disabled}
      >
        <X size={20} />
      </button>
      <h2>{title}</h2>
      {children}
    </dialog>
  );
}
export function RecoveryWorkspace({
  active,
  onHome,
  recoveryOnly = false,
}: {
  active: boolean;
  onHome: () => void;
  recoveryOnly?: boolean;
}) {
  const [capabilities, setCapabilities] = useState<{
    driveAccess: boolean;
    canElevate?: boolean;
    recoveryOnly: boolean;
    drives: { id: string; label: string; filesystem?: string }[];
  }>({ driveAccess: false, recoveryOnly: false, drives: [] });
  const [drive, setDrive] = useState("");
  const [deep, setDeep] = useState(false);
  async function refreshDrives() {
    if (!native) return;
    const value = await invoke<typeof capabilities>("recovery_capabilities");
    if (value) {
      setCapabilities(value);
      setDrive((current) => {
        const next = value.drives.some((d) => d.id === current)
          ? current
          : value.drives[0]?.id || "";
        const fs = value.drives.find((d) => d.id === next)?.filesystem;
        if (!current && fs) setDeep(!fs.toLowerCase().includes("ntfs"));
        return next;
      });
    }
  }
  useEffect(() => {
    if (active) void refreshDrives().catch(() => {});
  }, [active]);
  const [report, setReport] = useState<RecoveryReport | null>(null);
  const resultsRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (report && active)
      resultsRef.current?.scrollIntoView({ block: "start", behavior: "auto" });
  }, [report]);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("All");
  const [layout, setLayout] = useFileLayout("sift-recovery-layout");
  const [limit, setLimit] = useState(60);
  const [notice, setNotice] = useState("");
  const [help, setHelp] = useState(false);
  const [review, setReview] = useState<string[] | null>(null);
  const [details, setDetails] = useState<RecoveryFile | null>(null);
  const [result, setResult] = useState<ExportResult | null>(null);
  useEffect(() => {
    if (!native) return;
    const listener = listen<Progress>("recovery-progress", (event) =>
      setProgress(event.payload),
    );
    return () => {
      void listener.then((stop) => stop());
    };
  }, []);
  useEffect(() => setLimit(60), [query, category, report]);
  const files = useMemo<FileEntry[]>(
    () =>
      report?.files.map((file) => ({
        path: file.id,
        name: file.name,
        size: file.size,
        category: file.category,
        modified: 0,
      })) || [],
    [report],
  );
  const byId = useMemo(
    () => new Map(files.map((file) => [file.path, file])),
    [files],
  );
  const visible = useMemo(
    () =>
      report?.files.filter(
        (file) =>
          (category === "All" || file.category === category) &&
          `${file.name} ${file.originalPath || ""} ${file.format} ${file.offset.toString(16)}`
            .toLowerCase()
            .includes(query.toLowerCase()),
      ) || [],
    [report, category, query],
  );
  const totalSelected =
    report?.files
      .filter((file) => selected.has(file.id))
      .reduce((sum, file) => sum + file.size, 0) || 0;
  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }
  async function scan(driveId?: string) {
    if (!native) {
      setHelp(true);
      return;
    }
    setBusy(true);
    setProgress(null);
    setNotice("");
    setResult(null);
    try {
      const next = await invoke<RecoveryReport | null>(
        driveId ? "scan_recovery_drive" : "scan_recovery_image",
        driveId ? { id: driveId, deep } : undefined,
      );
      if (next) {
        setReport(next);
        setSelected(new Set());
        setCategory("All");
        setQuery("");
      }
    } catch (error) {
      setNotice(String(error));
      setReport(null);
      setSelected(new Set());
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }
  async function closeImage() {
    if (busy) return;
    setBusy(true);
    try {
      await invoke("close_recovery_image");
      setReport(null);
      setSelected(new Set());
      setResult(null);
      setNotice("");
    } catch (error) {
      setNotice(String(error));
    } finally {
      setBusy(false);
    }
  }
  async function save() {
    if (!review?.length) return;
    setBusy(true);
    setProgress(null);
    setNotice("");
    setResult(null);
    try {
      const next = await invoke<ExportResult | null>("export_recovery_files", {
        ids: review,
      });
      if (next) {
        setResult(next);
        const done = new Set(next.exported.map((file) => file.candidate.id));
        setSelected(
          (current) => new Set([...current].filter((id) => !done.has(id))),
        );
        setReview(null);
      }
    } catch (error) {
      setNotice(String(error));
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }
  return (
    <PreviewProvider
      enabled={native && active && !busy}
      files={files}
      command="recovery_preview"
    >
      <div className="recovery-shell">
        <header className="recovery-topbar">
          <button
            className="text-button"
            onClick={onHome}
            disabled={busy || recoveryOnly}
          >
            <ArrowLeft size={16} />
            Switch workspace
          </button>
          <span className="recovery-wordmark">
            sift<span>.</span>{" "}
            <small>{recoveryOnly ? "RECOVERY ONLY" : "RECOVER DATA"}</small>
          </span>
          <button
            className="icon-button"
            aria-label="Recovery help"
            onClick={() => setHelp(true)}
          >
            <CircleHelp size={19} />
          </button>
        </header>
        <main className="recovery-main">
          <div className="recovery-hero">
            <div>
              <div className="eyebrow">LOOK BACK. MOVE FORWARD.</div>
              <h1>
                Some things deserve
                <br />a second chance.
              </h1>
              <p>
                Find what remains. Understand the evidence.
                <br />
                Recover copies without changing the source.
              </p>
            </div>
            <div className="recovery-orbit" aria-hidden="true">
              <div />
              <History size={74} />
              <span>
                <ShieldCheck size={17} />
                Read-only source
              </span>
            </div>
          </div>
          <div className="recovery-paths">
            <section className="recovery-path">
              <span className="recovery-step">
                01 / DELETED FILES ON A DRIVE
              </span>
              <HardDrive size={24} />
              <h2>Recover from your storage.</h2>
              <p>
                Read deleted filesystem records, including original filenames,
                folder paths, resident contents and fragmented data runs. Live
                files are excluded in NTFS record mode. Deep signature scanning
                supports six contiguous formats on other filesystems, with
                unknown deletion status.
              </p>
              {capabilities.driveAccess ? (
                <>
                  <label className="recovery-category">
                    Source drive{" "}
                    <select
                      aria-label="Recovery source drive"
                      value={drive}
                      onChange={(event) => {
                        setDrive(event.target.value);
                        const fs = capabilities.drives.find(
                          (d) => d.id === event.target.value,
                        )?.filesystem;
                        setDeep(
                          fs ? !fs.toLowerCase().includes("ntfs") : false,
                        );
                      }}
                      disabled={busy}
                    >
                      {capabilities.drives.map((d) => (
                        <option key={d.id} value={d.id}>
                          {d.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button
                    className="text-button"
                    disabled={busy}
                    onClick={() =>
                      void refreshDrives().catch((e) => setNotice(String(e)))
                    }
                  >
                    Refresh storage devices
                  </button>
                  <label className="recovery-category">
                    Scan method{" "}
                    <select
                      aria-label="Recovery scan method"
                      value={deep ? "deep" : "ntfs"}
                      disabled={busy}
                      onChange={(e) => setDeep(e.target.value === "deep")}
                    >
                      <option value="ntfs">
                        Deleted NTFS records · original names and paths
                      </option>
                      <option value="deep">
                        Deep signatures · any readable device · unknown deletion
                        status
                      </option>
                    </select>
                  </label>
                  <button
                    className="button primary"
                    onClick={() => void scan(drive)}
                    disabled={busy || !drive}
                  >
                    {deep ? "Deep scan storage" : "Scan deleted files"}{" "}
                    <FileSearch size={15} />
                  </button>
                  {capabilities.canElevate &&
                    !(capabilities.recoveryOnly || recoveryOnly) && (
                      <button
                        className="text-button"
                        disabled={busy}
                        onClick={() =>
                          void invoke("enable_recovery_drive_access").catch(
                            (e) => setNotice(String(e)),
                          )
                        }
                      >
                        Enable drive access (Windows administrator)
                      </button>
                    )}
                  <small>
                    {capabilities.canElevate
                      ? "Administrator consent opens a recovery-only window. Cleanup is disabled there."
                      : "Raw devices require OS disk-read permissions. If access is denied, launch the native app with your administrator tools and --sift-recovery-only."}{" "}
                    Save copies to another device.
                  </small>
                </>
              ) : (
                <p>
                  The native app lists local drives and connected devices.
                  Browsers cannot read deleted drive sectors.
                </p>
              )}
            </section>
            <details className="recovery-advanced">
              <summary>Advanced: disk-image tools</summary>
              <section className="recovery-path image-path">
                <span className="recovery-step">
                  ADVANCED / WORK FROM AN IMAGE
                </span>
                <Database size={24} />
                <h2>Scan NTFS filesystem records.</h2>
                <p>
                  Choose a local raw NTFS volume or disk image (.img, .dd,
                  .raw). Recover deleted files of any type when their records
                  and data runs survive.
                </p>
                <button
                  className="button primary"
                  onClick={() => void scan()}
                  disabled={busy}
                >
                  {busy ? (
                    <LoaderCircle className="spin" size={16} />
                  ) : (
                    <FileSearch size={16} />
                  )}{" "}
                  {report ? "Scan another image" : "Choose disk image"}
                </button>
              </section>
            </details>
          </div>
          <div className="recovery-context">
            <ShieldCheck size={19} />
            <p>
              <strong>Recovery starts with preserving what’s left.</strong> Stop
              writing to the affected drive and save recovered files to another
              physical device. Sift opens sources read-only. An active drive can
              still change through Windows or other apps; a stable image is
              safer. Sift does not create disk images.
            </p>
          </div>
          {busy && (
            <section className="recovery-progress" role="status">
              <div>
                <LoaderCircle size={18} className="spin" />
                <strong>
                  {progress?.phase || "Choose a source or destination"}
                </strong>
                <button
                  className="text-button"
                  onClick={() =>
                    void invoke("cancel_scan").catch((error) =>
                      setNotice(String(error)),
                    )
                  }
                >
                  Stop
                </button>
              </div>
              <progress
                value={progress?.bytes || 0}
                max={Math.max(1, progress?.total || 1)}
              />
              <p>
                {progressAmount(progress?.bytes || 0, progress?.unit)} /{" "}
                {progressAmount(progress?.total || 0, progress?.unit)} ·{" "}
                {progress?.found || 0} files
              </p>
            </section>
          )}
          {notice && (
            <div className="recovery-error" role="alert">
              {notice}
              <button
                className="icon-button"
                aria-label="Dismiss recovery message"
                onClick={() => setNotice("")}
              >
                <X size={16} />
              </button>
            </div>
          )}
          {result && (
            <section
              className="recovery-outcome"
              aria-label="Recovery export results"
            >
              <Check size={23} />
              <div>
                <h2>
                  {result.exported.length} verified{" "}
                  {result.exported.length === 1 ? "copy" : "copies"} saved
                  {result.cancelled ? " · stopped" : ""}
                </h2>
                <p className="recovery-path-text">{result.directory}</p>
                <p>
                  {result.manifest
                    ? "recovery-manifest.json records original paths, deleted-record evidence, outcomes, and SHA-256 hashes."
                    : "No manifest saved."}
                </p>
                {result.warning && <p role="alert">{result.warning}</p>}
                {result.failed.slice(0, 20).map((file) => (
                  <p key={file.id} role="alert">
                    {file.id}: {file.error}
                  </p>
                ))}
                {result.failed.length > 20 && (
                  <p>
                    {result.failed.length - 20} further failures are recorded in
                    the manifest.
                  </p>
                )}
              </div>
              <button
                className="icon-button"
                aria-label="Dismiss recovery results"
                onClick={() => setResult(null)}
              >
                <X size={17} />
              </button>
            </section>
          )}
          {report ? (
            <section
              className="recovery-results"
              ref={resultsRef}
              aria-label="Recovery candidates"
            >
              <div className="recovery-results-heading">
                <div>
                  <div className="eyebrow">FILES FOUND ON THIS SOURCE</div>
                  <h2>
                    {report.files.length.toLocaleString()} candidate files
                  </h2>
                  <p className="recovery-path-text">{report.source}</p>
                </div>
                <button
                  className="text-button"
                  disabled={busy}
                  onClick={() => void closeImage()}
                >
                  Close source
                </button>
                <span className="badge">
                  {report.cancelled
                    ? "Stopped · partial scan"
                    : report.limited
                      ? "Limit reached · partial scan"
                      : "Scan complete"}
                </span>
              </div>
              <div className="recovery-facts">
                <span>
                  <HardDrive size={15} />
                  {report.method === "ntfs-deleted-records-v1"
                    ? `${report.scannedBytes.toLocaleString()} file records examined`
                    : `${bytes(report.scannedBytes)} / ${bytes(report.sourceBytes)} scanned`}
                </span>
                <span>{(report.elapsedMs / 1000).toFixed(1)}s</span>
                <span>
                  {report.rejectedHeaders.toLocaleString()} unreadable or unused
                  records skipped
                </span>
              </div>
              <p className="recovery-evidence-note">
                {report.method === "ntfs-deleted-records-v1"
                  ? "These entries are marked deleted in NTFS. Original paths come from surviving metadata. Reused data clusters are blocked. Even unallocated bytes may be overwritten, zeroed by TRIM, or unreadable; recoverability is not a guarantee of intact contents."
                  : "Signature search: deleted status and original paths are unknown."}
              </p>
              {(report.cancelled || report.limited) && (
                <p className="recovery-evidence-note">
                  Partial results only. Limits: 512 MiB per file, 10,000
                  candidates, 50,000 header attempts, and a bounded
                  validation-read budget.
                </p>
              )}
              <div className="recovery-controls">
                <label className="search-input">
                  <Search size={17} />
                  <input
                    aria-label="Search recovery results"
                    placeholder="Original name, folder, format, or record offset"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                  />
                </label>
                <label className="recovery-category">
                  Category{" "}
                  <select
                    aria-label="Recovery category"
                    value={category}
                    onChange={(event) => setCategory(event.target.value)}
                  >
                    {[
                      "All",
                      ...new Set(report.files.map((file) => file.category)),
                    ].map((c) => (
                      <option key={c}>{c}</option>
                    ))}
                  </select>
                </label>
                <ViewOptions layout={layout} onChange={setLayout} />
              </div>
              <div className="recovery-selection">
                <span>
                  {visible.length.toLocaleString()} matches ·{" "}
                  {selected.size.toLocaleString()} selected (
                  {bytes(totalSelected)})
                </span>
                <button
                  className="text-button"
                  disabled={busy || !visible.length}
                  onClick={() =>
                    setSelected(
                      (current) =>
                        new Set([
                          ...current,
                          ...visible
                            .filter((file) => file.recoverable !== false)
                            .map((file) => file.id),
                        ]),
                    )
                  }
                >
                  Select all matches
                </button>
                <button
                  className="text-button"
                  disabled={busy || !selected.size}
                  onClick={() => setSelected(new Set())}
                >
                  Clear selection
                </button>
                <button
                  className="button primary"
                  disabled={busy || !selected.size}
                  onClick={() => setReview([...selected])}
                >
                  <Download size={15} />
                  Recover selected
                </button>
              </div>
              <div className={`recovery-files recovery-${layout}`}>
                {visible.slice(0, limit).map((file) => (
                  <article
                    key={file.id}
                    className={`recovery-file ${selected.has(file.id) ? "selected" : ""}`}
                  >
                    <input
                      type="checkbox"
                      aria-label={`Select ${file.name}`}
                      checked={selected.has(file.id)}
                      disabled={busy || file.recoverable === false}
                      onChange={() => toggle(file.id)}
                    />
                    <button
                      className="recovery-file-main"
                      onClick={() => setDetails(file)}
                      aria-label={`Inspect ${file.name}`}
                    >
                      <FileThumbnail
                        file={byId.get(file.id)!}
                        large={layout === "grid"}
                      />
                      <span>
                        <strong>{file.name}</strong>
                        <small>
                          {file.category} · {bytes(file.size)} · offset 0x
                          {file.offset.toString(16)}
                        </small>
                        <em>{file.originalPath || file.evidence}</em>
                        {file.originalPath && (
                          <em>
                            {file.recoverable === false ? "Unavailable: " : ""}
                            {file.evidence}
                          </em>
                        )}
                      </span>
                    </button>
                  </article>
                ))}
              </div>
              {!visible.length && (
                <div className="empty-state">
                  <FileSearch size={30} />
                  <h3>
                    {report.files.length
                      ? "No matching candidates."
                      : "No deleted NTFS entries found."}
                  </h3>
                  <p>
                    This does not prove the image contains no recoverable data.
                    Overwriting, encryption, unsupported NTFS features, and scan
                    limits affect these results.
                  </p>
                </div>
              )}
              {visible.length > limit && (
                <button
                  className="button secondary recovery-load"
                  onClick={() => setLimit((current) => current + 100)}
                >
                  Show more candidates ({visible.length - limit} remaining)
                </button>
              )}
            </section>
          ) : (
            <section className="recovery-empty">
              <FolderOpen size={30} />
              <h2>A careful search starts here.</h2>
              <p>
                Choose an image to see categorized candidates, file evidence,
                and local image previews. Select one file, several, or all
                matches to save verified copies.
              </p>
            </section>
          )}
          <footer className="recovery-footer">
            <ShieldCheck size={16} />
            <span>Device recovery · no upload · no source writes</span>
            <button className="text-button" onClick={() => setHelp(true)}>
              Capabilities & limits
              <ArrowRight size={14} />
            </button>
          </footer>
        </main>
        {details && (
          <Dialog title={details.name} onClose={() => setDetails(null)}>
            <FileThumbnail file={byId.get(details.id)!} large />
            <dl className="recovery-detail">
              <dt>Source offset</dt>
              <dd>
                {details.offset.toLocaleString()} bytes / 0x
                {details.offset.toString(16)}
              </dd>
              <dt>Candidate size</dt>
              <dd>{bytes(details.size, 2)}</dd>
              <dt>Evidence</dt>
              <dd>{details.evidence}</dd>
              <dt>SHA-256</dt>
              <dd>
                <code>
                  {details.sha256 ||
                    "Calculated and verified when recovering a copy"}
                </code>
              </dd>
              <dt>Original path / deletion status</dt>
              <dd>
                {details.originalPath
                  ? `${details.originalPath} · marked deleted in NTFS`
                  : "Unknown. Signature search only."}
              </dd>
            </dl>
            <button
              className="button primary full-width"
              disabled={busy || details.recoverable === false}
              onClick={() => {
                setReview([details.id]);
                setDetails(null);
              }}
            >
              Recover this file only
              <Download size={16} />
            </button>
          </Dialog>
        )}
        {review && (
          <Dialog
            title="Save a second copy."
            disabled={busy}
            onClose={() => setReview(null)}
          >
            <p>
              {review.length} selected candidates will be saved in a new
              Sift-recovered folder, grouped by category. Existing files are
              never intentionally replaced.
            </p>
            <p>
              Choose another physical drive when recovering from an affected
              disk. The source stays read-only. Copies are SHA-256 checked
              against a second source read and read back from the destination.
            </p>
            <p>
              Original paths and deleted-record evidence appear in a JSON
              manifest. Output names include a record ID to prevent collisions.
              Files may still be incomplete or unreadable.
            </p>
            {notice && (
              <p className="recovery-error" role="alert">
                {notice}
              </p>
            )}
            {busy && (
              <p role="status">
                {progress?.phase || "Choose a destination"} ·{" "}
                {progressAmount(progress?.bytes || 0, progress?.unit)} /{" "}
                {progressAmount(progress?.total || 0, progress?.unit)} ·{" "}
                {progress?.found || 0} copies
              </p>
            )}
            <div className="recovery-review-list">
              {review.slice(0, 30).map((id) => (
                <p key={id}>{byId.get(id)?.name}</p>
              ))}
              {review.length > 30 && (
                <p>and {review.length - 30} more candidates</p>
              )}
            </div>
            <button
              className="button primary full-width"
              disabled={busy}
              onClick={() => void save()}
            >
              {busy ? (
                <LoaderCircle className="spin" size={16} />
              ) : (
                <FolderOpen size={16} />
              )}
              Choose destination & save
            </button>
            {busy && (
              <button
                className="button secondary full-width"
                onClick={() =>
                  void invoke("cancel_scan").catch((error) =>
                    setNotice(String(error)),
                  )
                }
              >
                Stop recovery export
              </button>
            )}
          </Dialog>
        )}
        {help && (
          <Dialog title="What recovery can do." onClose={() => setHelp(false)}>
            <p>
              The NTFS undelete scanner reads deleted master-file-table records.
              It can recover resident data and follow fragmented or sparse runs,
              with original names and surviving directory paths. No bin is used
              for recovery.
            </p>
            <p>
              Direct NTFS drive access is available on Windows with
              administrator consent. NTFS images work on Windows, Linux and
              macOS. Files referencing reused clusters are blocked. Compressed
              or EFS-encrypted files, file/MFT extension records, FAT/exFAT,
              APFS, ext and locked BitLocker are unsupported. Limits are 10,000
              entries, 2 million records and 64 GiB per file. Paths with missing
              parent records are marked unknown.
            </p>
            <p>
              SSD TRIM, overwriting, encryption, and physical failure may make
              recovery impossible. Normal Android permissions do not provide raw
              access to encrypted internal storage. No software can promise to
              recover everything.
            </p>
            <p>
              PDF header/footer evidence and ZIP directory evidence do not
              verify document or archive contents. Open recovered files
              cautiously in an appropriate viewer. Sift never executes recovered
              data.
            </p>
            {!native && (
              <p>
                You’re viewing the browser interface. Disk-image scanning and
                export require the desktop app. No recovery results are
                simulated here.
              </p>
            )}
          </Dialog>
        )}
      </div>
    </PreviewProvider>
  );
}
