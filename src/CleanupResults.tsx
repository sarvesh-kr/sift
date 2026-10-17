import { useEffect, useState } from 'react';
import { CheckCheck, ChevronDown, X } from 'lucide-react';
import type { TrashResult } from './model';
import { cleanupVerbs } from './CleanupOptions';

export default function CleanupResults({ result, onDismiss }: { result: TrashResult; onDismiss: () => void }) {
  const [limit, setLimit] = useState(25);
  useEffect(() => setLimit(25), [result]);
  return <section className="cleanup-results panel" aria-label="Cleanup results">
    <div className="cleanup-results-heading"><CheckCheck size={19}/><div><h2>{result.removed.length.toLocaleString()} {result.removed.length === 1 ? 'file' : 'files'} {cleanupVerbs[result.mode || 'recycle']}</h2><p>{result.failed.length ? `${result.failed.length.toLocaleString()} not confirmed. They remain selected for review; rescan if a file has moved.` : result.mode && result.mode !== 'recycle' ? 'Cleanup completed. These files bypassed trash; Sift cannot restore them.' : 'Cleanup completed. Restore files through your operating system’s trash if needed.'}</p>{result.rescanRequired && <p className="overwrite-rescan" role="alert">An overwrite was not fully completed. Files may already be partially or fully overwritten. Rescan before any further cleanup.</p>}{result.mode === 'wipe' && <p>Logical overwrite verified for successful files. Erasure from SSD/flash, snapshots, or backups is not guaranteed.</p>}</div><button className="icon-button" aria-label="Dismiss cleanup results" onClick={onDismiss}><X size={17}/></button></div>
    {result.failed.length > 0 && <details open><summary>File-by-file results <ChevronDown size={15}/></summary><ul className="cleanup-failures">{result.failed.slice(0, limit).map(file => <li key={file.path}><code>{file.path}</code><p>{file.error}</p></li>)}</ul>{limit < result.failed.length && <button className="text-button" onClick={() => setLimit(current => current + 25)}>Show more ({result.failed.length - limit} remaining)</button>}<p className="cleanup-results-note">Successful files leave your selection. Sift never changes the selected cleanup mode after a failure.</p></details>}
  </section>;
}
