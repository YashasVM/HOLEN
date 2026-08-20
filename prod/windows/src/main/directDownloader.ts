import { createWriteStream, existsSync, statSync } from 'fs'
import { basename, join } from 'path'
import type { DownloadJob, TransferProgress } from './models.js'

export async function downloadDirect(
  job: DownloadJob,
  stagingDir: string,
  onProgress: (p: TransferProgress) => void,
  isCancelled: () => boolean,
  onChild?: (c: { kill(): void }) => void,
): Promise<{ filePath: string; fileName: string }> {
  const url = job.sourceUrl
  const u = new URL(url)
  if (u.protocol !== 'https:') throw new Error('Only HTTPS links are supported.')
  const name = basename(u.pathname) || job.title || 'download'
  const safe = name.replace(/[\\/:*?"<>|]/g, '_').slice(0, 180) || 'download'
  const dest = join(stagingDir, safe)

  let start = 0
  if (existsSync(dest)) {
    try {
      start = statSync(dest).size
    } catch {
      start = 0
    }
  }
  const headers: Record<string, string> = { 'User-Agent': 'HOLEN/3.0 Windows' }
  if (start > 0) headers.Range = `bytes=${start}-`

  const controller = new AbortController()
  const kill = () => controller.abort()
  onChild?.({ kill })
  if (isCancelled()) {
    controller.abort()
    throw new Error('Download cancelled')
  }

  const res = await fetch(url, { headers, redirect: 'follow', signal: controller.signal } as any)
  // yt-dlp-style resume: if server ignores Range (200 not 206), truncate and restart
  const isResume = res.status === 206
  if (!res.ok && !isResume) throw new Error(`Network response ${res.status}`)
  if (start > 0 && !isResume) start = 0

  const totalHeader = res.headers.get('content-length')
  const total = totalHeader ? (isResume ? start : 0) + parseInt(totalHeader, 10) : (job.totalBytes ?? null)

  const out = createWriteStream(dest, { flags: isResume ? 'a' : 'w' })

  const body = res.body as unknown as ReadableStream<Uint8Array> | null
  if (!body) {
    out.close()
    throw new Error('No response body')
  }
  const reader = (body as any).getReader ? (body as any).getReader() as ReadableStreamDefaultReader<Uint8Array> : null

  let downloaded = start
  let lastEmit = 0
  let speedWindow: { t: number; b: number }[] = []

  const pump = async (): Promise<void> => {
    if (reader) {
      while (true) {
        if (isCancelled()) {
          controller.abort()
          throw new Error('Download cancelled')
        }
        const { done, value } = await reader.read()
        if (done) break
        if (value) {
          if (isCancelled()) {
            controller.abort()
            throw new Error('Download cancelled')
          }
          await new Promise<void>((res2, rej) => out.write(value, (e) => (e ? rej(e) : res2())))
          downloaded += value.byteLength
          const now = Date.now()
          speedWindow.push({ t: now, b: downloaded })
          speedWindow = speedWindow.filter((x) => now - x.t < 3000)
          if (now - lastEmit >= 250) {
            const speed = speedWindow.length >= 2 ? Math.round((speedWindow[speedWindow.length - 1].b - speedWindow[0].b) / Math.max(1, (speedWindow[speedWindow.length - 1].t - speedWindow[0].t) / 1000)) : null
            const pct = total ? Math.min(100, Math.round((downloaded / total) * 100)) : 0
            const eta = speed && total ? Math.round((total - downloaded) / speed) : null
            onProgress({ percent: pct, bytesDownloaded: downloaded, totalBytes: total, speedBytesPerSecond: speed, etaSeconds: eta })
            lastEmit = now
          }
        }
      }
    } else {
      const ab = await res.arrayBuffer()
      if (isCancelled()) throw new Error('Download cancelled')
      await new Promise<void>((res2, rej) => out.write(Buffer.from(ab), (e) => (e ? rej(e) : res2())))
      downloaded = start + ab.byteLength
    }
  }

  try {
    await pump()
  } catch (e: any) {
    // ensure stream closed on abort/error
    try {
      out.close()
    } catch {}
    if (isCancelled() || e?.name === 'AbortError' || /aborted|cancelled/i.test(String(e?.message ?? ''))) throw new Error('Download cancelled')
    throw e
  } finally {
    await new Promise<void>((r) => out.end(() => r()))
  }
  if (isCancelled()) throw new Error('Download cancelled')
  onProgress({ percent: 100, bytesDownloaded: downloaded, totalBytes: downloaded, speedBytesPerSecond: null, etaSeconds: 0 })
  const cd = res.headers.get('content-disposition') ?? ''
  const m = cd.match(/filename\*?=(?:UTF-8''|")?([^";\n]+)/i)
  const finalName = m ? decodeURIComponent(m[1].replace(/"/g, '')) : safe
  if (finalName !== safe) {
    const alt = join(stagingDir, finalName.replace(/[\\/:*?"<>|]/g, '_').slice(0, 180))
    try {
      const { renameSync } = await import('fs')
      renameSync(dest, alt)
      return { filePath: alt, fileName: finalName }
    } catch {
      // keep dest
    }
  }
  return { filePath: dest, fileName: finalName }
}
