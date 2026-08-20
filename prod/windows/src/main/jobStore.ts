import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs'
import { join } from 'path'
import { dataDir, jobsDbPath } from './store.js'
import type { DownloadJob } from './models.js'

class JobStore {
  private jobs: DownloadJob[] = []
  private loaded = false
  private listeners: Set<(jobs: DownloadJob[]) => void> = new Set()

  private load(): void {
    if (this.loaded) return
    this.loaded = true
    const p = jobsDbPath()
    if (!existsSync(p)) { this.jobs = []; return }
    try {
      const raw = readFileSync(p, 'utf8')
      const arr = JSON.parse(raw) as DownloadJob[]
      this.jobs = Array.isArray(arr) ? arr : []
    } catch { this.jobs = [] }
  }

  private save(): void {
    try {
      mkdirSync(dataDir(), { recursive: true })
      writeFileSync(jobsDbPath(), JSON.stringify(this.jobs, null, 2), 'utf8')
    } catch {}
    for (const l of this.listeners) l(this.snapshot())
  }

  private snapshot(): DownloadJob[] {
    return [...this.jobs].sort((a,b) => b.createdAt - a.createdAt).slice(0, 200)
  }

  onChange(fn: (jobs: DownloadJob[]) => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  all(): DownloadJob[] { this.load(); return this.snapshot() }
  get(id: string): DownloadJob | null { this.load(); return this.jobs.find(j=>j.id===id) ?? null }

  insert(jobs: DownloadJob[]): void {
    this.load()
    this.jobs.push(...jobs)
    this.save()
  }

  update(id: string, patch: Partial<DownloadJob>): boolean {
    this.load()
    const i = this.jobs.findIndex(j=>j.id===id)
    if (i < 0) return false
    this.jobs[i] = { ...this.jobs[i], ...patch, updatedAt: Date.now() }
    this.save()
    return true
  }

  transition(id: string, status: DownloadJob['status'], errorMessage?: string | null, resetProgress?: boolean): boolean {
    this.load()
    const j = this.jobs.find(x=>x.id===id)
    if (!j) return false
    const patch: Partial<DownloadJob> = { status, errorMessage: errorMessage ?? null }
    if (resetProgress) { patch.progress = 0; patch.bytesDownloaded = null; patch.totalBytes = null; patch.speedBytesPerSecond = null; patch.etaSeconds = null }
    return this.update(id, patch)
  }

  progress(id: string, p: { percent: number; bytesDownloaded?: number | null; totalBytes?: number | null; speedBytesPerSecond?: number | null; etaSeconds?: number | null }): boolean {
    this.load()
    const j = this.jobs.find(x=>x.id===id)
    if (!j || j.status !== 'RUNNING') return false
    return this.update(id, {
      progress: p.percent,
      bytesDownloaded: p.bytesDownloaded ?? j.bytesDownloaded,
      totalBytes: p.totalBytes ?? j.totalBytes,
      speedBytesPerSecond: p.speedBytesPerSecond ?? j.speedBytesPerSecond,
      etaSeconds: p.etaSeconds ?? j.etaSeconds,
    })
  }

  complete(id: string, outputPath: string, outputUri: string, fileName: string, mimeType: string, byteCount: number): boolean {
    return this.update(id, {
      status: 'COMPLETED', progress: 100,
      bytesDownloaded: byteCount, totalBytes: byteCount,
      speedBytesPerSecond: null, etaSeconds: 0,
      outputPath, outputUri, fileName, mimeType, errorMessage: null,
    })
  }

  requeueInterrupted(): number {
    this.load()
    let n = 0
    for (const j of this.jobs) if (j.status === 'RUNNING' || j.status === 'FINALIZING') {
      j.status = 'QUEUED'; j.progress = 0; j.bytesDownloaded = null; j.totalBytes = null; j.speedBytesPerSecond = null; j.etaSeconds = null
      j.errorMessage = 'Interrupted. Resuming download.'; j.updatedAt = Date.now(); n++
    }
    if (n) this.save()
    return n
  }

  claimNextQueued(excluded: Set<string> = new Set()): DownloadJob | null {
    this.load()
    const cand = this.jobs.filter(j=> j.status==='QUEUED' && !excluded.has(j.id)).sort((a,b)=>a.createdAt-b.createdAt)[0]
    if (!cand) return null
    cand.status = 'RUNNING'; cand.errorMessage = null; cand.updatedAt = Date.now()
    this.save()
    return { ...cand }
  }

  hasQueued(): boolean { this.load(); return this.jobs.some(j=>j.status==='QUEUED') }
  hasRecoverable(): boolean { this.load(); return this.jobs.some(j=> j.status==='QUEUED' || j.status==='RUNNING' || j.status==='FINALIZING') }

  cancelIfQueued(id: string): boolean {
    this.load()
    const j = this.jobs.find(x=>x.id===id)
    if (!j || j.status !== 'QUEUED') return false
    return this.transition(id, 'CANCELLED')
  }
  cancelActive(id: string): boolean {
    this.load()
    const j = this.jobs.find(x=>x.id===id)
    if (!j || !['QUEUED','RUNNING','FINALIZING'].includes(j.status)) return false
    return this.transition(id, 'CANCELLED')
  }

  clearFinished(ids?: Set<string>): void {
    this.load()
    const terminal = new Set(['COMPLETED','FAILED','CANCELLED'])
    if (!ids || ids.size===0) this.jobs = this.jobs.filter(j=> !terminal.has(j.status))
    else this.jobs = this.jobs.filter(j=> !(terminal.has(j.status) && ids.has(j.id)))
    this.save()
  }
  remove(id: string): void { this.load(); this.jobs = this.jobs.filter(j=>j.id!==id); this.save() }
  knownIds(): Set<string> { this.load(); return new Set(this.jobs.map(j=>j.id)) }
}

export const jobStore = new JobStore()
