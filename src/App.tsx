import { useEffect, useMemo, useRef, useState } from 'react';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { ArrowDown, ArrowDownToLine, ArrowRight, ArrowUpDown, AudioLines, Check, CheckCheck, ChevronDown, ChevronRight, CircleHelp, Code2, Copy, File, FileArchive, FileImage, FileText, Film, Folder, FolderOpen, HardDrive, LayoutDashboard, LoaderCircle, Monitor, MoreHorizontal, Plus, Search, Settings2, ShieldCheck, SlidersHorizontal, Sparkles, Trash2, X } from 'lucide-react';
import { bytes, categories, colors, demoReport, relativeDate } from './model';
import TreeMap from './TreeMap';
import { ModeHome, RecoveryWorkspace } from './RecoveryWorkspace';
import { FileThumbnail, PreviewProvider, useFileLayout, ViewOptions } from './FilePresentation';
import CleanupCenter from './CleanupCenter';
import StorageExplorer from './StorageExplorer';
import DuplicateReview from './DuplicateReview';
import CleanupResults from './CleanupResults';
import CleanupOptions, { cleanupLabels, cleanupVerbs, confirmationPhrase } from './CleanupOptions';
import { cleanupInsights, duplicateGroups, inFolder, lastCopyConflicts, removeEntries } from './analysis';
import type { DuplicateStats, Insight, InsightId } from './analysis';
import type { Category, CleanupMode, FileEntry, Progress, ScanReport, TrashResult } from './model';
import { version } from '../package.json';

type Page = 'Overview' | 'Cleanup center' | 'Storage explorer' | 'All files' | 'Duplicates' | 'Cleanup review';
type Filter = 'all' | 'large' | 'old-installers' | Category;
const native = isTauri();
const categoryIcons = { Videos: Film, Images: FileImage, Documents: FileText, Audio: AudioLines, Archives: FileArchive, Applications: Monitor, Code: Code2, Other: File };
const navigation = [{ name: 'Overview', icon: LayoutDashboard }, { name: 'Cleanup center', icon: Sparkles }, { name: 'Storage explorer', icon: HardDrive }, { name: 'All files', icon: FolderOpen }, { name: 'Duplicates', icon: Copy }, { name: 'Cleanup review', icon: Trash2 }] as const;

