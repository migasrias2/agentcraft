import { describe, expect, it } from 'vitest';
import { routeGoal } from '../src/reporoute.js';

const home = '/Users/me';
const repo = (rel: string) => ({ id: rel, name: rel.split('/').pop()!, path: `${home}/${rel}` });
const repos = ['website', 'website-2', 'shop.example', 'moonbase', 'Pulse', 'Pulse/Pulse', 'Desktop/side-project', 'Documents/New project'].map(repo);
const route = (t: string) => routeGoal(t, repos, home);

describe('routeGoal', () => {
  it('takes an explicit "<repo>:" prefix and strips it', () => {
    expect(route('moonbase: make the lighting moodier')).toEqual({ repoId: 'moonbase', text: 'make the lighting moodier' });
    expect(route('Desktop/side-project: add tests')).toEqual({ repoId: 'Desktop/side-project', text: 'add tests' });
    expect(route('New project: scaffold it')).toEqual({ repoId: 'Documents/New project', text: 'scaffold it' });
  });

  it('finds a repo named anywhere, as a whole word, case-insensitive', () => {
    expect(route('fix the login bug in shop.example')?.repoId).toBe('shop.example');
    expect(route('Polish the UI in Moonbase')?.repoId).toBe('moonbase');
    expect(route('moonbaseing the bugs')).toBeUndefined();
  });

  it('prefers the longest match', () => {
    expect(route('update website-2 hero')?.repoId).toBe('website-2');
  });

  it('prefers the shallower repo between equal names, and a path picks the deeper one', () => {
    expect(route('pulse: dashboards')?.repoId).toBe('Pulse');
    expect(route('in pulse/pulse fix auth')?.repoId).toBe('Pulse/Pulse');
  });

  it('returns undefined when no repo is named, and ignores unknown prefixes', () => {
    expect(route('make it look polished')).toBeUndefined();
    expect(route('note: make it polished')).toBeUndefined();
  });
});
