import type { CleanupMode } from './model';

export const cleanupLabels: Record<CleanupMode, string> = { recycle: 'Move to trash', delete: 'Delete permanently', wipe: 'Overwrite & delete' };
export const cleanupVerbs: Record<CleanupMode, string> = { recycle: 'moved to trash', delete: 'permanently deleted', wipe: 'overwritten and deleted' };
export function confirmationPhrase(mode: CleanupMode, count: number): string {
  return mode === 'recycle' ? '' : `${mode === 'delete' ? 'DELETE' : 'WIPE'} ${count} ${count === 1 ? 'FILE' : 'FILES'}`;
}

export default function CleanupOptions({ mode, onMode, count, confirmation, onConfirmation, conflicts, allowAllCopies, onAllowAllCopies, disabled }: {
  mode: CleanupMode; onMode: (mode: CleanupMode) => void; count: number;
  confirmation: string; onConfirmation: (value: string) => void;
  conflicts: number; allowAllCopies: boolean; onAllowAllCopies: (value: boolean) => void; disabled: boolean;
}) {
  const phrase = confirmationPhrase(mode, count);
  return <div className="cleanup-options">
    <fieldset disabled={disabled}><legend>Cleanup action</legend>
      <label className={mode === 'recycle' ? 'is-active' : ''}><input type="radio" name="cleanup-action" checked={mode === 'recycle'} onChange={() => onMode('recycle')}/><span><strong>Recycle Bin / trash</strong><small>Default. Restore through your operating system.</small></span></label>
      <label className={mode === 'delete' ? 'is-active' : ''}><input type="radio" name="cleanup-action" checked={mode === 'delete'} onChange={() => onMode('delete')}/><span><strong>Delete permanently</strong><small>Bypass trash. Sift cannot undo this; contents may remain recoverable.</small></span></label>
      <label className={mode === 'wipe' ? 'is-active' : ''}><input type="radio" name="cleanup-action" checked={mode === 'wipe'} onChange={() => onMode('wipe')}/><span><strong>Overwrite & delete</strong><small>One zero pass, read-back verification, then permanent deletion.</small></span></label>
    </fieldset>
    {mode !== 'recycle' && <div className="irreversible-warning" role="note"><strong>This action cannot be undone in Sift.</strong><p>{mode === 'wipe' ? 'Best-effort file overwrite, not certified secure erasure. SSD/flash wear leveling, copy-on-write, snapshots, backups, and synced copies can retain data. Hard-linked and Windows cloud/reparse, compressed, sparse, or encrypted files are refused.' : 'These selected files will bypass Recycle Bin / trash. Permanent deletion removes their names; it does not overwrite their contents.'}</p></div>}
    {mode !== 'recycle' && conflicts > 0 && <label className="all-copies-confirm"><input type="checkbox" checked={allowAllCopies} disabled={disabled} onChange={event => onAllowAllCopies(event.target.checked)}/><span>Allow removal of every selected copy in {conflicts} duplicate {conflicts === 1 ? 'group' : 'groups'}. No retained copy is required for this explicitly confirmed action.</span></label>}
    {mode !== 'recycle' && <label className="typed-confirmation"><span>Type <strong>{phrase}</strong> to confirm</span><input aria-label="Typed cleanup confirmation" disabled={disabled} value={confirmation} onChange={event => onConfirmation(event.target.value)} autoComplete="off" spellCheck={false} placeholder={phrase}/></label>}
  </div>;
}
