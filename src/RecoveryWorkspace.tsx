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
type Progress = { bytes: number; total: number; found: number; phase: string };
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
            Start with trash, or search a disk image for recognizable file
            contents. Review the evidence. Save a copy.
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
}: {
  active: boolean;
  onHome: () => void;
}) {
  const [report, setReport] = useState<RecoveryReport | null>(null);
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
          `${file.name} ${file.format} ${file.offset.toString(16)}`
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
  async function scan() {
    if (!native) {
      setHelp(true);
      return;
    }
    setBusy(true);
    setProgress(null);
    setNotice("");
    setResult(null);
    try {
      const next = await invoke<RecoveryReport | null>("scan_recovery_image");
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
  async function trash() {
    if (!native) {
      setHelp(true);
      return;
    }
    try {
      await invoke("open_system_trash");
    } catch (error) {
      setNotice(String(error));
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
          <button className="text-button" onClick={onHome} disabled={busy}>
            <ArrowLeft size={16} />
            Switch workspace
          </button>
          <span className="recovery-wordmark">
            sift<span>.</span> <small>RECOVER DATA</small>
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
              <span className="recovery-step">01 / START HERE</span>
              <History size={24} />
              <h2>Still in the trash?</h2>
              <p>
                Recycle Bin / system trash keeps original file names and
                locations. Restore these through your system file manager.
              </p>
              <button
                className="button secondary"
                onClick={() => void trash()}
                disabled={busy}
              >
                Open system trash
                <ArrowRight size={15} />
              </button>
            </section>
            <section className="recovery-path image-path">
              <span className="recovery-step">02 / SEARCH AN IMAGE</span>
              <Database size={24} />
              <h2>Look for file contents.</h2>
              <p>
                Scan a local raw disk image (.img, .dd, .raw). Search for
                contiguous JPEG, PNG, WebP, PDF, ZIP, and WAV files.
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
          </div>
          <div className="recovery-context">
            <ShieldCheck size={19} />
            <p>
              <strong>Recovery starts with preserving what’s left.</strong> Stop
              writing to the affected drive. Work from an image made with a
              disk-imaging tool and save recovered files to another physical
              drive. Sift does not create disk images or access live raw disks.
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
                {bytes(progress?.bytes || 0)} / {bytes(progress?.total || 0)} ·{" "}
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
                    ? "recovery-manifest.json records offsets, format evidence, and SHA-256 hashes."
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
              aria-label="Recovery candidates"
            >
              <div className="recovery-results-heading">
                <div>
                  <div className="eyebrow">WHAT THE IMAGE CONTAINS</div>
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
                  Close image
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
                  {bytes(report.scannedBytes)} / {bytes(report.sourceBytes)}{" "}
                  scanned
                </span>
                <span>{(report.elapsedMs / 1000).toFixed(1)}s</span>
                <span>
                  {report.rejectedHeaders.toLocaleString()} incomplete or
                  unrecognized headers excluded
                </span>
              </div>
              <p className="recovery-evidence-note">
                These are byte ranges, not a list of proven deleted files.
                Existing files may appear too. Names are generated from offsets;
                original names and folders are unknown. Format checks do not
                guarantee readable contents. PDF results stop at the first
                footer; later revisions may be missing.
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
                    placeholder="Name, format, or hex offset"
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
                          ...visible.map((file) => file.id),
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
                      disabled={busy}
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
                        <em>{file.evidence}</em>
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
                      : "No supported file boundaries found."}
                  </h3>
                  <p>
                    This does not prove the image contains no recoverable data.
                    Fragmentation, encryption, unsupported formats, and scan
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
            <span>
              Contiguous carving preview · no upload · no source writes
            </span>
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
                <code>{details.sha256}</code>
              </dd>
              <dt>Original path / deletion status</dt>
              <dd>Unknown. Generated name; contiguous bytes only.</dd>
            </dl>
            <button
              className="button primary full-width"
              disabled={busy}
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
              disk. The source image stays read-only. Copied bytes are SHA-256
              checked against the scan and read back from the destination.
            </p>
            <p>
              Generated names and format evidence appear in a JSON manifest. No
              guarantee is made that a candidate was deleted or will open
              correctly.
            </p>
            {notice && (
              <p className="recovery-error" role="alert">
                {notice}
              </p>
            )}
            {busy && (
              <p role="status">
                {progress?.phase || "Choose a destination"} ·{" "}
                {bytes(progress?.bytes || 0)} / {bytes(progress?.total || 0)} ·{" "}
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
              System trash can restore files that remain there, using the
              operating system’s controls. The separate image scan searches
              regular, local raw disk-image files for contiguous JPEG, PNG,
              WebP, PDF, ZIP, and WAV byte ranges.
            </p>
            <p>
              This release does not parse NTFS, FAT, exFAT, APFS, or ext
              metadata, rebuild fragmented files, recover original names or
              folders, decrypt data, or create images. ZIP64, split archives,
              incomplete files, and files above 512 MiB are unsupported. Source
              images must remain stable.
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
