import { contextBridge, ipcRenderer } from 'electron'

export type HolenAPI = {
  appInfo: () => Promise<{ version: string; isPackaged: boolean; platform: string }>
  prefsGet: () => Promise<any>
  prefsSet: (patch: Record<string, unknown>) => Promise<any>
  chooseFolder: () => Promise<string | null>
  openFolder: (p?: string) => Promise<boolean>
  openPath: (p: string) => Promise<string>
  showItem: (p: string) => Promise<void>
  openExternal: (url: string) => Promise<void>
  cookiesGet: () => Promise<{ configured: boolean; preview: string }>
  cookiesSave: (text: string) => Promise<{ ok: boolean }>
  cookiesClear: () => Promise<{ ok: boolean }>
  engineStatus: () => Promise<{ available: boolean; version: string; ffmpeg: boolean; binHint: string }>
  analyze: (url: string, mode?: 'QUICK'|'FULL') => Promise<any>
  jobsList: () => Promise<any[]>
  jobsQueue: (payload: { analysis: any; format: string; selectedIds?: string[] }) => Promise<string[]>
  jobsCancel: (id: string) => Promise<boolean>
  jobsRetry: (id: string) => Promise<boolean>
  jobsClearFinished: (ids?: string[]) => Promise<boolean>
  jobsRemove: (id: string) => Promise<boolean>
  jobsReveal: (id: string) => Promise<boolean>
  jobsOpen: (id: string) => Promise<boolean>
  onboardingComplete: () => Promise<any>
  queueWake: () => Promise<boolean>
  onJobsUpdate: (cb: (jobs: any[]) => void) => () => void
  onExternalUrl: (cb: (url: string) => void) => () => void
}

const api: HolenAPI = {
  appInfo: () => ipcRenderer.invoke('app:info'),
  prefsGet: () => ipcRenderer.invoke('prefs:get'),
  prefsSet: (p) => ipcRenderer.invoke('prefs:set', p),
  chooseFolder: () => ipcRenderer.invoke('dialog:chooseFolder'),
  openFolder: (p) => ipcRenderer.invoke('dialog:openFolder', p),
  openPath: (p) => ipcRenderer.invoke('shell:openPath', p),
  showItem: (p) => ipcRenderer.invoke('shell:showItem', p),
  openExternal: (url) => ipcRenderer.invoke('shell:openExternal', url),
  cookiesGet: () => ipcRenderer.invoke('cookies:get'),
  cookiesSave: (t) => ipcRenderer.invoke('cookies:save', t),
  cookiesClear: () => ipcRenderer.invoke('cookies:clear'),
  engineStatus: () => ipcRenderer.invoke('engine:status'),
  analyze: (url, mode) => ipcRenderer.invoke('analyze', url, mode),
  jobsList: () => ipcRenderer.invoke('jobs:list'),
  jobsQueue: (payload) => ipcRenderer.invoke('jobs:queue', payload),
  jobsCancel: (id) => ipcRenderer.invoke('jobs:cancel', id),
  jobsRetry: (id) => ipcRenderer.invoke('jobs:retry', id),
  jobsClearFinished: (ids) => ipcRenderer.invoke('jobs:clearFinished', ids),
  jobsRemove: (id) => ipcRenderer.invoke('jobs:remove', id),
  jobsReveal: (id) => ipcRenderer.invoke('jobs:reveal', id),
  jobsOpen: (id) => ipcRenderer.invoke('jobs:open', id),
  onboardingComplete: () => ipcRenderer.invoke('onboarding:complete'),
  queueWake: () => ipcRenderer.invoke('queue:wake'),
  onJobsUpdate: (cb) => {
    const h = (_: unknown, jobs: any[]) => cb(jobs)
    ipcRenderer.on('jobs:update', h)
    return () => ipcRenderer.removeListener('jobs:update', h)
  },
  onExternalUrl: (cb) => {
    const h = (_: unknown, url: string) => cb(url)
    ipcRenderer.on('external-url', h)
    return () => ipcRenderer.removeListener('external-url', h)
  },
}

contextBridge.exposeInMainWorld('holen', api)

declare global {
  interface Window { holen: HolenAPI }
}
