import { useState } from 'react';
import { Check, Copy, ShieldCheck } from 'lucide-react';
import { bytes, relativeDate } from './model';
import type { FileEntry } from './model';
import { FileThumbnail, ViewOptions } from './FilePresentation';
import type { FileLayout } from './FilePresentation';
import { suggestedKeeper } from './analysis';
import type { DuplicateStats } from './analysis';

export default function DuplicateReview({ layout, onLayout, groups, selected, onSelectExtras, busy, stats }: { layout: FileLayout; onLayout: (layout: FileLayout) => void; groups: FileEntry[][]; selected: Set<string>; onSelectExtras: (group: FileEntry[], keeper: string) => void; busy: boolean; stats: DuplicateStats | null }) {
  const [keepers, setKeepers] = useState<Record<string, string>>({});
  const [limit, setLimit] = useState(20);
  return <section className={`duplicate-review duplicate-layout-${layout}`}><div className="duplicate-view-tools"><span>Compare copies visually</span><ViewOptions layout={layout} onChange={onLayout}/></div>{stats && <div className="comparison-stats"><span><strong>{stats.sampledFiles}</strong> sampled</span><span><strong>{stats.hashedFiles}</strong> fully hashed</span><span><strong>{stats.verifiedPairs}</strong> byte-verified pairs</span><span><strong>{bytes(stats.bytesRead)}</strong> read · {(stats.elapsedMs / 1000).toFixed(1)}s</span></div>}{groups.slice(0, limit).map((group, index) => {
    const key = group[0].duplicateGroup!;
    const keeper = group.some(file => file.path === keepers[key]) ? keepers[key] : suggestedKeeper(group).path;
    return <article className="panel duplicate-group" key={key}><div className="duplicate-group-heading"><span className="quick-icon lavender"><Copy size={19}/></span><div><h2>Match {index + 1} <span>{group.length} identical files</span></h2><p>{bytes(group[0].size * (group.length - 1))} in extra copies · {bytes(group[0].size)} each</p></div><button className="button secondary" disabled={busy} onClick={() => onSelectExtras(group, keeper)}>Select extra copies</button></div><div className="keeper-files">{[...group.filter(file => file.path === keeper), ...group.filter(file => file.path !== keeper)].slice(0, 100).map(file => <label className={`keeper-row ${file.path === keeper ? 'is-keeper' : ''}`} key={file.path}><input type="radio" name={`keeper-${key}`} checked={keeper === file.path} disabled={busy} onChange={() => { setKeepers(current => ({ ...current, [key]: file.path })); if (group.some(copy => selected.has(copy.path))) onSelectExtras(group, file.path); }}/><FileThumbnail file={file} large={layout === 'grid'}/><span className="keeper-file"><strong>{file.name}</strong><small>{file.path}</small></span><span className="keeper-modified">{relativeDate(file.modified)}</span>{file.path === keeper ? <span className="keeper-label"><ShieldCheck size={13}/>Keep this copy</span> : selected.has(file.path) ? <span className="selected-copy"><Check size={13}/>In review</span> : <span className="keeper-label muted-label">Extra copy</span>}</label>)}</div>{group.length > 100 && <p className="group-limit">Showing 100 of {group.length} copies. Choose a keeper here; selection includes the entire group. Use the file list to inspect further paths.</p>}<div className="duplicate-group-foot"><ShieldCheck size={13}/> Native cleanup rechecks the retained copy before moving a duplicate to trash.</div></article>;
  })}{groups.length > limit && <button className="button secondary full-width" onClick={() => setLimit(value => value + 20)}>Show more matching groups</button>}</section>;
}
