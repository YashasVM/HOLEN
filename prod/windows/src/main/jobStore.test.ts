import { describe, it, expect, beforeEach, vi } from 'vitest'

vi.mock('electron', () => ({
  app: {
    getPath: (name: string) => {
      if (name === 'downloads') return '/tmp/holen-downloads'
      if (name === 'userData') return '/tmp/holen-test-userdata'
      return '/tmp'
    },
    getAppPath: () => '/tmp/holen-app',
  },
}))

import { sanitizeFileName } from './models.js'

describe('jobStore transitions', () => {
  beforeEach(async () => {
    const { rmSync, mkdirSync } = await import('fs')
    try { rmSync('/tmp/holen-test-userdata', { recursive: true, force: true }) } catch {}
    try { mkdirSync('/tmp/holen-test-userdata/holen', { recursive: true }) } catch {}
  })

  it('enforces state machine: RUNNING cannot go straight to COMPLETED', async () => {
    const { jobStore } = await import('./jobStore.js')
    const now = Date.now()
    const j = { id: 't1', sourceUrl: 'https://example.com/v', sourceKind: 'MEDIA' as const, format: 'BEST_MP4' as const, title: 't', thumbnailUrl: null, status: 'QUEUED' as const, progress: 0, bytesDownloaded: null, totalBytes: null, speedBytesPerSecond: null, etaSeconds: null, outputPath: null, outputUri: null, fileName: null, mimeType: null, errorMessage: null, createdAt: now, updatedAt: now }
    jobStore.insert([j])
    expect(jobStore.get('t1')?.status).toBe('QUEUED')
    expect(jobStore.transition('t1', 'RUNNING')).toBe(true)
    expect(jobStore.get('t1')?.status).toBe('RUNNING')
    expect(jobStore.transition('t1', 'COMPLETED')).toBe(false)
    expect(jobStore.get('t1')?.status).toBe('RUNNING')
    jobStore.remove('t1')
  })

  it('progress only in RUNNING', async () => {
    const { jobStore } = await import('./jobStore.js')
    const now = Date.now()
    const j = { id: 't2', sourceUrl: 'https://example.com/v2', sourceKind: 'MEDIA' as const, format: 'BEST_MP4' as const, title: 't2', thumbnailUrl: null, status: 'QUEUED' as const, progress: 0, bytesDownloaded: null, totalBytes: null, speedBytesPerSecond: null, etaSeconds: null, outputPath: null, outputUri: null, fileName: null, mimeType: null, errorMessage: null, createdAt: now, updatedAt: now }
    jobStore.insert([j])
    expect(jobStore.progress('t2', { percent: 50 })).toBe(false)
    jobStore.transition('t2', 'RUNNING')
    expect(jobStore.progress('t2', { percent: 50 })).toBe(true)
    expect(jobStore.get('t2')?.progress).toBe(50)
    jobStore.remove('t2')
  })
})
