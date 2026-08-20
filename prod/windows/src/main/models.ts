export type SourceKind = 'DIRECT_FILE' | 'MEDIA'
export type DownloadFormat = 'ORIGINAL' | 'BEST_MP4' | 'MP4_1080' | 'MP4_720' | 'AUDIO_M4A' | 'AUDIO_MP3'
export type JobStatus = 'QUEUED' | 'RUNNING' | 'FINALIZING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface SourceAnalysisDirect {
  kind: 'direct'
  sourceUrl: string
  title: string
  fileName: string
  mimeType: string | null
  sizeBytes: number | null
}
export interface SourceAnalysisMedia {
  kind: 'media'
  sourceUrl: string
  title: string
  uploader: string | null
  durationSeconds: number | null
  thumbnailUrl: string | null
  estimatedSizes: Partial<Record<DownloadFormat, number | null>>
}
export interface SourceAnalysisPlaylist {
  kind: 'playlist'
  sourceUrl: string
  title: string
  uploader: string | null
  entries: PlaylistEntry[]
}
export type SourceAnalysis = SourceAnalysisDirect | SourceAnalysisMedia | SourceAnalysisPlaylist

export interface PlaylistEntry {
  id: string
  url: string
  title: string
  thumbnailUrl: string | null
  durationSeconds: number | null
}

export interface DownloadJob {
  id: string
  sourceUrl: string
  sourceKind: SourceKind
  format: DownloadFormat
  title: string
  thumbnailUrl: string | null
  status: JobStatus
  progress: number
  bytesDownloaded: number | null
  totalBytes: number | null
  speedBytesPerSecond: number | null
  etaSeconds: number | null
  outputPath: string | null
  outputUri: string | null
  fileName: string | null
  mimeType: string | null
  errorMessage: string | null
  createdAt: number
  updatedAt: number
}

export interface TransferProgress {
  percent: number
  bytesDownloaded: number | null
  totalBytes: number | null
  speedBytesPerSecond: number | null
  etaSeconds: number | null
}

export const PROGRESS_MARKER = 'HOLEN_PROGRESS'

