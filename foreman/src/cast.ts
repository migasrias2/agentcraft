// The shared cast. Final colors/descriptions are owned by the art track in assets-src/cast.json
// ({id,name,role,color,accent,description}[]); we read it if present and fall back to placeholders.
import fs from 'node:fs';
import path from 'node:path';
import type { AgentRole } from './protocol.js';

export interface CastMember {
  id: string;
  name: string;
  role: AgentRole;
  title: string;
  color: string;
  accent?: string;
  description: string;
  /** which room: omitted = the coding studio; 'social' = the Social Desk; 'content' = the Content Room */
  team?: 'studio' | 'social' | 'content';
}

export const LEAD_ID = 'marlow';
export const SOCIAL_PLANNER_ID = 'nova';
export const SOCIAL_PUBLISHER_ID = 'sable';
export const SOCIAL_IDS = [SOCIAL_PLANNER_ID, SOCIAL_PUBLISHER_ID] as const;
export const CONTENT_IDS = ['iris', 'reel', 'vega', 'cleo'] as const;
export const CONTENT_PUBLISHER_ID = 'cleo';
export const WORKER_IDS = ['juniper', 'kit', 'wren', 'rowan', 'tove'] as const;

const PLACEHOLDER: CastMember[] = [
  { id: 'marlow', name: 'Marlow', role: 'lead', title: 'Lead', color: '#D97757', accent: '#3B2A20', description: 'Plans the work, splits it into tasks, reviews and asks you when it matters.' },
  { id: 'juniper', name: 'Juniper', role: 'worker', title: 'Worker', color: '#8FA98B', accent: '#F4EFE6', description: 'Careful generalist; likes CLIs and UX details.' },
  { id: 'kit', name: 'Kit', role: 'worker', title: 'Worker', color: '#2FA3A0', accent: '#1F1E1D', description: 'Fast backend tinkerer; writes the tests first.' },
  { id: 'wren', name: 'Wren', role: 'worker', title: 'Worker', color: '#C9A227', accent: '#3B2A20', description: 'Front-of-house polish: output, colors, docs.' },
  { id: 'rowan', name: 'Rowan', role: 'worker', title: 'Worker', color: '#B4553A', accent: '#E9E1D3', description: 'Documentation and release hygiene.' },
  { id: 'tove', name: 'Tove', role: 'worker', title: 'Worker', color: '#6F8FB5', accent: '#F4EFE6', description: 'Performance and tooling.' },
  { id: 'nova', name: 'Nova', role: 'worker', title: 'Social planner', color: '#B565A7', accent: '#F4EFE6', description: 'Plans the social calendar, drafts the posts and keeps your voice brief.', team: 'social' },
  { id: 'sable', name: 'Sable', role: 'worker', title: 'Social publisher', color: '#3E4C59', accent: '#E9E1D3', description: 'Publishes the posts you approved, in your own Chrome, and reports the link.', team: 'social' },
  { id: 'iris', name: 'Iris', role: 'worker', title: 'Showrunner', color: '#E4572E', accent: '#F4EFE6', description: 'Pitches the videos, iterates the ideas with you and keeps each channel\'s playbook.', team: 'content' },
  { id: 'reel', name: 'Reel', role: 'worker', title: 'Producer', color: '#29335C', accent: '#E9E1D3', description: 'Studies reference videos frame by frame, writes the script and makes the cut.', team: 'content' },
  { id: 'vega', name: 'Vega', role: 'worker', title: 'Analyst', color: '#17BEBB', accent: '#1F1E1D', description: 'Syncs the analytics, writes the post-mortems and finds what to copy next.', team: 'content' },
  { id: 'cleo', name: 'Cleo', role: 'worker', title: 'Publisher', color: '#7A5C61', accent: '#F4EFE6', description: 'Uploads the cuts you approved in YouTube Studio, in your own Chrome, and reports the link.', team: 'content' },
];

const HEX = /^#[0-9A-Fa-f]{6}$/;

export function loadCast(projectRoot: string | undefined): { cast: CastMember[]; source: string } {
  const file = projectRoot ? path.join(projectRoot, 'assets-src', 'cast.json') : undefined;
  if (!file || !fs.existsSync(file)) return { cast: PLACEHOLDER, source: 'placeholder' };
  try {
    const raw = JSON.parse(fs.readFileSync(file, 'utf8')) as unknown;
    // accepted shapes: [...], {agents:[...]}, {cast:[...]}
    const obj = raw as { agents?: unknown; cast?: unknown };
    const arr = Array.isArray(raw) ? raw : Array.isArray(obj.agents) ? obj.agents : Array.isArray(obj.cast) ? obj.cast : [];
    const byId = new Map<string, Record<string, unknown>>();
    for (const r of arr as Array<Record<string, unknown>>) if (typeof r?.id === 'string') byId.set(r.id, r);
    const cast = PLACEHOLDER.map((p) => {
      const r = byId.get(p.id);
      if (!r) return p;
      const title =
        typeof r.title === 'string' && r.title ? r.title : typeof r.role === 'string' && !['lead', 'worker'].includes(r.role) ? r.role : p.title;
      return {
        ...p,
        name: typeof r.name === 'string' && r.name ? r.name : p.name,
        title,
        color: typeof r.color === 'string' && HEX.test(r.color) ? r.color : p.color,
        accent: typeof r.accent === 'string' && HEX.test(r.accent) ? r.accent : p.accent,
        description: typeof r.description === 'string' ? r.description : p.description,
      } satisfies CastMember;
    });
    return { cast, source: file };
  } catch {
    return { cast: PLACEHOLDER, source: 'placeholder (cast.json unreadable)' };
  }
}
