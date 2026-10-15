// Pull image blocks (screenshots) out of a tool_result content value.
import { SCREEN_MAX_B64 } from '../../screens.js';

export interface ToolImage {
  mime: string;
  data: string;
}

/** Base64 image blocks of a tool_result `content`, in order. Oversized or malformed ones are skipped. */
export function extractImages(content: unknown, maxB64 = SCREEN_MAX_B64): ToolImage[] {
  if (!Array.isArray(content)) return [];
  const out: ToolImage[] = [];
  for (const item of content) {
    if (!item || typeof item !== 'object') continue;
    const c = item as { type?: unknown; source?: { type?: unknown; media_type?: unknown; data?: unknown } };
    if (c.type !== 'image' || !c.source || c.source.type !== 'base64') continue;
    const { media_type: mime, data } = c.source;
    if (typeof mime !== 'string' || !mime.startsWith('image/') || typeof data !== 'string') continue;
    if (data.length === 0 || data.length > maxB64) continue;
    out.push({ mime, data });
  }
  return out;
}
