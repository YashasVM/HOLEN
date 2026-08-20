import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron'
import { join } from 'path'
import { existsSync, mkdirSync, readdirSync, statSync, copyFileSync, rmSync, renameSync } from 'fs'
import { cp } from 'fs/promises'
import { jobStore } from './jobStore.js'
import { prefsStore, getPrefs, ensureDownloadDir, ONBOARDING_VERSION, cookiesPath, saveCookies, clearCookies, hasCookies, readCookies, dataDir, stagingRoot, cleanOrphanStaging } from './store.js'
import { analyze as ytAnalyze, downloadMedia, getEngineVersion, isYtDlpAvailable } from './ytDlp.js'
import { downloadDirect } from './directDownloader.js'
import { validateHttpsUrl, friendlyFailure, sanitizeFileName } from './models.js'
import type { DownloadFormat, DownloadJob, SourceAnalysis } from './models.js'

// Single instance
const gotLock = app.requestSingleInstanceLock()
if (!gotLock) app.quit()

let mainWindow: BrowserWindow | null = null
let queueRunning = false
const cancelledIds = new Set<string>()
let currentJobId: string | null = null

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 780,
    minWidth: 980,
    minHeight: 640,
    backgroundColor: '#0f0f0f',
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#0f0f0f', symbolColor: '#fafafa', height: 32 },
    webPreferences: {
      preload: join(app.getAppPath(), 'dist/preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
    show: false,
  })
  mainWindow.once('ready-to-show', () => mainWindow?.show())
  if (process.env.ELECTRON_VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.ELECTRON_VITE_DEV_SERVER_URL)
  } else {
    mainWindow.loadFile(join(app.getAppPath(), 'dist/renderer/index.html'))
  }
  mainWindow.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' } })
}

app.whenReady().then(() => {
  // protocol for app://
  ensureDownloadDir()
  jobStore.requeueInterrupted()
  cleanOrphanStaging(jobStore.knownIds())
  createWindow()
  // handle second instance: focus + optional URL
  app.on('second-instance', (_e, argv) => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
      const url = argv.find(a => a.startsWith('https://'))
      if (url) mainWindow.webContents.send('external-url', url)
    }
  })
})

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
app.on('activate', () => { if (!mainWindow) createWindow() })

// ---- IPC ----

function sendJobs() {
  mainWindow?.webContents.send('jobs:update', jobStore.all())
}
jobStore.onChange(() => sendJobs())

ipcMain.handle('app:info', () => ({
  version: app.getVersion(),
  isPackaged: app.isPackaged,
  platform: process.platform,
}))

ipcMain.handle('prefs:get', () => getPrefs())
ipcMain.handle('prefs:set', (_e, patch: Record<string, unknown>) => {
  for (const [k,v] of Object.entries(patch)) (prefsStore as any).set(k, v)
  return getPrefs()
})

ipcMain.handle('dialog:chooseFolder', async () => {
  const r = await dialog.showOpenDialog(mainWindow!, { properties: ['openDirectory', 'createDirectory'] })
  if (r.canceled || !r.filePaths[0]) return null
  prefsStore.set('downloadDir', r.filePaths[0])
  return r.filePaths[0]
})
ipcMain.handle('dialog:openFolder', async (_e, p?: string) => {
  const dir = p || getPrefs().downloadDir
  try { mkdirSync(dir, { recursive: true }) } catch {}
  await shell.openPath(dir)
  return true
})
ipcMain.handle('shell:openPath', async (_e, p: string) => shell.openPath(p))
ipcMain.handle('shell:showItem', async (_e, p: string) => shell.showItemInFolder(p))
ipcMain.handle('shell:openExternal', async (_e, url: string) => shell.openExternal(url))

ipcMain.handle('cookies:get', () => ({ configured: hasCookies(), preview: hasCookies() ? readCookies().slice(0, 400) : '' }))
ipcMain.handle('cookies:save', (_e, text: string) => { saveCookies(text); return { ok: true } })
ipcMain.handle('cookies:clear', () => { clearCookies(); return { ok: true } })
ipcMain.handle('cookies:path', () => cookiesPath())

ipcMain.handle('engine:status', async () => ({
  available: isYtDlpAvailable(),
  version: await getEngineVersion(),
  ffmpeg: existsSync(join(app.isPackaged ? process.resourcesPath : app.getAppPath(), 'bin', process.platform==='win32'?'ffmpeg.exe':'ffmpeg')),
  binHint: app.isPackaged ? join(process.resourcesPath,'bin') : join(app.getAppPath(),'bin'),
}))

