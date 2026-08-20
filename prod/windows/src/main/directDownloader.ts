import { createWriteStream, existsSync, statSync, rmSync } from 'fs'
import { basename, join } from 'path'
import { finished } from 'stream/promises'
import { Readable } from 'stream'
import type { DownloadJob, TransferProgress } from './models.js'

export async function downloadDirect(
  job: DownloadJob,
  stagingDir: string,
  onProgress: (p: TransferProgress) => void,
  isCancelled: () => boolean,
): Promise<{ filePath: string; fileName: string }> {
  const url = job.sourceUrl
  const u = new URL(url)
  if (u.protocol !== 'https:') throw new Error('Only HTTPS links are supported.')
  const name = basename(u.pathname) || job.title || 'download'
  const safe = name.replace(/[\\/:*?"<>|]/g, '_').slice(0, 180) || 'download'
  const dest = join(stagingDir, safe)

  // Resume support via Range
  let start = 0
  if (existsSync(dest)) {
    try { start = statSync(dest).size } catch { start = 0 }
  }
  const headers: Record<string,string> = { 'User-Agent': 'HOLEN/3.0 Windows' }
  if (start > 0) headers['Range'] = `bytes=${start}-`

  const res = await fetch(url, { headers, redirect: 'follow' } as any)
  if (!res.ok && res.status !== 206) throw new Error(`Network response ${res.status}`)
  const totalHeader = res.headers.get('content-length')
  const total = totalHeader ? start + parseInt(totalHeader, 10) : (job.totalBytes ?? null)
  const contentType = res.headers.get('content-type') ?? null

  // If server ignored Range and sent 200, truncate
  const out = createWriteStream(dest, { flags: start > 0 && res.status === 206 ? 'a' : 'w' })
  if (start > 0 && res.status !== 206) start = 0

  const body = res.body as unknown as ReadableStream<Uint8Array> | null
  if (!body) throw new Error('No response body')
  const reader = (body as any).getReader ? (body as any).getReader() : null

  let downloaded = start
  let lastEmit = 0
  let speedWindow: { t: number; b: number }[] = []

  const pump = async () => {
    if (reader) {
      while (true) {
        if (isCancelled()) { try { out.close() } catch {}; throw new Error('Download cancelled') }
        const { done, value } = await reader.read()
        if (done) break
        if (value) {
          await new Promise<void>((res, rej) => out.write(value, (e) => e ? rej(e) : res()))
          downloaded += value.byteLength
          const now = Date.now()
          speedWindow.push({ t: now, b: downloaded })
          speedWindow = speedWindow.filter(x => now - x.t < 3000)
          if (now - lastEmit >= 250) {
            const speed = speedWindow.length >= 2 ? Math.round((speedWindow[speedWindow.length-1].b - speedWindow[0].b) / Math.max(1, (speedWindow[speedWindow.length-1].t - speedWindow[0].t)/1000)) : null
            const pct = total ? Math.min(100, Math.round(downloaded / total * 100)) : 0
            const eta = speed && total ? Math.round((total - downloaded) / speed) : null
            onProgress({ percent: pct, bytesDownloaded: downloaded, totalBytes: total, speedBytesPerSecond: speed, etaSeconds: eta })
            lastEmit = now
          }
        }
      }
    } else {
      // Node fetch returns web stream; fallback using arrayBuffer chunks via stream
      const ab = await res.arrayBuffer()
      if (isCancelled()) throw new Error('Download cancelled')
      await new Promise<void>((res2, rej) => out.write(Buffer.from(ab), (e)=> e? rej(e): res2()))
      downloaded = start + ab.byteLength
    }
  }

  try {
    await pump()
  } finally {
    await new Promise<void>(r => out.end(()=> r()))
  }
  if (isCancelled()) throw new Error('Download cancelled')
  // final progress
  onProgress({ percent: 100, bytesDownloaded: downloaded, totalBytes: downloaded, speedBytesPerSecond: null, etaSeconds: 0 })
  // determine filename from content-disposition
  const cd = res.headers.get('content-disposition') ?? ''
  const m = cd.match(/filename\*?=(?:UTF-8''|")?([^";\n]+)/i)
  const finalName = m ? decodeURIComponent(m[1].replace(/"/g,'')) : safe
  if (finalName !== safe) {
    const alt = join(stagingDir, finalName.replace(/[\\/:*?"<>|]/g,'_').slice(0,180))
    try { const { renameSync } = await import('fs'); renameSync(dest, alt); return { filePath: alt, fileName: finalName } } catch { /* keep dest */}
  }
  void contentType
  return { filePath: dest, fileName: finalName }
}
