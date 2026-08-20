import { spawn } from 'child_process'
import { existsSync, mkdirSync, readdirSync, statSync } from 'fs'
import { join, basename } from 'path'
import { app } from 'electron'
import { cookiesPath } from './store.js'
import type { SourceAnalysis, TransferProgress, DownloadJob, DownloadFormat } from './models.js'
import { PROGRESS_MARKER } from './models.js'

type AnalysisMode = 'QUICK' | 'FULL'

function ytDlpBin(): string {
  const resBin = app.isPackaged ? join(process.resourcesPath, 'bin') : join(app.getAppPath(), 'bin')
  const exe = process.platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp'
  const p = join(resBin, exe)
  if (existsSync(p)) return p
  return exe
}
function ffmpegBin(): string {
  const resBin = app.isPackaged ? join(process.resourcesPath, 'bin') : join(app.getAppPath(), 'bin')
  const exe = process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg'
  const p = join(resBin, exe)
  if (existsSync(p)) return p
  return exe
}

function cookieArgs(): string[] {
  const p = cookiesPath()
  return existsSync(p) ? ['--cookies', p] : []
}

export function isYtDlpAvailable(): boolean {
  try {
    return existsSync(ytDlpBin())
  } catch {
    return false
  }
}

async function runYtDlp(args: string[], timeoutMs = 45_000): Promise<{ stdout: string; stderr: string; code: number | null }> {
  return new Promise((resolve, reject) => {
    const bin = ytDlpBin()
    const child = spawn(bin, args, { windowsHide: true })
    let out = ''
    let err = ''
    const timer = setTimeout(() => {
      try {
        child.kill()
      } catch {}
      reject(new Error('yt-dlp timed out'))
    }, timeoutMs)
    child.stdout.on('data', (d: Buffer) => {
      out += d.toString()
    })
    child.stderr.on('data', (d: Buffer) => {
      err += d.toString()
    })
    child.on('error', (e) => {
      clearTimeout(timer)
      reject(e)
    })
    child.on('close', (code) => {
      clearTimeout(timer)
      resolve({ stdout: out, stderr: err, code })
    })
  })
}

export async function getEngineVersion(): Promise<string> {
  try {
    const r = await runYtDlp(['--version'], 10_000)
    const v = (r.stdout || r.stderr).split('\n').find((s) => s.trim())?.trim()
    return v || 'yt-dlp (unknown version)'
  } catch {
    return 'Bundled (yt-dlp)'
  }
}

export async function analyze(url: string, mode: AnalysisMode = 'FULL'): Promise<SourceAnalysis> {
  const isQuick = mode === 'QUICK'
  const args = ['--ignore-config', ...cookieArgs(), '--dump-single-json', '--flat-playlist', '--playlist-end', isQuick ? '3' : '100', '--skip-download', '--no-warnings', url]
  const { stdout, stderr, code } = await runYtDlp(args, isQuick ? 12_000 : 40_000)
  if (code !== 0) {
    const msg = (stderr || stdout).slice(0, 800) || `yt-dlp exited with code ${code}`
    throw new Error(msg)
  }
  let json: any
  try {
    json = JSON.parse(stdout)
  } catch {
    throw new Error('Could not parse video metadata. Try a different link.')
  }
  const entries = json.entries as any[] | undefined
  if (entries) return toPlaylist(json, entries, url)
  return toMedia(json, url, mode === 'FULL')
}

function toMedia(j: any, fallbackUrl: string, includeEstimates: boolean): SourceAnalysis {
  const formats: any[] = j.formats ?? []
  const est: Partial<Record<DownloadFormat, number | null>> = {}
  if (includeEstimates) {
    for (const f of ['BEST_MP4', 'MP4_1080', 'MP4_720', 'AUDIO_M4A', 'AUDIO_MP3'] as DownloadFormat[]) {
      est[f] = estimateSize(formats, f)
    }
  }
  return {
    kind: 'media',
    sourceUrl: (j.webpage_url as string) || fallbackUrl,
    title: (j.title as string) || 'Untitled media',
    uploader: (j.uploader as string) || null,
    durationSeconds: typeof j.duration === 'number' && j.duration > 0 ? Math.round(j.duration) : null,
    thumbnailUrl: (j.thumbnail as string) || null,
    estimatedSizes: est,
  }
}

function toPlaylist(j: any, entries: any[], fallbackUrl: string): SourceAnalysis {
  const preview = []
  for (let i = 0; i < Math.min(entries.length, 100); i++) {
    const e = entries[i]
    const id = (e.id as string) || String(i)
    const cand = (e.webpage_url as string) || (e.url as string) || ''
    if (!cand.startsWith('https://')) continue
    preview.push({ id, url: cand, title: (e.title as string) || `Playlist item ${i + 1}`, thumbnailUrl: (e.thumbnail as string) || null, durationSeconds: typeof e.duration === 'number' ? Math.round(e.duration) : null })
  }
  if (!preview.length) throw new Error('This playlist did not expose any public downloadable entries.')
  return { kind: 'playlist', sourceUrl: (j.webpage_url as string) || fallbackUrl, title: (j.title as string) || 'Playlist', uploader: (j.uploader as string) || null, entries: preview }
}

