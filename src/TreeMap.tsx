import { useEffect, useRef, useState } from 'react';
import { ArrowRight, AudioLines, Code2, File, FileArchive, FileImage, FileText, Film, Monitor } from 'lucide-react';
import { bytes, colors } from './model';
import type { Category } from './model';

type Item = { category: Category; size: number };
type Rect = Item & { x: number; y: number; w: number; h: number };
const icons = { Videos: Film, Images: FileImage, Documents: FileText, Audio: AudioLines, Archives: FileArchive, Applications: Monitor, Code: Code2, Other: File };

// Recursive balanced bisection preserves every category's exact area proportion.
function layout(items: Item[], x: number, y: number, w: number, h: number): Rect[] {
  if (!items.length) return [];
  if (items.length === 1) return [{ ...items[0], x, y, w, h }];
  const total = items.reduce((sum, item) => sum + item.size, 0);
  let best = 1, part = items[0].size, bestPart = part;
  for (let index = 2; index < items.length; index++) {
    part += items[index - 1].size;
    if (Math.abs(part - total / 2) < Math.abs(bestPart - total / 2)) { best = index; bestPart = part; }
  }
  const ratio = bestPart / total;
  return w >= h
    ? [...layout(items.slice(0, best), x, y, w * ratio, h), ...layout(items.slice(best), x + w * ratio, y, w * (1 - ratio), h)]
    : [...layout(items.slice(0, best), x, y, w, h * ratio), ...layout(items.slice(best), x, y + h * ratio, w, h * (1 - ratio))];
}

export default function TreeMap({ items, onSelect }: { items: Item[]; onSelect: (category: Category) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 600, height: 325 });
  useEffect(() => {
    if (!ref.current) return;
    const observer = new ResizeObserver(([entry]) => setSize({ width: entry.contentRect.width, height: entry.contentRect.height }));
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);
  const cells = layout(items.filter(item => item.size > 0), 0, 0, size.width, size.height);
  return <div className="treemap proportional-map" ref={ref}>{cells.map(cell => {
    const Icon = icons[cell.category];
    const small = cell.w < 110 || cell.h < 90;
    const tiny = cell.w < 70 || cell.h < 38;
    return <button key={cell.category} className={`tree-cell proportional-cell ${small ? 'small-cell' : ''} ${tiny ? 'tiny-cell' : ''}`} style={{ left: `${cell.x / size.width * 100}%`, top: `${cell.y / size.height * 100}%`, width: `${cell.w / size.width * 100}%`, height: `${cell.h / size.height * 100}%`, background: colors[cell.category], color: cell.category === 'Videos' ? '#fff' : '#3e5544' }} onClick={() => onSelect(cell.category)} title={`${cell.category} · ${bytes(cell.size)}`} aria-label={`Explore ${cell.category}, ${bytes(cell.size)}`}><Icon size={small ? 16 : 24}/><div><strong>{cell.category}</strong><span>{bytes(cell.size)}</span></div><ArrowRight size={15} className="tree-arrow"/></button>;
  })}{!cells.length && <div className="empty-map">No file sizes to visualize yet.</div>}</div>;
}
