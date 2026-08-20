import { app } from 'electron'
import { join } from 'path'
import { mkdirSync, existsSync, readFileSync, writeFileSync, readdirSync, statSync, rmSync } from 'fs'
import Store from 'electron-store'

export type Prefs = {
  downloadDir: string
  onboardingCompleted: boolean
  onboardingVersion: number
  rightsAcknowledged: boolean
  filenameSuffixEnabled: boolean
  engineVersion: string | null
  engineLastCheckAt: number
  engineLastSuccessfulUpdateAt: number
  appUpdateLastCheckAt: number
  appUpdateDismissedTag: string | null
  cookiesConfigured: boolean
}

const defaults: Prefs = {
  downloadDir: join(app.getPath('downloads'), 'HOLEN'),
  onboardingCompleted: false,
  onboardingVersion: 0,
  rightsAcknowledged: false,
  filenameSuffixEnabled: true,
  engineVersion: null,
  engineLastCheckAt: 0,
  engineLastSuccessfulUpdateAt: 0,
  appUpdateLastCheckAt: 0,
  appUpdateDismissedTag: null,
  cookiesConfigured: false,
}

export const prefsStore = new Store<Prefs>({
  name: 'holen-prefs',
  defaults,
  schema: {
    downloadDir: { type: 'string' },
    onboardingCompleted: { type: 'boolean' },
    onboardingVersion: { type: 'integer' },
    rightsAcknowledged: { type: 'boolean' },
    filenameSuffixEnabled: { type: 'boolean' },
    engineVersion: { type: ['string', 'null'] },
    engineLastCheckAt: { type: 'integer' },
    engineLastSuccessfulUpdateAt: { type: 'integer' },
    appUpdateLastCheckAt: { type: 'integer' },
    appUpdateDismissedTag: { type: ['string', 'null'] },
    cookiesConfigured: { type: 'boolean' },
  },
})

export const ONBOARDING_VERSION = 5

export function getPrefs(): Prefs {
  return {
    downloadDir: (prefsStore.get('downloadDir') as string) || defaults.downloadDir,
    onboardingCompleted: Boolean(prefsStore.get('onboardingCompleted')),
    onboardingVersion: Number(prefsStore.get('onboardingVersion') ?? 0),
    rightsAcknowledged: Boolean(prefsStore.get('rightsAcknowledged')),
    filenameSuffixEnabled: prefsStore.get('filenameSuffixEnabled') !== false,
    engineVersion: (prefsStore.get('engineVersion') as string | null) ?? null,
    engineLastCheckAt: Number(prefsStore.get('engineLastCheckAt') ?? 0),
    engineLastSuccessfulUpdateAt: Number(prefsStore.get('engineLastSuccessfulUpdateAt') ?? 0),
    appUpdateLastCheckAt: Number(prefsStore.get('appUpdateLastCheckAt') ?? 0),
    appUpdateDismissedTag: (prefsStore.get('appUpdateDismissedTag') as string | null) ?? null,
    cookiesConfigured: Boolean(prefsStore.get('cookiesConfigured')),
  }
}

export function dataDir(): string {
  const d = join(app.getPath('userData'), 'holen')
  mkdirSync(d, { recursive: true })
  return d
}
export function engineDir(): string {
  const d = join(dataDir(), 'engine')
  mkdirSync(d, { recursive: true })
  return d
}
export function stagingRoot(): string {
  const d = join(dataDir(), 'staging')
  mkdirSync(d, { recursive: true })
  return d
}
export function cookiesPath(): string { return join(dataDir(), 'cookies.txt') }
export function jobsDbPath(): string { return join(dataDir(), 'jobs.json') }

export function hasCookies(): boolean {
  try { return existsSync(cookiesPath()) && readFileSync(cookiesPath(), 'utf8').trim().length > 0 } catch { return false }
}
export function readCookies(): string { try { return readFileSync(cookiesPath(), 'utf8') } catch { return '' } }
export function saveCookies(text: string): void {
  const t = text.trim()
  if (!t) throw new Error('Paste Netscape cookies.txt content first.')
  if (t.length > 512 * 1024) throw new Error('Cookies file is too large (max 512 KB).')
  if (!t.includes('# Netscape HTTP Cookie File') && !t.includes('# HTTP Cookie File')) {
    // still allow but warn — yt-dlp is strict; store anyway
  }
  writeFileSync(cookiesPath(), t.endsWith('\n') ? t : t + '\n', 'utf8')
  prefsStore.set('cookiesConfigured', true)
}
export function clearCookies(): void { try { rmSync(cookiesPath(), { force: true }) } catch {} ; prefsStore.set('cookiesConfigured', false) }

export function ensureDownloadDir(): string {
  const dir = getPrefs().downloadDir
  mkdirSync(dir, { recursive: true })
  return dir
}

export function cleanOrphanStaging(knownIds: Set<string>): void {
  try {
    const root = stagingRoot()
    for (const name of readdirSync(root)) {
      if (!knownIds.has(name)) {
        const p = join(root, name)
        try { const s = statSync(p); if (s.isDirectory()) rmSync(p, { recursive: true, force: true }) } catch {}
      }
    }
  } catch {}
}