function estimateSize(formats: any[], target: DownloadFormat): number | null {
  if (!formats.length) return null
  let best: any = null
  for (const f of formats) {
    const h = f.height as number | undefined
    const ext = (f.ext as string) || ''
    let score = -1
    if (target === 'BEST_MP4' && ext === 'mp4') score = (h ?? 720) + 1000
    else if (target === 'MP4_1080' && ext === 'mp4' && (h ?? 9999) <= 1080) score = h ?? 0
    else if (target === 'MP4_720' && ext === 'mp4' && (h ?? 9999) <= 720) score = h ?? 0
    else if (target === 'AUDIO_M4A' && ext === 'm4a') score = 800
    else if (target === 'AUDIO_MP3' && (f.acodec as string)) score = 700
    if (score >= 0 && (!best || score > best._score || (score === best._score && (f.filesize ?? 0) > (best.filesize ?? 0)))) {
      best = { ...f, _score: score }
    }
  }
  if (!best) return null
  return (best.filesize as number) ?? (best.filesize_approx as number) ?? null
}

function downloadArgs(format: DownloadFormat): string[] {
  switch (format) {
    case 'ORIGINAL':
      throw new Error('Original handled by direct downloader')
    case 'BEST_MP4':
      return ['-f', 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best', '--merge-output-format', 'mp4']
    case 'MP4_1080':
      return ['-f', 'bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[height<=1080]', '--merge-output-format', 'mp4']
    case 'MP4_720':
      return ['-f', 'bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]', '--merge-output-format', 'mp4']
    case 'AUDIO_M4A':
      return ['-f', 'bestaudio[ext=m4a]/bestaudio', '--extract-audio', '--audio-format', 'm4a']
    case 'AUDIO_MP3':
      return ['-f', 'bestaudio', '--extract-audio', '--audio-format', 'mp3', '--audio-quality', '0']
  }
}

export function downloadMedia(
  job: DownloadJob,
  stagingDir: string,
  onProgress: (p: TransferProgress) => void,
  isCancelled: () => boolean,
  onChild?: (c: { kill(): void }) => void,
): Promise<{ filePath: string; fileName: string }> {
  return new Promise((resolve, reject) => {
    mkdirSync(stagingDir, { recursive: true })
    const before = new Set(readdirSync(stagingDir).map((n) => join(stagingDir, n)))
    // suffix pref lives in electron-store; import lazily to avoid cycle at top-level
    let suffixEnabled = true
    try {
      const { prefsStore: ps } = require('./store.js') as typeof import('./store.js')
      suffixEnabled = ps.get('filenameSuffixEnabled') !== false
    } catch {}
    const outTpl = join(stagingDir, suffixEnabled ? '%(title)s [HOLEN].%(ext)s' : '%(title)s.%(ext)s')
    const bin = ytDlpBin()
    const ff = ffmpegBin()
    const ffExists = existsSync(ff)
    const args = [
      '--ignore-config',
      ...cookieArgs(),
      ...downloadArgs(job.format as DownloadFormat),
      '--continue',
      '--windows-filenames',
      '--no-overwrites',
      '--embed-metadata',
      '--concurrent-fragments',
      '2',
      '--retries',
      '3',
      '--fragment-retries',
      '3',
      '--socket-timeout',
      '20',
      '--progress',
      '--progress-template',
      `download:${PROGRESS_MARKER} %(progress._percent_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s`,
      '--newline',
      '--no-playlist',
      '--output',
      outTpl,
      '--print',
      'after_move:filepath',
      ...(ffExists ? ['--ffmpeg-location', ff] : []),
      job.sourceUrl,
    ]
    const child = spawn(bin, args, { windowsHide: true })
    onChild?.({ kill: () => { try { child.kill() } catch {} } })
    let stdout = ''
    let stderr = ''
    let lastProgress: TransferProgress | null = null
    let lastEmit = 0

    const onData = (chunk: Buffer): void => {
      const text = chunk.toString()
      stderr += text
      for (const line of text.split('\n')) {
        const l = line.trim()
        if (!l) continue
        if (l.includes(PROGRESS_MARKER)) {
          const p = parseMachineLine(l)
          if (p) {
            lastProgress = merge(p, lastProgress)
            maybeEmit(p)
          }
        } else if (l.match(/\d+(?:\.\d+)?\s*%/)) {
          const p = parseHumanLine(l)
          if (p) {
            lastProgress = merge(p, lastProgress)
            maybeEmit(p)
          }
        }
        if (l && !l.includes(PROGRESS_MARKER) && !l.startsWith('[') && (l.includes('\\') || l.includes('/'))) {
          stdout += l + '\n'
        }
      }
    }
    function maybeEmit(p: TransferProgress): void {
      const now = Date.now()
      if (now - lastEmit >= 250 || p.percent >= 100) {
        if (!isCancelled()) onProgress(p)
        lastEmit = now
      }
    }
    function merge(a: TransferProgress, b: TransferProgress | null): TransferProgress {
      if (!b) return a
      return {
        percent: Math.max(b.percent, a.percent),
        bytesDownloaded: [b.bytesDownloaded, a.bytesDownloaded].filter((v): v is number => v != null).reduce((m, v) => Math.max(m, v), -1) >= 0 ? Math.max(...[b.bytesDownloaded, a.bytesDownloaded].filter((v): v is number => v != null)) : null,
        totalBytes: a.totalBytes ?? b.totalBytes ?? null,
        speedBytesPerSecond: a.speedBytesPerSecond ?? b.speedBytesPerSecond ?? null,
        etaSeconds: a.etaSeconds ?? b.etaSeconds ?? null,
      }
    }

    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.on('error', reject)
    child.on('close', (code) => {
      if (isCancelled()) return reject(new Error('Download cancelled'))
      if (code !== 0) {
        const msg = (stderr || stdout).slice(0, 1000) || `yt-dlp exited ${code}`
        return reject(new Error(msg))
      }
      const printed = stdout
        .split('\n')
        .map((s) => s.trim())
        .filter((s) => s && (s.includes('\\') || s.includes('/')))
        .pop()
      let file: string | null = null
      if (printed && existsSync(printed)) file = printed
      else {
        const nowFiles = readdirSync(stagingDir)
          .map((n) => join(stagingDir, n))
          .filter((p) => !before.has(p) && statSync(p).isFile())
        if (nowFiles.length) file = nowFiles.sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0]
        else {
          const all = readdirSync(stagingDir)
            .map((n) => join(stagingDir, n))
            .filter((p) => statSync(p).isFile())
            .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0]
          if (all) file = all
        }
      }
      if (!file || !existsSync(file)) return reject(new Error('The media engine completed without an output file.'))
      resolve({ filePath: file, fileName: basename(file) })
    })

    const iv = setInterval(() => {
      if (isCancelled()) {
        try {
          child.kill()
        } catch {}
        clearInterval(iv)
      }
    }, 250)
    child.on('close', () => clearInterval(iv))
  })
}

