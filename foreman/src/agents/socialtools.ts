// Team tools of the Social Desk agents (never offered to the coding team):
//   Nova (planner):    list_posts, create_post, update_post, cancel_post
//   Sable (publisher): list_posts, mark_posted, mark_failed
import { z } from 'zod';
import { SOCIAL_PUBLISHER_ID } from '../cast.js';
import type { Foreman } from '../foreman.js';
import { SOCIAL_TZ, SocialError, describePost, parseWhen } from '../social.js';
import type { AgentTool, ToolResult } from './tools.js';

type Reply = (text: string, isError?: boolean) => ToolResult;

const Platform = z.enum(['x', 'linkedin']);
const WHEN = `ISO 8601 date-time, e.g. 2026-10-14T09:30 (${SOCIAL_TZ} time unless it has an offset like +01:00 or Z)`;

function tool<S extends z.ZodRawShape>(name: string, description: string, shape: S, handler: (args: z.infer<z.ZodObject<S>>) => Promise<ToolResult> | ToolResult): AgentTool {
  return { name, description, shape, handler: async (a) => handler(a) };
}

/** Run a desk call; a SocialError becomes a tool error the agent can act on. */
function guarded(reply: Reply, fn: () => ToolResult): ToolResult {
  try {
    return fn();
  } catch (e) {
    if (e instanceof SocialError) return reply(`Error: ${e.message}`, true);
    throw e;
  }
}

export function socialTools(fm: Foreman, agentId: string, reply: Reply): AgentTool[] {
  const desk = fm.social;
  const listPosts = tool(
    'list_posts',
    'Show the Social Desk calendar (id, status, platform, time, text). Filter by time window and/or status.',
    {
      from: z.string().optional().describe(`${WHEN}; a bare date 2026-10-14 means the start of that day`),
      to: z.string().optional().describe(`${WHEN}; a bare date means the end of that day`),
      status: z.enum(['draft', 'approved', 'posting', 'posted', 'failed', 'cancelled']).optional(),
    },
    ({ from, to, status }) =>
      guarded(reply, () => {
        const posts = desk.list({ ...(from ? { from: parseWhen(from, 'start') } : {}), ...(to ? { to: parseWhen(to, 'end') } : {}), ...(status ? { status } : {}) });
        return reply(posts.length ? posts.map((p) => `- ${describePost(p)}`).join('\n') : 'No posts match.');
      }),
  );
  if (agentId === SOCIAL_PUBLISHER_ID) {
    return [
      listPosts,
      tool(
        'mark_posted',
        'Report that the post is live. Call it right after publishing, with the post URL you copied.',
        { id: z.string().describe('the post id you were asked to publish'), url: z.string().describe('link to the live post') },
        ({ id, url }) => guarded(reply, () => reply(`Marked ${desk.markPosted(id, url).id} as posted. End your turn.`)),
      ),
      tool(
        'mark_failed',
        'Report that you could NOT publish the post (not logged in, captcha, error, unsure whether it went out). Say exactly what happened. Never retry blindly.',
        { id: z.string(), error: z.string().describe('what happened and what you saw') },
        ({ id, error }) => guarded(reply, () => reply(`Marked ${desk.markFailed(id, error).id} as failed. End your turn.`)),
      ),
    ];
  }
  // the planner (and any other social agent): drafts only; the user approves in the calendar
  const by = agentId;
  return [
    listPosts,
    tool(
      'create_post',
      'Add a DRAFT post to the calendar. It only goes out after the user approves it in the calendar. Rejects text over the platform limit and times in the past.',
      {
        platform: Platform,
        text: z.string().describe('the exact post text'),
        scheduledAt: z.string().describe(WHEN),
        note: z.string().optional().describe('one line: why this post, why this slot'),
        mediaPaths: z.array(z.string()).optional().describe('local image/video files to attach (absolute, or relative to your workspace)'),
        allow_long: z.boolean().optional().describe('X only, and only if the user told you they have X Premium: raises the limit to 25000 characters'),
      },
      ({ platform, text, scheduledAt, note, mediaPaths, allow_long }) =>
        guarded(reply, () => {
          const p = desk.create({ platform, text, scheduledAt: parseWhen(scheduledAt), authorId: by, ...(note ? { note } : {}), ...(mediaPaths ? { mediaPaths } : {}), ...(allow_long ? { allowLong: true } : {}) });
          return reply(`Created draft ${describePost(p)}. It waits for the user's approval in the calendar.`);
        }),
    ),
    tool(
      'update_post',
      'Change a draft or failed post (or an approved one: changing text, time, platform or media sends it back to draft so the user approves it again).',
      {
        id: z.string(),
        text: z.string().optional(),
        scheduledAt: z.string().optional().describe(WHEN),
        platform: Platform.optional(),
        note: z.string().optional(),
        mediaPaths: z.array(z.string()).optional().describe('replaces the media list; [] removes it'),
        allow_long: z.boolean().optional().describe('X Premium only, see create_post'),
      },
      ({ id, text, scheduledAt, platform, note, mediaPaths, allow_long }) =>
        guarded(reply, () => {
          const p = desk.update(
            id,
            { ...(text !== undefined ? { text } : {}), ...(scheduledAt ? { scheduledAt: parseWhen(scheduledAt) } : {}), ...(platform ? { platform } : {}), ...(note !== undefined ? { note } : {}), ...(mediaPaths ? { mediaPaths } : {}), ...(allow_long ? { allowLong: true } : {}) },
            by,
          );
          return reply(`Updated ${describePost(p)}${p.status === 'draft' ? ' (draft: waits for the user\'s approval)' : ''}.`);
        }),
    ),
    tool('cancel_post', 'Cancel a post that is not yet posted.', { id: z.string() }, ({ id }) => guarded(reply, () => reply(`Cancelled ${describePost(desk.cancel(id, by))}.`))),
  ];
}
