import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { AudioLines, Code2, File, FileArchive, FileImage, FileText, Film, Grid2X2, List, Monitor } from 'lucide-react';
import { colors } from './model';
import type { FileEntry } from './model';

export type FileLayout = 'list' | 'grid';
type Preview = { image: string | null; text: string | null };
const icons = { Videos: Film, Images: FileImage, Documents: FileText, Audio: AudioLines, Archives: FileArchive, Applications: Monitor, Code: Code2, Other: File };
class PreviewStore {
  cache = new Map<string, Preview | null>();
  pending = new Map<string, Promise<Preview | null>>();
  queue: (() => void)[] = [];
  interested = new Map<string, (() => boolean)[]>();
  active = 0;
  bytes = 0;
  constructor(public enabled: boolean) {}
  load(file: FileEntry, alive: () => boolean): Promise<Preview | null> {
    const key = JSON.stringify([file.path, file.size, file.modified]);
    if (this.cache.has(key)) { const value = this.cache.get(key)!; this.cache.delete(key); this.cache.set(key, value); return Promise.resolve(value); }
    if (this.pending.has(key)) { this.interested.get(key)!.push(alive); return this.pending.get(key)!; }
    this.interested.set(key, [alive]);
    const promise = new Promise<Preview | null>(resolve => {
      this.queue.push(() => {
        if (!this.interested.get(key)?.some(check => check())) { this.pending.delete(key); this.interested.delete(key); resolve(null); this.pump(); return; }
        this.active++;
        invoke<Preview | null>('file_preview', { path: file.path }).catch(() => null).then(value => {
          this.cache.set(key, value); this.bytes += JSON.stringify(value).length * 2;
          while (this.cache.size > 96 || this.bytes > 12 * 1024 * 1024) {
            const oldest = this.cache.keys().next().value!;
            this.bytes -= JSON.stringify(this.cache.get(oldest)).length * 2; this.cache.delete(oldest);
          }
          this.pending.delete(key); this.interested.delete(key); resolve(value);
        }).finally(() => { this.active--; this.pump(); });
      });
    });
    this.pending.set(key, promise); this.pump(); return promise;
  }
  pump() { while (this.active < 3 && this.queue.length) this.queue.shift()!(); }
}
const Context = createContext(new PreviewStore(false));
export function PreviewProvider({ enabled, files, children }: { enabled: boolean; files: FileEntry[]; children: ReactNode }) {
  const store = useMemo(() => new PreviewStore(enabled), [enabled, files]);
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useFileLayout() {
  const [layout, setLayout] = useState<FileLayout>(() => localStorage.getItem('sift-file-layout') === 'grid' ? 'grid' : 'list');
  useEffect(() => localStorage.setItem('sift-file-layout', layout), [layout]);
  return [layout, setLayout] as const;
}
export function ViewOptions({ layout, onChange }: { layout: FileLayout; onChange: (layout: FileLayout) => void }) {
  return <div className="view-options" role="group" aria-label="File layout"><button title="List view" aria-label="List view" aria-pressed={layout === 'list'} onClick={() => onChange('list')}><List size={16}/></button><button title="Grid view" aria-label="Grid view" aria-pressed={layout === 'grid'} onClick={() => onChange('grid')}><Grid2X2 size={16}/></button></div>;
}
export function FileThumbnail({ file, large = false }: { file: FileEntry; large?: boolean }) {
  const store = useContext(Context);
  const element = useRef<HTMLSpanElement>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const Icon = icons[file.category];
  const extension = file.name.includes('.') ? file.name.split('.').at(-1)!.slice(0, 7).toUpperCase() : 'FILE';
  useEffect(() => {
    let alive = true; let visible = false; setPreview(null);
    if (!store.enabled || !element.current) return;
    const observer = new IntersectionObserver(entries => {
      visible = entries.some(entry => entry.isIntersecting);
      if (!visible) { setPreview(null); return; }
      void store.load(file, () => alive && visible).then(value => { if (alive && visible) setPreview(value); });
    }, { rootMargin: '120px' });
    observer.observe(element.current);
    return () => { alive = false; observer.disconnect(); };
  }, [store, file.path, file.size, file.modified]);
  return <span ref={element} className={`file-thumbnail ${large ? 'large-thumbnail' : ''} ${preview?.text ? 'text-thumbnail' : ''}`} style={{ '--file-color': colors[file.category] } as React.CSSProperties} title={preview ? 'Local content preview' : store.enabled ? 'Content preview unavailable for this file' : 'Content previews appear after a local scan'}>
    {preview?.image ? <img src={preview.image} alt={`Preview of ${file.name}`} draggable={false}/> : preview?.text ? <span className="thumbnail-text" aria-label={`Text preview of ${file.name}`}>{preview.text}</span> : <span className="thumbnail-fallback" aria-label={`${extension} file`}><Icon size={large ? 36 : 21}/><small>{extension}</small></span>}
  </span>;
}