function parseMachineLine(line: string): TransferProgress | null {
  const idx = line.indexOf(PROGRESS_MARKER)
  if (idx < 0) return null
  const rest = line.slice(idx + PROGRESS_MARKER.length).trim()
  const f = rest.split('|')
  if (f.length !== 6) return null
  const pct = parseFloat(f[0].trim().replace('%', ''))
  if (!Number.isFinite(pct)) return null
  const n = (s: string): number | null => {
    const t = s.trim()
    if (!t || t.toUpperCase() === 'NA') return null
    const v = Number(t)
    return Number.isFinite(v) ? v : null
  }
  return { percent: Math.round(Math.min(100, Math.max(0, pct))), bytesDownloaded: n(f[1]), totalBytes: n(f[2]) ?? n(f[3]), speedBytesPerSecond: n(f[4]), etaSeconds: n(f[5]) }
}
function parseHumanLine(line: string): TransferProgress | null {
  const m = line.match(/(\d{1,3}(?:\.\d+)?)\s*%/)
  if (!m) return null
  const pct = Math.round(parseFloat(m[1]))
  const totM = line.match(/\bof\s+(?:~\s*)?([\d.,]+)\s*([KMGT]?i?B)/i)
  const tot = totM ? parseBytes(totM[1], totM[2]) : null
  const dl = tot != null ? Math.round((tot * pct) / 100) : null
  const spM = line.match(/\bat\s+([\d.,]+)\s*([KMGT]?i?B)\/s/i)
  const sp = spM ? parseBytes(spM[1], spM[2]) : null
  const etaM = line.match(/\bETA\s+(?:(\d+):)?(\d{1,2}):(\d{2})/i)
  let eta: number | null = null
  if (etaM) eta = parseInt(etaM[1] || '0', 10) * 3600 + parseInt(etaM[2], 10) * 60 + parseInt(etaM[3], 10)
  return { percent: pct, bytesDownloaded: dl, totalBytes: tot, speedBytesPerSecond: sp, etaSeconds: eta }
}
function parseBytes(num: string, unit: string): number {
  const mult: Record<string, number> = { B: 1, KB: 1e3, KIB: 1024, MB: 1e6, MIB: 1048576, GB: 1e9, GIB: 1073741824 }
  return Math.round(parseFloat(num.replace(/,/g, '')) * (mult[unit.toUpperCase()] ?? 1))
}