ipcMain.handle('analyze', async (_e, rawUrl: string, mode: 'QUICK'|'FULL' = 'FULL') => {
  const url = validateHttpsUrl(rawUrl)
  // quick direct check: if HEAD says file, return direct without yt-dlp
  if (mode === 'QUICK') {
    try {
      const h = await fetch(url, { method: 'HEAD', redirect: 'follow' } as any)
      const ct = h.headers.get('content-type') ?? ''
      const clen = h.headers.get('content-length')
      const disp = h.headers.get('content-disposition') ?? ''
      const isFile = ct && !ct.includes('text/html') && !disp.includes('html')
      if (h.ok && isFile) {
        const cdName = disp.match(/filename\*?=(?:UTF-8''|")?([^";\n]+)/i)?.[1]
        const name = cdName ? decodeURIComponent(cdName.replace(/"/g,'')) : (new URL(url).pathname.split('/').pop() || 'download')
        return { kind: 'direct', sourceUrl: url, title: sanitizeFileName(name), fileName: sanitizeFileName(name), mimeType: ct.split(';')[0].trim() || null, sizeBytes: clen ? parseInt(clen,10) : null } satisfies SourceAnalysis
      }
    } catch { /* fall through to yt-dlp */}
  }
  return ytAnalyze(url, mode)
})

ipcMain.handle('jobs:list', () => jobStore.all())
ipcMain.handle('jobs:queue', async (_e, payload: { analysis: SourceAnalysis; format: DownloadFormat; selectedIds?: string[] }) => {
  const { analysis, format, selectedIds } = payload
  const now = Date.now()
  const jobs: DownloadJob[] = []
  const mk = (over: Partial<DownloadJob> & { sourceUrl: string; title: string; thumbnailUrl?: string | null; sourceKind: DownloadJob['sourceKind'] }): DownloadJob => ({
    id: `${Date.now()}-${Math.random().toString(36).slice(2,8)}`,
    format, progress: 0, bytesDownloaded: null, totalBytes: null, speedBytesPerSecond: null, etaSeconds: null,
    outputPath: null, outputUri: null, fileName: null, mimeType: null, errorMessage: null,
    status: 'QUEUED', createdAt: now, updatedAt: now,
    thumbnailUrl: null, ...over,
  })
  if (analysis.kind === 'direct') {
    jobs.push(mk({ sourceUrl: analysis.sourceUrl, sourceKind: 'DIRECT_FILE', title: analysis.title, thumbnailUrl: null, format: 'ORIGINAL' as DownloadFormat }))
  } else if (analysis.kind === 'media') {
    jobs.push(mk({ sourceUrl: analysis.sourceUrl, sourceKind: 'MEDIA', title: analysis.title, thumbnailUrl: analysis.thumbnailUrl }))
  } else if (analysis.kind === 'playlist') {
    const ids = new Set(selectedIds ?? [])
    const entries = analysis.entries.filter(e=> ids.has(e.id))
    if (!entries.length) throw new Error('Select at least one playlist item.')
    if (entries.length > 25) throw new Error('Select no more than 25 playlist items.')
    for (const e of entries) jobs.push(mk({ sourceUrl: e.url, sourceKind: 'MEDIA', title: e.title, thumbnailUrl: e.thumbnailUrl }))
  }
  jobStore.insert(jobs)
  sendJobs()
  wakeQueue()
  return jobs.map(j=> j.id)
})

ipcMain.handle('jobs:cancel', async (_e, id: string) => {
  const j = jobStore.get(id)
  if (!j) return false
  if (j.status === 'QUEUED') { jobStore.cancelIfQueued(id); sendJobs(); return true }
  cancelledIds.add(id)
  if (currentJobId === id) { /* queue loop will abort */ }
  else { jobStore.cancelActive(id); sendJobs() }
  return true
})
ipcMain.handle('jobs:retry', async (_e, id: string) => {
  const j = jobStore.get(id)
  if (!j) throw new Error('Job not found')
  jobStore.transition(id, 'QUEUED', null, true)
  sendJobs()
  wakeQueue()
  return true
})
ipcMain.handle('jobs:clearFinished', async (_e, ids?: string[]) => {
  jobStore.clearFinished(ids ? new Set(ids) : undefined)
  sendJobs()
  return true
})
ipcMain.handle('jobs:remove', async (_e, id: string) => {
  const j = jobStore.get(id)
  if (j && j.outputPath) { try { rmSync(j.outputPath, { force: true }) } catch {} }
  // clear staging too
  try { rmSync(join(stagingRoot(), id), { recursive: true, force: true }) } catch {}
  jobStore.remove(id); sendJobs(); return true
})
ipcMain.handle('jobs:reveal', async (_e, id: string) => {
  const j = jobStore.get(id)
  if (!j?.outputPath) throw new Error('File not ready yet.')
  if (!existsSync(j.outputPath)) throw new Error('File not found on disk.')
  shell.showItemInFolder(j.outputPath)
  return true
})
ipcMain.handle('jobs:open', async (_e, id: string) => {
  const j = jobStore.get(id)
  if (!j?.outputPath) throw new Error('File not ready yet.')
  const r = await shell.openPath(j.outputPath)
  if (r) throw new Error(r)
  return true
})

ipcMain.handle('onboarding:complete', () => {
  prefsStore.set('rightsAcknowledged', true)
  prefsStore.set('onboardingCompleted', true)
  prefsStore.set('onboardingVersion', ONBOARDING_VERSION)
  return getPrefs()
})

ipcMain.handle('queue:wake', () => { wakeQueue(); return true })

// ---- Queue runner ----

async function publishStaged(jobId: string, stagedPath: string, fileName: string): Promise<string> {
  const outDir = ensureDownloadDir()
  mkdirSync(outDir, { recursive: true })
  let target = join(outDir, sanitizeFileName(fileName))
  // avoid overwrite: suffix (1), (2), ...
  if (existsSync(target)) {
    const dot = target.lastIndexOf('.')
    const base = dot >= 0 ? target.slice(0, dot) : target
    const ext = dot >= 0 ? target.slice(dot) : ''
    let n = 1
    while (existsSync(target)) { target = `${base} (${n})${ext}`; n++; if (n>999) break }
  }
  // try rename (same volume) else copy
  try { renameSync(stagedPath, target) } catch { await cp(stagedPath, target); try { rmSync(stagedPath, { force: true }) } catch {} }
  // clean staging dir
  try { rmSync(join(stagingRoot(), jobId), { recursive: true, force: true }) } catch {}
  return target
}

function mimeFor(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  const map: Record<string,string> = { mp4:'video/mp4', m4a:'audio/mp4', mp3:'audio/mpeg', webm:'video/webm', mkv:'video/x-matroska', mov:'video/quicktime', jpg:'image/jpeg', png:'image/png' }
  return map[ext] ?? 'application/octet-stream'
}

async function wakeQueue() {
  if (queueRunning) return
  queueRunning = true
  try {
    while (true) {
      const job = jobStore.claimNextQueued()
      if (!job) break
      currentJobId = job.id
      sendJobs()
      const stagingDir = join(stagingRoot(), job.id)
      mkdirSync(stagingDir, { recursive: true })
      try {
        jobStore.update(job.id, { status: 'RUNNING' })
        sendJobs()
        let staged: { filePath: string; fileName: string }
        const onProgress = (p: import('./models.js').TransferProgress) => {
          if (cancelledIds.has(job.id)) return
          jobStore.progress(job.id, { percent: p.percent, bytesDownloaded: p.bytesDownloaded, totalBytes: p.totalBytes, speedBytesPerSecond: p.speedBytesPerSecond, etaSeconds: p.etaSeconds })
          // throttle UI push: jobStore already notifies, but batch via sendJobs debounce
          if (p.percent % 5 === 0 || p.percent >= 100) sendJobs()
        }
        const isCancelled = () => cancelledIds.has(job.id)
        if (job.sourceKind === 'DIRECT_FILE') {
          staged = await downloadDirect(job, stagingDir, onProgress, isCancelled)
        } else {
          staged = await downloadMedia(job, stagingDir, onProgress, isCancelled)
        }
        if (isCancelled()) throw new Error('Download cancelled')
        jobStore.update(job.id, { status: 'FINALIZING', progress: 99 })
        sendJobs()
        const target = await publishStaged(job.id, staged.filePath, staged.fileName)
        const bytes = (()=>{ try { return statSync(target).size } catch { return 0 } })()
        const ok = jobStore.complete(job.id, target, `file:///${target.replace(/\\/g,'/')}`, sanitizeFileName(staged.fileName), mimeFor(staged.fileName), bytes)
        if (!ok) { try { rmSync(target, { force: true }) } catch {} ; throw new Error('Download cancelled') }
        cancelledIds.delete(job.id)
        sendJobs()
      } catch (e: any) {
        const msg = e?.message ?? String(e)
        if (cancelledIds.has(job.id) || /cancelled/i.test(msg)) {
          try { rmSync(join(stagingRoot(), job.id), { recursive: true, force: true }) } catch {}
          jobStore.transition(job.id, 'CANCELLED')
          cancelledIds.delete(job.id)
        } else {
          jobStore.transition(job.id, 'FAILED', friendlyFailure(e))
        }
        sendJobs()
      } finally {
        currentJobId = null
      }
    }
  } finally {
    queueRunning = false
  }
}