export function sanitizeFileName(value: string, fallback = 'download'): string {
  const clean = value
    .split('/').pop()!.split('\\').pop()!
    .replace(/[\u0000-\u001f<>:\"/\\|?*]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\.+|\.+$/g, '')
    .slice(0, 180)
  return clean || fallback
}

export function validateHttpsUrl(raw: string): string {
  const v = raw.trim()
  if (v.length < 1 || v.length > 4096) throw new Error('Enter an HTTPS URL up to 4,096 characters.')
  const u = (() => { try { return new URL(v) } catch { throw new Error('Enter a valid HTTPS URL.') } })()
  if (u.protocol !== 'https:' || !u.hostname) throw new Error('Only HTTPS links are supported.')
  if (u.username || u.password) throw new Error('Links containing credentials are not supported.')
  return u.toString()
}

export function friendlyFailure(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err ?? '')
  const n = msg.toLowerCase()
  if (n.includes('drm')) return 'This source is DRM-protected and cannot be downloaded.'
  if (n.includes("confirm you're not a bot") || n.includes('confirm you\u2019re not a bot') || n.includes('verify you are human') || n.includes('unusual traffic') || n.includes('http error 429')) return 'The source asked for a bot check. Wait a little, then retry; valid cookies may help.'
  if (['age-restricted','age restricted','age verification','verify your age','confirm your age','age-confirmation'].some(s=>n.includes(s))) return 'This video needs age verification. Use fresh cookies from an account permitted to watch it.'
  if (['login required','sign in required','sign in to confirm','please sign in','authentication required','members-only','members only','this video is private'].some(s=>n.includes(s))) return 'This source needs a signed-in account. Add fresh cookies from an account permitted to access it.'
  if (n.includes('unsupported')) return 'This URL is not supported by the current engine.'
  if (msg.startsWith('Network response ')) { const s = msg.slice('Network response '.length).match(/^\d+/)?.[0]; return s ? `The server returned HTTP ${s}. Check the link and try again.` : 'The server rejected the request.' }
  if (n.includes('enospc') || n.includes('no space')) return 'There is not enough storage space.'
  if (n.includes('permission') || n.includes('dened') || n.includes('denied')) return 'Write permission was denied. Choose a different folder.'
  if (n.includes('timed out') || n.includes('timeout')) return 'The network timed out. Retry to continue the partial download.'
  if (n.includes('media engine') || n.includes('youtubedl') || n.includes('ffmpeg') || n.includes('yt-dlp')) return 'The media engine could not start. Reset or update it in Settings.'
  if (n.includes('network') || msg.includes('ECONN')) return 'The network transfer failed. Retry to continue the partial download.'
  const first = msg.split('\n').find(s=>s.trim())?.replace(/^ERROR:\s*/,'').slice(0,180)
  return first || 'The download failed. Try again.'
}

export function formatBytes(v: number): string {
  const units = ['B','KB','MB','GB','TB']
  let a = v, u = 0
  while (a >= 1000 && u < units.length-1) { a/=1000; u++ }
  return u===0 ? `${Math.round(a)} ${units[u]}` : `${a.toFixed(1)} ${units[u]}`
}
export function formatDuration(s: number): string {
  return s >= 3600 ? `${Math.floor(s/3600)}:${String(Math.floor(s%3600/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}` : `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`
}

export function canTransition(from: JobStatus, to: JobStatus): boolean {
  const m: Record<JobStatus, JobStatus[]> = {
    QUEUED: ['RUNNING','CANCELLED'],
    RUNNING: ['QUEUED','FINALIZING','FAILED','CANCELLED'],
    FINALIZING: ['QUEUED','COMPLETED','FAILED','CANCELLED'],
    FAILED: ['QUEUED'], CANCELLED: ['QUEUED'], COMPLETED: [],
  }
  return m[from].includes(to)
}

export function parseByteAmount(num: string, unit: string): number {
  const mult: Record<string, number> = { KB:1e3,KIB:1024,MB:1e6,MIB:1048576,GB:1e9,GIB:1073741824,TB:1e12,TIB:1099511627776,B:1 }
  return Math.round(parseFloat(num.replace(/,/g,'')) * (mult[unit.toUpperCase()] ?? 1))
}

export function parseTransferLine(line: string): TransferProgress | null {
  const m = parseMachineTransferLine(line)
  if (m) return m
  const pct = line.match(/(\d{1,3}(?:\.\d+)?)\s*%/)?.[1]
  if (!pct) return null
  const percent = Math.min(100, Math.max(0, Math.round(parseFloat(pct))))
  const totalM = line.match(/\bof\s+(?:~\s*)?([\d.,]+)\s*([KMGT]?i?B)/i)
  const total = totalM ? parseByteAmount(totalM[1], totalM[2]) : null
  const downloaded = total != null ? Math.round(total * percent / 100) : null
  const speedM = line.match(/\bat\s+([\d.,]+)\s*([KMGT]?i?B)\/s/i)
  const speed = speedM ? parseByteAmount(speedM[1], speedM[2]) : null
  const etaM = line.match(/\bETA\s+(?:(\d+):)?(\d{1,2}):(\d{2})/i)
  let eta: number | null = null
  if (etaM) { const h=parseInt(etaM[1]||'0',10), mm=parseInt(etaM[2],10), ss=parseInt(etaM[3],10); eta = h*3600+mm*60+ss }
  return { percent, bytesDownloaded: downloaded, totalBytes: total, speedBytesPerSecond: speed, etaSeconds: eta }
}

export function parseMachineTransferLine(line: string): TransferProgress | null {
  if (!line.trim().startsWith(PROGRESS_MARKER)) return null
  const fields = line.trim().slice(PROGRESS_MARKER.length).trim().split('|')
  if (fields.length !== 6) return null
  const p = parseFloat(fields[0].trim().replace('%',''))
  if (!Number.isFinite(p)) return null
  const toN = (s: string) => { const t=s.trim(); if(!t||t.toUpperCase()==='NA') return null; const n=Number(t); return Number.isFinite(n)? n : null }
  const dl = toN(fields[1]), tot = toN(fields[2]), est = toN(fields[3]), sp = toN(fields[4]), eta = toN(fields[5])
  return { percent: Math.min(100, Math.max(0, Math.round(p))), bytesDownloaded: dl, totalBytes: tot ?? est, speedBytesPerSecond: sp, etaSeconds: eta }
}

export function transferProgressFromCallback(line: string, wrapperPercent: number, wrapperEta: number, prev: TransferProgress | null): TransferProgress | null {
  const parsed = parseTransferLine(line)
  const wv = Number.isFinite(wrapperPercent) && wrapperPercent>=0 && wrapperPercent<=100 ? Math.round(wrapperPercent) : null
  const raw = parsed?.percent ?? wv ?? prev?.percent ?? null
  if (raw == null) return null
  const percent = Math.min(100, Math.max(prev?.percent ?? 0, raw))
  const total = parsed?.totalBytes ?? prev?.totalBytes ?? null
  const downloaded = parsed?.bytesDownloaded ?? (total != null ? Math.round(total*percent/100) : prev?.bytesDownloaded ?? null)
  const eta = parsed?.etaSeconds ?? (wrapperEta >= 0 ? wrapperEta : prev?.etaSeconds ?? null)
  return { percent, bytesDownloaded: downloaded, totalBytes: total, speedBytesPerSecond: parsed?.speedBytesPerSecond ?? prev?.speedBytesPerSecond ?? null, etaSeconds: eta }
}

export function mergeTransferProgress(prev: TransferProgress | null, cand: TransferProgress): TransferProgress {
  if (!prev) return cand
  return {
    percent: Math.min(100, Math.max(prev.percent, cand.percent)),
    bytesDownloaded: [prev.bytesDownloaded, cand.bytesDownloaded].filter((v): v is number => v != null).reduce((a,b)=>Math.max(a,b), -1) >=0 ? Math.max(...[prev.bytesDownloaded, cand.bytesDownloaded].filter((v): v is number => v!=null)) : null,
    totalBytes: cand.totalBytes ?? prev.totalBytes ?? null,
    speedBytesPerSecond: cand.speedBytesPerSecond ?? prev.speedBytesPerSecond ?? null,
    etaSeconds: cand.etaSeconds ?? prev.etaSeconds ?? null,
  }
}