export default function App() {
  const [mode, setMode] = useState<'home' | 'clean' | 'recover'>('home');
  const [visited, setVisited] = useState({clean: false, recover: false});
  const [recoveryOnly,setRecoveryOnly]=useState(false);
  useEffect(()=>{if(native) void invoke<boolean>('recovery_launch_mode').then(c=>{if(c){setRecoveryOnly(true);setVisited({clean:false,recover:true});setMode('recover');}}).catch(()=>{});},[]);
  function open(next: 'clean' | 'recover') { setVisited(current => ({...current, [next]: true})); setMode(next); }
  return <>{mode === 'home' && !recoveryOnly && <ModeHome onSelect={open}/>}
    {visited.clean && <div hidden={mode !== 'clean'}><CleanWorkspace active={mode === 'clean'} onHome={() => setMode('home')}/></div>}
    {visited.recover && <div hidden={mode !== 'recover'}><RecoveryWorkspace recoveryOnly={recoveryOnly} active={mode === 'recover'} onHome={() => {if(!recoveryOnly)setMode('home');}}/></div>}
  </>;
}
function CleanWorkspace({active, onHome}: {active: boolean; onHome: () => void}) {
  const [layout, setLayout] = useFileLayout();
  const [report, setReport] = useState<ScanReport>(demoReport);
  const [preview, setPreview] = useState(true);
  const [page, setPage] = useState<Page>('Overview');
  const [insightId, setInsightId] = useState<InsightId | null>(null);
  const [folderScope, setFolderScope] = useState<string | null>(null);
  const [comparisonStats, setComparisonStats] = useState<DuplicateStats | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<'size' | 'name' | 'modified'>('size');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [settings, setSettings] = useState(false);
  const [help, setHelp] = useState(false);
  const [notice, setNotice] = useState('');
  const [details, setDetails] = useState<FileEntry | null>(null);
  const [limit, setLimit] = useState(30);
  const [duplicatesChecked, setDuplicatesChecked] = useState(true);
  const [cleaning, setCleaning] = useState(false);
  const [cleanupResult, setCleanupResult] = useState<TrashResult | null>(null);
  const [cleanupMode, setCleanupMode] = useState<CleanupMode>('recycle');
  const [cleanupConfirmation, setCleanupConfirmation] = useState('');
  const [allowAllCopies, setAllowAllCopies] = useState(false);
  const [needsRescan, setNeedsRescan] = useState(false);
  useEffect(() => { setCleanupConfirmation(''); setAllowAllCopies(false); }, [cleanupMode, selected]);
  const [reducedMotion, setReducedMotion] = useState(() => localStorage.getItem('sift-reduced-motion') === 'true');
  const search = useRef<HTMLInputElement>(null);

  useEffect(() => {
    document.documentElement.dataset.reducedMotion = String(reducedMotion);
    localStorage.setItem('sift-reduced-motion', String(reducedMotion));
  }, [reducedMotion]);
  useEffect(() => {
    if (!native) return;
    const unlisten = listen<Progress>('scan-progress', event => setProgress(event.payload));
    return () => { void unlisten.then(stop => stop()); };
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timeout = window.setTimeout(() => setNotice(''), 6500);
    return () => window.clearTimeout(timeout);
  }, [notice]);
  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === 'k') { event.preventDefault(); search.current?.focus(); }
      if (event.key === 'Escape' && !cleaning) { setConfirm(false); setSettings(false); setHelp(false); setDetails(null); }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [cleaning]);
  useEffect(() => { setLimit(30); }, [filter, query, page]);
  const modalOpen = confirm || settings || help || Boolean(details);
  useEffect(() => {
    if (!modalOpen) return;
    const previous = document.activeElement as HTMLElement | null;
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]');
    const focusable = () => [...(dialog?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]') || [])];
    focusable()[0]?.focus();
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const elements = focusable();
      if (!elements.length) { event.preventDefault(); return; }
      if (event.shiftKey && document.activeElement === elements[0]) { event.preventDefault(); elements.at(-1)?.focus(); }
      else if (!event.shiftKey && document.activeElement === elements.at(-1)) { event.preventDefault(); elements[0].focus(); }
    };
    document.addEventListener('keydown', trap);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.removeEventListener('keydown', trap); document.body.style.overflow = overflow; previous?.focus(); };
  }, [modalOpen, confirm, settings, help, details]);

  const summary = useMemo(() => {
    const byCategory = new Map(categories.map(category => [category, { category, size: 0, count: 0 }]));
    const large: FileEntry[] = [];
    const installers: FileEntry[] = [];
    let total = 0;
    for (const file of report.files) {
      total += file.size;
      const group = byCategory.get(file.category)!;
      group.size += file.size; group.count++;
      if (file.size >= 1e9) large.push(file);
      if (file.category === 'Applications' && /\.(exe|msi|dmg|pkg|deb|rpm|appimage)$/i.test(file.name) && file.modified > 0 && file.modified < Date.now() - 180 * 86400000) installers.push(file);
    }
    return { total, breakdown: [...byCategory.values()].sort((a, b) => b.size - a.size), large, installers, installerPaths: new Set(installers.map(file => file.path)) };
  }, [report.files]);
  const { total, breakdown, large, installers, installerPaths } = summary;
  const groups = useMemo(() => duplicateGroups(report.files), [report.files]);
  const insights = useMemo(() => cleanupInsights(report), [report]);
  const activeInsight = insights.find(insight => insight.id === insightId);
  const insightPaths = useMemo(() => insightId ? new Set(activeInsight?.files.map(file => file.path) || []) : null, [insightId, activeInsight]);
  const conflicts = useMemo(() => lastCopyConflicts(groups, selected), [groups, selected]);
  const duplicatePaths = useMemo(() => new Set(groups.flat().map(file => file.path)), [groups]);
  const duplicateBytes = groups.reduce((sum, group) => sum + group[0].size * (group.length - 1), 0);
  const selectedFiles = report.files.filter(file => selected.has(file.path));
  const selectedBytes = selectedFiles.reduce((sum, file) => sum + file.size, 0);
  const reviewSelection = page === 'Cleanup review' ? selected : null;
  const visible = useMemo(() => report.files.filter(file => {
    if (insightPaths && !insightPaths.has(file.path)) return false;
    if (folderScope && !inFolder(file.path, folderScope)) return false;
    if (page === 'Duplicates' && !duplicatePaths.has(file.path)) return false;
    if (reviewSelection && !reviewSelection.has(file.path)) return false;
    if (filter === 'large' && file.size < 1e9) return false;
    if (filter === 'old-installers' && !installerPaths.has(file.path)) return false;
    if (categories.includes(filter as Category) && file.category !== filter) return false;
    return `${file.name} ${file.path}`.toLowerCase().includes(query.toLowerCase());
  }).sort((a, b) => sort === 'size' ? b.size - a.size : sort === 'name' ? a.name.localeCompare(b.name) : b.modified - a.modified), [report.files, page, filter, query, sort, reviewSelection, duplicatePaths, installerPaths, insightPaths, folderScope]);
  const shown = visible.slice(0, page === 'Overview' ? 5 : limit);
  const allShownSelected = shown.length > 0 && shown.every(file => selected.has(file.path));

  function navigate(next: Page, nextFilter: Filter = 'all') { setPage(next); setFilter(nextFilter); setQuery(''); setInsightId(null); setFolderScope(null); }
  function toggle(path: string) { setSelected(current => { const next = new Set(current); next.has(path) ? next.delete(path) : next.add(path); return next; }); }
  function toggleAll() { setSelected(current => { const next = new Set(current); for (const file of shown) allShownSelected ? next.delete(file.path) : next.add(file.path); return next; }); }
  function beginCleanup(file?: FileEntry) {
    if (file) { setSelected(new Set([file.path])); setDetails(null); }
    setCleanupMode('recycle'); setCleanupConfirmation(''); setAllowAllCopies(false); setConfirm(true);
  }

  function reviewInsight(insight: Insight) { navigate('All files'); setInsightId(insight.id); }
  function selectExtras(group: FileEntry[], keeper: string) {
    setSelected(current => {
      const next = new Set(current);
      for (const file of group) file.path === keeper ? next.delete(file.path) : next.add(file.path);
      return next;
    });
    setNotice('Extra copies added to review. Your chosen copy is retained.');
  }

  async function scan() {
    if (busy) return;
    if (!native) { setHelp(true); return; }
    setBusy(true);
    try {
      const path = await invoke<string | null>('choose_folder');
      if (!path) return;
      setProgress({ files: 0, bytes: 0, phase: 'Scanning files' });
      const next = await invoke<ScanReport>('scan_directory');
      setReport(next); setPreview(false); setSelected(new Set()); setDuplicatesChecked(false); setComparisonStats(null); setCleanupResult(null); setNeedsRescan(false); navigate('Overview');
      setNotice(next.cancelled ? `Scan stopped. Showing ${next.files.length.toLocaleString()} files found so far.` : `Scan complete · ${next.files.length.toLocaleString()} files indexed in ${(next.elapsedMs / 1000).toFixed(1)}s`);
    } catch (error) { setNotice(`Could not scan: ${String(error)}`); }
    finally { setBusy(false); setProgress(null); }
  }

  async function findDuplicates() {
    if (preview) { navigate('Duplicates'); return; }
    setBusy(true); setProgress({ files: 0, bytes: 0, phase: 'Comparing file contents' });
    try {
      const result = await invoke<{ files: FileEntry[]; cancelled: boolean; skipped: number; stats: DuplicateStats }>('find_duplicates');
      setReport(current => ({ ...current, files: result.files })); setDuplicatesChecked(!result.cancelled); setComparisonStats(result.stats); navigate('Duplicates');
      setNotice(result.cancelled ? 'Comparison stopped. Partial duplicate results are shown.' : `Content comparison complete.${result.skipped ? ` ${result.skipped} changed or unreadable files skipped.` : ''}`);
    } catch (error) { setNotice(`Could not compare files: ${String(error)}`); }
    finally { setBusy(false); setProgress(null); }
  }

  async function clean() {
    if (!selectedFiles.length || cleaning) return;
    if (needsRescan) { setNotice('Rescan after the incomplete overwrite before further cleanup.'); return; }
    if (conflicts.length && !(cleanupMode !== 'recycle' && allowAllCopies)) { setNotice('Keep a copy in each duplicate group, or explicitly confirm removing all copies with a permanent action.'); return; }
    if (cleanupMode !== 'recycle' && cleanupConfirmation !== confirmationPhrase(cleanupMode, selectedFiles.length)) { setNotice('Type the exact action and file count before permanent cleanup.'); return; }
    setCleaning(true);
    setProgress({ files: 0, bytes: 0, phase: cleanupMode === 'recycle' ? 'Validating files and moving to trash' : cleanupMode === 'delete' ? 'Validating files before permanent deletion' : 'Overwriting and verifying selected files' });
    try {
      if (preview) {
        setReport(current => ({ ...current, files: removeEntries(current.files, selected) }));
        setNotice('Preview updated. No files on your computer were changed.');
        setSelected(new Set());
      } else {
        const result = await invoke<TrashResult>('cleanup_files', { paths: selectedFiles.map(file => file.path), mode: cleanupMode, confirmation: cleanupConfirmation, allowAllCopies });
        setCleanupResult(result);
        if (result.rescanRequired) setNeedsRescan(true);
        const removed = new Set(result.removed);
        setReport(current => ({ ...current, files: removeEntries(current.files, removed), volume: result.volume }));
        setSelected(current => new Set([...current].filter(path => !removed.has(path))));
        setNotice(`${result.removed.length} files ${cleanupVerbs[cleanupMode]}.${result.failed.length ? ` ${result.failed.length} not confirmed. See the file-by-file cleanup results.` : ''}`);
      }
      setConfirm(false);
    } catch (error) { setNotice(`Cleanup failed: ${String(error)}`); }
    finally { setCleaning(false); setProgress(null); }
  }

  async function exportReport() {
    if (native) {
      try {
        const destination = await invoke<string | null>('export_report');
        if (destination) setNotice('Scan report saved. It includes file names and paths.');
      } catch (error) { setNotice(String(error)); }
      return;
    }
    const blob = new Blob([JSON.stringify({ ...report, preview, exportedAt: new Date().toISOString() }, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'sift-scan-report.json'; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    setNotice('Scan report exported. It includes file names and paths.');
  }

  let cumulative = 0;
  const donut = breakdown.map(item => { const start = cumulative; cumulative += total ? item.size / total * 100 : 0; return `${colors[item.category]} ${start}% ${cumulative}%`; }).join(', ');

  return <PreviewProvider enabled={native && !preview && active} files={report.files}><div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="#" onClick={event => { event.preventDefault(); navigate('Overview'); }} aria-label="Sift home"><span className="brand-mark"><span/><span/><span/></span><span>sift<span className="brand-dot">.</span></span></a>
      <button className="nav-item mode-switch" onClick={onHome} disabled={busy || cleaning}><ArrowRight size={16}/>Switch workspace</button><div className="workspace-label">CLEAN DATA</div>
      <nav>{navigation.map(({ name, icon: Icon }) => <button key={name} aria-label={name} className={`nav-item ${page === name ? 'active' : ''}`} onClick={() => navigate(name)}><Icon size={18}/><span>{name}</span>{name === 'Cleanup review' && selected.size > 0 && <span className="nav-count">{selected.size}</span>}{name === 'Duplicates' && <span className="nav-new">{groups.length}</span>}</button>)}</nav>
      <div className="sidebar-bottom">
        <div className="local-card"><div className="local-card-icon"><ShieldCheck size={20}/></div><h3>Your files stay yours.</h3><p>Everything happens on your device. Always.</p><span><span className="status-dot"/> 100% local & private</span></div>
        <button className="nav-item muted" onClick={() => setSettings(true)}><Settings2 size={18}/>Preferences</button>
        <button className="nav-item muted" onClick={() => setHelp(true)}><CircleHelp size={18}/>Help & shortcuts<ArrowUpDown size={13} className="push-right"/></button>
        <div className="sidebar-footer"><span className="mini-brand">s.</span><div>Sift for desktop<small>v{version} · Space, thoughtfully reclaimed.</small></div></div>
      </div>
    </aside>

    <div className="main-shell">
      <header className="topbar"><div className="breadcrumb">Workspace <ChevronRight size={14}/><span>{page}</span></div><div className="topbar-right"><span className="local-status"><span className="status-dot"/>On-device processing</span><button className="icon-button" aria-label="Help" onClick={() => setHelp(true)}><CircleHelp size={18}/></button><div className="avatar">A</div></div></header>
      <main>
        <div className="page-heading"><div><div className="eyebrow">A LITTLE CLARITY. A LOT MORE ROOM.</div><h1>{page === 'Overview' ? 'Your space, simplified.' : page === 'Cleanup center' ? 'Make room, with a reason.' : page === 'Storage explorer' ? 'Follow the space.' : page === 'All files' ? 'Everything in its place.' : page === 'Duplicates' ? 'One is enough.' : 'A little room to breathe.'}</h1><p>{page === 'Overview' ? 'See what’s taking up space. Make room for what matters.' : page === 'Cleanup center' ? 'Clear explanations. Real file paths. Every decision stays yours.' : page === 'Storage explorer' ? 'Find the biggest branches, then look a little closer.' : page === 'All files' ? 'Explore, filter, and find the files that matter to you.' : page === 'Duplicates' ? 'Identical contents. A clearer picture of what you could keep.' : 'Your handpicked files. One last look before you let them go.'}</p></div><button className="button primary scan-button" onClick={scan} disabled={busy || cleaning}>{busy ? <LoaderCircle size={17} className="spin"/> : <Plus size={18}/>} {busy ? 'Working…' : 'Scan a folder'}{!busy && <span className="key-hint">↗</span>}</button></div>

        <div className={`scope-bar ${busy ? 'scanning' : ''}`}><div className="scope-icon"><HardDrive size={19}/></div><div className="scope-name"><strong>{preview ? 'Personal workspace' : report.root.split(/[\\/]/).filter(Boolean).pop() || report.root}</strong><span title={report.root}>{preview ? 'A sample of what your space could look like' : report.root}</span></div><span className={`badge ${preview ? 'preview-badge' : ''}`}><span className="status-dot"/>{preview ? 'Demo preview' : 'Local scan'}</span><div className="scope-end">{busy ? <><LoaderCircle size={14} className="spin"/><span>{progress?.phase || 'Choose a folder'}{progress && ` · ${progress.files.toLocaleString()} files`}</span><button onClick={() => { void invoke('cancel_scan').catch(error => setNotice(String(error))); }} className="text-button">Stop</button></> : <><CheckCheck size={15}/><span>{preview ? 'Explore with sample data' : `${report.files.length.toLocaleString()} files · ${(report.elapsedMs / 1000).toFixed(1)}s`}</span><button className="icon-button" aria-label="Export scan report" onClick={exportReport}><ArrowDownToLine size={16}/></button></>}</div></div>
        {report.volume && <div className="volume-strip"><HardDrive size={15}/><span>Host volume</span><strong>{bytes(report.volume.availableBytes)} available</strong><div className="volume-track"><span style={{ width: `${Math.max(0, Math.min(100, (1 - report.volume.availableBytes / Math.max(1, report.volume.totalBytes)) * 100))}%` }}/></div><span>{bytes(report.volume.totalBytes)} capacity</span><small>{preview ? 'Sample drive' : 'Updated after scan / cleanup'}</small></div>}
        {needsRescan && <div className="scan-warning" role="alert">An overwrite was not fully completed. This inventory may be stale. Rescan before comparing or cleaning up more files.</div>}
        {(report.skipped > 0 || report.truncated || report.cancelled) && <div className="scan-warning">{report.cancelled && 'Partial scan. '}{report.skipped > 0 && `${report.skipped.toLocaleString()} inaccessible or symbolic-link entries skipped. `}{report.truncated && 'The 500,000-file limit was reached. Select a smaller folder for a complete scan.'}</div>}

        {cleanupResult && <CleanupResults result={cleanupResult} onDismiss={() => setCleanupResult(null)}/>}

        {page === 'Overview' && <>
          <section className="stats-grid" aria-label="Scan summary"><div className="stat-card"><div className="stat-label">Space in view <HardDrive size={16}/></div><div className="stat-number">{bytes(total).split(' ')[0]}<span>{bytes(total).split(' ')[1]}</span></div><div className="stat-foot"><span className="tiny-dot green"/> Across your scanned files</div><div className="stat-mini-bars">{breakdown.map(item => <span key={item.category} style={{ flex: Math.max(item.size, 1), background: colors[item.category] }}/>)}</div></div><div className="stat-card"><div className="stat-label">Files, made sense of <Folder size={16}/></div><div className="stat-number">{report.files.length.toLocaleString()}<span>files</span></div><div className="stat-foot">Sorted into <strong>{breakdown.filter(item => item.count).length} categories</strong></div><div className="stat-category-dots">{categories.map(category => { const Icon = categoryIcons[category]; return <span key={category} style={{ background: `${colors[category]}22`, color: colors[category] }}><Icon size={13}/></span>; })}</div></div><div className="stat-card opportunity-card"><div className="stat-label">Duplicate copies <Sparkles size={16}/></div><div className="stat-number">{duplicatesChecked ? bytes(duplicateBytes).split(' ')[0] : '—'}<span>{duplicatesChecked ? bytes(duplicateBytes).split(' ')[1] : ''}</span></div><div className="stat-foot">{duplicatesChecked ? `${groups.length} groups worth a closer look` : 'Compare contents to find matches'}</div><button className="stat-link" disabled={busy} onClick={findDuplicates}>{duplicatesChecked ? 'Explore duplicates' : 'Find duplicates'} <ArrowRight size={15}/></button></div></section>

          <section className="storage-layout"><div className="panel category-panel"><div className="panel-heading"><h2>A bird’s-eye view</h2><span className="small-muted">By file type</span></div><div className="donut-area"><div className="donut" style={{ background: total ? `conic-gradient(${donut})` : '#e9eeeb' }}><div className="donut-hole"><span>SCANNED SPACE</span><strong>{bytes(total)}</strong><small>{report.files.length.toLocaleString()} files, one clear view</small></div></div></div><div className="category-list">{breakdown.slice(0, 6).map(({ category, size, count }) => <button key={category} onClick={() => navigate('All files', category)}><span className="category-color" style={{ background: colors[category] }}/><span>{category}<small>{count} files</small></span><strong>{bytes(size)}</strong><span className="category-percentage">{total ? Math.round(size / total * 100) : 0}%</span><ChevronRight size={13}/></button>)}</div><button className="panel-footer-button" onClick={() => navigate('All files')}>Explore all categories <ArrowRight size={14}/></button></div>
          <div className="panel map-panel"><div className="panel-heading"><div><h2>The bigger picture</h2><p>The bigger the block, the more space it takes.</p></div><span className="map-label"><span className="tiny-dot green"/> File categories</span></div><TreeMap items={breakdown} onSelect={category => navigate('All files', category)}/><div className="map-footer"><span><SlidersHorizontal size={14}/> Click a category to look closer</span><span>File sizes · not disk capacity</span></div></div></section>

          <section className="quick-section"><div className="section-heading"><h2>A good place to start<span className="subtle-tag">Picked for you</span></h2><span className="small-muted">A little less clutter, on your terms.</span></div><div className="quick-grid"><button className="quick-card" disabled={busy} onClick={findDuplicates}><span className="quick-icon lavender"><Copy size={20}/></span><div><h3>Seeing double?</h3><p>{duplicatesChecked ? `${groups.length} groups of identical files` : 'Find files with matching contents'}</p><strong>{duplicatesChecked ? bytes(duplicateBytes) : 'Compare files'} <span>to review</span></strong></div><ChevronRight size={18}/></button><button className="quick-card" onClick={() => navigate('All files', 'large')}><span className="quick-icon peach"><Film size={20}/></span><div><h3>The space takers</h3><p>{large.length} files larger than 1 GB</p><strong>{bytes(large.reduce((sum, file) => sum + file.size, 0))} <span>in large files</span></strong></div><ChevronRight size={18}/></button><button className="quick-card" onClick={() => navigate('All files', 'old-installers')}><span className="quick-icon sage"><ArrowDownToLine size={20}/></span><div><h3>Installed. And forgotten?</h3><p>Installers unchanged for 6+ months</p><strong>{bytes(installers.reduce((sum, file) => sum + file.size, 0))} <span>to review</span></strong></div><ChevronRight size={18}/></button></div></section>
        </>}

        {page === 'Overview' && <div className="overview-upgrade"><span className="quick-icon sage"><Sparkles size={20}/></span><div><strong>A smarter starting point.</strong><p>See why files are suggested, understand the impact, and follow space into folders.</p></div><button className="button secondary" onClick={() => navigate('Cleanup center')}>Open Cleanup Center <ArrowRight size={14}/></button><button className="text-button" onClick={() => navigate('Storage explorer')}>Explore folders</button></div>}
        {page === 'Cleanup center' && <CleanupCenter insights={insights} duplicateBytes={duplicateBytes} groups={groups.length} onReview={reviewInsight} onDuplicates={() => navigate('Duplicates')} partial={report.cancelled || report.truncated || report.skipped > 0}/>}
        {page === 'Storage explorer' && <StorageExplorer key={report.root} report={report} layout={layout} onLayout={setLayout} onInspect={setDetails} onFiles={folder => { navigate('All files'); setFolderScope(folder); }}/>}
        {page === 'All files' && activeInsight && <div className="insight-detail-banner"><span className="risk-badge">{activeInsight.risk}</span><h2>{activeInsight.title}</h2><p>{activeInsight.reason}</p><strong>What removal changes</strong><p>{activeInsight.consequence}</p><button className="text-button" onClick={() => setInsightId(null)}>Clear suggestion filter <X size={12}/></button></div>}
        {folderScope && <div className="folder-scope"><Folder size={15}/><span>{folderScope}</span><button className="icon-button" aria-label="Clear folder scope" onClick={() => setFolderScope(null)}><X size={14}/></button></div>}
        {page === 'Duplicates' && <div className="info-banner"><Copy size={20}/><div><strong>{duplicatesChecked ? `${groups.length} duplicate groups · ${bytes(duplicateBytes)} in extra copies` : 'Ready to compare your files?'}</strong><p>Choose a copy to retain, then add extra copies to review. Native matches pass full hashing and a separate byte comparison.</p></div><button className="button secondary" onClick={findDuplicates} disabled={busy || needsRescan}>{busy ? 'Comparing…' : duplicatesChecked ? 'Compare again' : 'Find duplicates'}</button></div>}
        {page === 'Duplicates' && <DuplicateReview layout={layout} onLayout={setLayout} groups={groups} selected={selected} onSelectExtras={selectExtras} busy={busy || cleaning} stats={comparisonStats}/>}
        {page === 'Cleanup review' && <div className="info-banner"><ShieldCheck size={22}/><div><strong>You’re in control.</strong><p>{preview ? 'This is a demo. Reviewing and removing sample files won’t change your computer.' : 'Choose Recycle Bin / trash, permanent deletion, or best-effort overwrite during review. File changes are checked again before cleanup.'}</p></div></div>}

        {page !== 'Cleanup center' && page !== 'Storage explorer' && <section className="panel files-panel"><div className="files-heading"><div><h2>{page === 'Overview' ? 'A closer look at your files' : page === 'Duplicates' ? 'Matching files' : page === 'Cleanup review' ? 'Your selected files' : 'Your files'} <span className="count-badge">{visible.length.toLocaleString()}</span></h2>{page === 'Overview' && <p>A few of the biggest things in your space.</p>}</div><div className="file-tools"><ViewOptions layout={layout} onChange={setLayout}/><div className="search-input"><Search size={15}/><input ref={search} placeholder="Find a file…" value={query} onChange={event => setQuery(event.target.value)} aria-label="Search files"/><kbd>⌘ K</kbd></div><label className="sort-select"><SlidersHorizontal size={15}/><select aria-label="Sort files" value={sort} onChange={event => setSort(event.target.value as typeof sort)}><option value="size">Largest first</option><option value="name">Name A–Z</option><option value="modified">Last modified</option></select><ChevronDown size={12}/></label></div></div>
          {page !== 'Overview' && page !== 'Cleanup review' && <div className="filter-row"><button className={filter === 'all' ? 'selected' : ''} onClick={() => setFilter('all')}>All types</button>{categories.map(category => <button key={category} className={filter === category ? 'selected' : ''} onClick={() => setFilter(category)}><span style={{ background: colors[category] }}/>{category}</button>)}{(filter === 'large' || filter === 'old-installers') && <button className="selected" onClick={() => setFilter('all')}>{filter === 'large' ? 'Over 1 GB' : 'Older installers'} <X size={12}/></button>}</div>}
          {page !== 'Overview' && page !== 'Cleanup review' && visible.length > 0 && <div className="bulk-selection"><span>{visible.length.toLocaleString()} files match this view</span><button className="text-button" disabled={busy || cleaning || needsRescan} onClick={() => setSelected(current => new Set([...current, ...visible.map(file => file.path)]))}>Add all {visible.length.toLocaleString()} matches to selection</button></div>}
          {layout === 'grid' ? <><div className="grid-selection"><label><input type="checkbox" aria-label="Select visible files" checked={allShownSelected} onChange={toggleAll} disabled={busy || cleaning}/> Select visible files</label><span>Previews load locally as you browse. Unsupported files show their type.</span></div><div className="file-card-grid">{shown.map(file => <article key={file.path} className={`file-card ${selected.has(file.path) ? 'row-selected' : ''}`}><label className="card-checkbox"><input type="checkbox" aria-label={`Select ${file.name}`} checked={selected.has(file.path)} onChange={() => toggle(file.path)} disabled={busy || cleaning}/></label><button className="card-inspect" aria-label={`Details for ${file.name}`} onClick={() => setDetails(file)}><FileThumbnail file={file} large/><strong>{file.name}</strong><small title={file.path}>{file.path}</small></button><div className="card-meta"><span>{file.category}</span><strong>{bytes(file.size)}</strong></div></article>)}</div></> : <><div className="table-scroll"><table><thead><tr><th className="checkbox-cell"><input type="checkbox" aria-label="Select visible files" checked={allShownSelected} onChange={toggleAll} disabled={busy || cleaning}/></th><th className="name-column">File name</th><th>Category</th><th><button className="table-sort" onClick={() => setSort('size')}>Size <ArrowDown size={12}/></button></th><th>Last modified</th><th/></tr></thead><tbody>{shown.slice(0, page === 'Overview' ? 5 : shown.length).map(file => { return <tr key={file.path} className={selected.has(file.path) ? 'row-selected' : ''}><td className="checkbox-cell"><input type="checkbox" aria-label={`Select ${file.name}`} checked={selected.has(file.path)} onChange={() => toggle(file.path)} disabled={busy || cleaning}/></td><td><button className="file-name" onClick={() => setDetails(file)}><FileThumbnail file={file}/><span><strong>{file.name}</strong><small title={file.path}>{file.path.substring(0, file.path.length - file.name.length).replace(/\/$/, '')}</small></span></button></td><td><span className="file-category"><span style={{ background: colors[file.category] }}/>{file.category}</span></td><td className="file-size">{bytes(file.size)}</td><td className="file-date">{relativeDate(file.modified)}</td><td><button className="icon-button" aria-label={`Details for ${file.name}`} onClick={() => setDetails(file)}><MoreHorizontal size={18}/></button></td></tr>; })}</tbody></table></div></>}
          {visible.length === 0 && <div className="empty-state"><FolderOpen size={35}/><h3>{page === 'Cleanup review' ? 'A clean slate.' : page === 'Duplicates' ? 'No matching copies here.' : 'No files found.'}</h3><p>{page === 'Cleanup review' ? 'Select files from your overview or file explorer to review them here.' : query ? 'Try another name or change your filters.' : page === 'Duplicates' ? duplicatesChecked ? 'Try scanning another folder, or clear the filters.' : 'Compare file contents to discover duplicate copies.' : 'Scan a folder or try a different category.'}</p><button className="button secondary" onClick={() => page === 'Duplicates' && !duplicatesChecked ? void findDuplicates() : navigate('All files')}>{page === 'Duplicates' && !duplicatesChecked ? 'Find duplicates' : 'Explore files'}<ArrowRight size={14}/></button></div>}
          <div className="table-footer"><span>{page === 'Overview' ? `Showing ${Math.min(5, visible.length)} of ${visible.length.toLocaleString()} files` : `${Math.min(limit, visible.length)} of ${visible.length.toLocaleString()} files`}</span>{page === 'Overview' ? <button onClick={() => navigate('All files')}>View all files <ArrowRight size={14}/></button> : visible.length > limit ? <button onClick={() => setLimit(value => value + 100)}>Load more files <Plus size={14}/></button> : <span className="footer-privacy"><ShieldCheck size={13}/> Only you can see these files</span>}</div>
        </section>}
        <footer className="main-footer"><span><ShieldCheck size={13}/> Thoughtfully organized. Never automatically deleted.</span><span>Less clutter. More possibility.<span className="footer-spark">✳</span></span></footer>
      </main>
    </div>

    {selected.size > 0 && <div className="selection-bar"><div className="selection-check"><Check size={18}/></div><span><strong>{selected.size} files selected</strong><small>{bytes(selectedBytes)} in your selection</small></span><button className="text-button" onClick={() => setSelected(new Set())} disabled={cleaning}>Clear</button><button className="button primary" onClick={() => beginCleanup()} disabled={busy || cleaning || needsRescan}><Trash2 size={15}/>Review cleanup<ArrowRight size={15}/></button></div>}
    {notice && <div className="toast" role="status"><span>{notice}</span><button className="icon-button" aria-label="Dismiss notification" onClick={() => setNotice('')}><X size={15}/></button></div>}

    {confirm && <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !cleaning) setConfirm(false); }}><div className="modal" role="dialog" aria-modal="true" aria-labelledby="cleanup-title"><button className="modal-close icon-button" aria-label="Close review" disabled={cleaning} onClick={() => setConfirm(false)}><X size={19}/></button><span className="modal-illustration"><Trash2 size={27}/></span><div className="eyebrow">A LITTLE MORE BREATHING ROOM</div><h2 id="cleanup-title">Ready to let these go?</h2><p>{preview ? 'Try the cleanup flow with sample data. Your real files are untouched.' : 'Review the full paths and choose how these files should be handled.'}</p><CleanupOptions mode={cleanupMode} onMode={setCleanupMode} count={selectedFiles.length} confirmation={cleanupConfirmation} onConfirmation={setCleanupConfirmation} conflicts={conflicts.length} allowAllCopies={allowAllCopies} onAllowAllCopies={setAllowAllCopies} disabled={cleaning}/><div className="review-total"><span>{selectedFiles.length} selected files</span><strong>{bytes(selectedBytes)}</strong></div><div className="review-layout-tools"><span>Review files visually</span><ViewOptions layout={layout} onChange={setLayout}/></div><div className={`review-files ${layout === 'grid' ? 'review-grid' : ''}`}>{selectedFiles.slice(0, 100).map(file => <div key={file.path}><FileThumbnail file={file} large={layout === 'grid'}/><span><strong>{file.name}</strong><small>{file.path}</small></span><button className="icon-button" aria-label={`Keep ${file.name}`} disabled={cleaning} onClick={() => toggle(file.path)}><X size={14}/></button></div>)}</div>{selectedFiles.length > 100 && <p className="group-limit">Showing 100 of {selectedFiles.length.toLocaleString()} selected paths. Inspect the full selection in Cleanup review before continuing.</p>}{conflicts.length > 0 && !(cleanupMode !== 'recycle' && allowAllCopies) && <div className="copy-conflict" role="alert"><ShieldCheck size={18}/><span>Every copy is selected in {conflicts.length} duplicate group(s). Keep at least one copy before cleanup.</span><button onClick={() => { setConfirm(false); navigate('Duplicates'); }} className="text-button">Choose a copy to keep</button></div>}<div className="review-note"><ShieldCheck size={16}/>{preview ? 'Demo mode · no actual disk operations' : cleaning && cleanupMode === 'wipe' ? `${progress?.phase || 'Overwriting and verifying'} · ${bytes(progress?.bytes || 0)} in current file. Stopping may leave altered data.` : 'Changed or inaccessible files will be skipped.'}</div><div className="modal-actions">{cleaning && cleanupMode !== 'recycle' && <button className="button secondary" onClick={() => { void invoke('cancel_scan').catch(error => setNotice(String(error))); }}>Stop cleanup</button>}<button className="button secondary" disabled={cleaning} onClick={() => setConfirm(false)}>Keep exploring</button><button className="button primary" disabled={!selectedFiles.length || cleaning || needsRescan || (conflicts.length > 0 && !(cleanupMode !== 'recycle' && allowAllCopies)) || (cleanupMode !== 'recycle' && cleanupConfirmation !== confirmationPhrase(cleanupMode, selectedFiles.length))} onClick={() => void clean()}>{cleaning ? <LoaderCircle size={16} className="spin"/> : <Trash2 size={16}/>} {cleaning ? `Processing ${progress?.files || 0} / ${selectedFiles.length}…` : preview ? 'Try demo cleanup' : cleanupLabels[cleanupMode]}</button></div></div></div>}
    {details && <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) setDetails(null); }}><div className="modal detail-modal" role="dialog" aria-modal="true" aria-labelledby="detail-title"><button className="modal-close icon-button" aria-label="Close file details" onClick={() => setDetails(null)}><X size={19}/></button><FileThumbnail file={details} large/><h2 id="detail-title">{details.name}</h2><div className="detail-list"><div><span>Category</span><strong>{details.category}</strong></div><div><span>File size</span><strong>{bytes(details.size, 2)}</strong></div><div><span>Last modified</span><strong>{details.modified ? new Date(details.modified).toLocaleString() : 'Unknown'}</strong></div><div><span>Full path</span><code>{details.path}</code></div>{duplicatePaths.has(details.path) && <div><span>Content match</span><strong>Exact duplicate group</strong></div>}</div><button className="button primary full-width" disabled={busy || cleaning} onClick={() => { toggle(details.path); setDetails(null); }}>{selected.has(details.path) ? <Check size={16}/> : <Plus size={16}/>} {selected.has(details.path) ? 'Remove from selection' : 'Add to cleanup review'}</button><button className="button secondary full-width" disabled={busy || cleaning || needsRescan} onClick={() => beginCleanup(details)}>Clean up this file only</button></div></div>}
    {settings && <div className="modal-backdrop"><div className="modal" role="dialog" aria-modal="true" aria-labelledby="preferences-title"><button className="modal-close icon-button" aria-label="Close preferences" onClick={() => setSettings(false)}><X size={19}/></button><span className="modal-illustration"><Settings2 size={27}/></span><h2 id="preferences-title">Make yourself at home.</h2><p>A few thoughtful defaults for your workspace.</p><label className="preference"><span><strong>Reduce motion</strong><small>Keep transitions quiet and simple.</small></span><input type="checkbox" checked={reducedMotion} onChange={event => setReducedMotion(event.target.checked)}/></label><div className="preference"><span><strong>Local processing</strong><small>File data stays on your device. No account needed.</small></span><span className="badge">Always on</span></div><div className="preference"><span><strong>Cleanup destination</strong><small>Recycle Bin / trash is the default. Permanent actions require typed confirmation during review.</small></span><ShieldCheck size={20}/></div><button className="button secondary full-width" onClick={exportReport}><ArrowDownToLine size={16}/>Export current scan report</button></div></div>}
    {help && <div className="modal-backdrop"><div className="modal" role="dialog" aria-modal="true" aria-labelledby="help-title"><button className="modal-close icon-button" aria-label="Close help" onClick={() => setHelp(false)}><X size={19}/></button><span className="modal-illustration"><Sparkles size={27}/></span><div className="eyebrow">WELCOME TO A CLEARER SPACE</div><h2 id="help-title">Small steps. More space.</h2><p>{native ? 'Choose a folder or drive to start a local scan.' : 'You’re exploring the browser preview. Real folder scanning and system trash are available in the desktop app.'}</p><ol className="help-steps"><li><span>1</span><div><strong>See the whole picture</strong><p>Scan an accessible folder or drive. The scan reads metadata. Visible thumbnails and optional duplicate comparison read contents locally.</p></div></li><li><span>2</span><div><strong>Look a little closer</strong><p>Filter by type, inspect large files, or compare full contents to find duplicates.</p></div></li><li><span>3</span><div><strong>Choose what goes</strong><p>Select one file or many, review their paths, and choose recycling, permanent deletion, or best-effort overwrite.</p></div></li></ol><div className="help-shortcuts"><span>Find a file <kbd>Ctrl / ⌘ K</kbd></span><span>Close a dialog <kbd>Esc</kbd></span></div>{!native && <button className="button primary full-width" onClick={() => { setHelp(false); setReport(demoReport()); setPreview(true); setSelected(new Set()); setDuplicatesChecked(true); navigate('Overview'); setNotice('Sample workspace restored. Explore the categories and cleanup flow.'); }}>Explore the demo <ArrowRight size={16}/></button>}</div></div>}
  </div></PreviewProvider>;
}
