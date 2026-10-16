import { ArrowRight, Copy, FileCheck2, ShieldCheck, Sparkles } from 'lucide-react';
import { bytes } from './model';
import type { Insight } from './analysis';

export default function CleanupCenter({ insights, duplicateBytes, groups, onReview, onDuplicates, partial }: { insights: Insight[]; duplicateBytes: number; groups: number; onReview: (insight: Insight) => void; onDuplicates: () => void; partial: boolean }) {
  const unique = new Map(insights.flatMap(insight => insight.files).map(file => [file.path, file.size]));
  const potential = [...unique.values()].reduce((sum, size) => sum + size, 0);
  return <section className="cleanup-center">
    <div className="cleanup-hero"><div className="cleanup-hero-icon"><Sparkles size={28}/></div><div><span className="eyebrow">SPACE WITH A SECOND CHANCE</span><h2>{bytes(potential)} worth a closer look.</h2><p>{unique.size.toLocaleString()} unique files across {insights.length} explanations. Overlapping suggestions are counted once.</p></div><div className="hero-note"><ShieldCheck size={18}/><span>Your choice, every time.<small>No files are selected automatically.</small></span></div></div>
    <div className="center-disclaimer"><FileCheck2 size={16}/><span>{partial ? 'This inventory is partial. ' : ''}Figures describe logical file sizes, not guaranteed free space. Moving files to trash usually does not free space until it is emptied.</span></div>
    <button className="duplicate-promo" onClick={onDuplicates}><span className="quick-icon lavender"><Copy size={23}/></span><div><h3>Make room without losing a copy.</h3><p>{groups ? `${groups} matching groups · ${bytes(duplicateBytes)} in extra copies.` : 'Compare full contents to discover duplicate files.'} Retain at least one verified original.</p></div><span className="button secondary">Review duplicates <ArrowRight size={14}/></span></button>
    <div className="insight-grid">{insights.map(insight => <article className="panel insight-card" key={insight.id}><div className="insight-top"><span className={`risk-badge ${insight.risk === 'App impact' ? 'risk-app' : ''}`}>{insight.risk}</span><span>{insight.files.length.toLocaleString()} files</span></div><h2>{insight.title}</h2><strong className="insight-size">{bytes(insight.size)}</strong><p>{insight.reason}</p><div className="insight-evidence"><span>WHY IT APPEARS</span>{insight.evidence}</div><div className="insight-consequence">{insight.consequence}</div><button className="button secondary full-width" onClick={() => onReview(insight)}>Inspect these files <ArrowRight size={14}/></button></article>)}</div>
    {insights.length === 0 && <div className="panel empty-state"><ShieldCheck size={34}/><h3>Nothing to suggest from these rules.</h3><p>That doesn’t mean the drive is empty. Explore folders or compare duplicate contents to look further.</p></div>}
  </section>;
}
