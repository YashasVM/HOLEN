import React, { useEffect, useMemo, useState } from 'react'
import type { SourceAnalysis, DownloadFormat } from '../../main/models'

type Job = any

const FORMATS: { id: DownloadFormat; label: string; desc: string }[] = [
  { id: 'BEST_MP4', label: 'Best MP4', desc: 'Highest quality mp4' },
  { id: 'MP4_1080', label: '1080p', desc: '≤1080p mp4' },
  { id: 'MP4_720', label: '720p', desc: '≤720p mp4' },
  { id: 'AUDIO_M4A', label: 'M4A', desc: 'Audio only' },
  { id: 'AUDIO_MP3', label: 'MP3', desc: 'Audio mp3 320k' },
  { id: 'ORIGINAL', label: 'Original', desc: 'Direct file' },
]

function fmtBytes(n: number | null | undefined): string {
  if (n == null) return '—'
  const u = ['B','KB','MB','GB']; let a=n, i=0; while(a>=1000&&i<u.length-1){a/=1000;i++}
  return i===0? `${Math.round(a)} ${u[i]}` : `${a.toFixed(1)} ${u[i]}`
}
function fmtDur(s: number | null | undefined): string {
  if (s == null) return '—'
  return s>=3600? `${Math.floor(s/3600)}:${String(Math.floor(s%3600/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}` : `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`
}

export default function App() {
  const [tab, setTab] = useState<'download'|'queue'|'settings'>('download')
  const [prefs, setPrefs] = useState<any>(null)
  const [url, setUrl] = useState('')
  const [analysis, setAnalysis] = useState<SourceAnalysis | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [format, setFormat] = useState<DownloadFormat>('BEST_MP4')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [jobs, setJobs] = useState<Job[]>([])
  const [toast, setToast] = useState<string | null>(null)
  const [engine, setEngine] = useState<any>(null)
  const [cookiesText, setCookiesText] = useState('')
  const [appInfo, setAppInfo] = useState<any>(null)

  const showToast = (m: string) => { setToast(m); setTimeout(()=> setToast(null), 2800) }

  useEffect(() => {
    window.holen.prefsGet().then(setPrefs)
    window.holen.appInfo().then(setAppInfo)
    window.holen.engineStatus().then(setEngine)
    window.holen.jobsList().then(setJobs)
    const off = window.holen.onJobsUpdate(setJobs)
    const off2 = window.holen.onExternalUrl((u) => { setUrl(u); setTab('download') })
    return () => { off(); off2() }
  }, [])

  const onAnalyze = async (mode: 'QUICK'|'FULL' = 'FULL') => {
    if (!url.trim()) { setError('Paste a public HTTPS link first.'); return }
    setBusy(true); setError(null); setAnalysis(null)
    try {
      const a = await window.holen.analyze(url.trim(), mode)
      setAnalysis(a)
      if ((a as any).kind === 'direct') setFormat('ORIGINAL')
      else setFormat('BEST_MP4')
      if ((a as any).kind === 'playlist') setSelected(new Set((a as any).entries.slice(0, 25).map((e: any)=> e.id)))
    } catch (e: any) { setError(e?.message ?? String(e)) }
    finally { setBusy(false) }
  }

  const onQueue = async () => {
    if (!analysis) return
    if (!prefs?.downloadDir) { setError('Choose a download folder in Settings first.'); setTab('settings'); return }
    try {
      setBusy(true)
      const ids = await window.holen.jobsQueue({ analysis: analysis as any, format, selectedIds: [...selected] })
      showToast(`Queued ${ids.length} download${ids.length>1?'s':''}`)
      setAnalysis(null); setUrl(''); setSelected(new Set())
      setTab('queue')
    } catch (e: any) { setError(e?.message ?? String(e)); showToast(e?.message ?? 'Queue failed') }
    finally { setBusy(false) }
  }

  const needsOnboarding = useMemo(() => {
    if (!prefs) return false
    return !prefs.onboardingCompleted || (prefs.onboardingVersion ?? 0) < 5
  }, [prefs])

  if (prefs && needsOnboarding) {
    return <Onboarding prefs={prefs} onDone={async()=>{ const p=await window.holen.onboardingComplete(); setPrefs(p) }} onChooseFolder={async()=>{ const d=await window.holen.chooseFolder(); if(d) setPrefs(await window.holen.prefsGet()) }} />
  }

  return (
    <>
      <div className="titlebar">
        <div className="brand">HOLEN</div>
        <div style={{ display:'flex', gap:8, alignItems:'center', fontSize:12, color:'var(--muted)' }}>
          {engine && <span className="mono" title={engine.binHint}>{engine.version}</span>}
          {appInfo && <span className="pill">v{appInfo.version}</span>}
        </div>
      </div>
      <div className="app">
        <div className="sidebar">
          <div className="nav">
            <div className={`nav-item ${tab==='download'?'active':''}`} onClick={()=> setTab('download')}>⬇ Download</div>
            <div className={`nav-item ${tab==='queue'?'active':''}`} onClick={()=> setTab('queue')}>≡ Queue {jobs.filter((j:Job)=> j.status==='QUEUED'||j.status==='RUNNING'||j.status==='FINALIZING').length ? `· ${jobs.filter((j:Job)=> j.status==='QUEUED'||j.status==='RUNNING'||j.status==='FINALIZING').length}`:''}</div>
            <div className={`nav-item ${tab==='settings'?'active':''}`} onClick={()=> setTab('settings')}>⚙ Settings</div>
          </div>
          <div className="sidebar-footer">
            <div className="pill">⚡ Local · no server · fast</div>
            <div style={{ fontSize: 11, color: 'var(--muted-2)' }}>Made by <a href="#" onClick={(e)=>{e.preventDefault(); window.holen.openExternal('https://github.com/YashasVM')}} style={{ color:'inherit' }}>@yashas.vm</a></div>
          </div>
        </div>
        <div className="main">
          <div className="main-scroll">
            {tab==='download' && (
              <>
                <div className="card">
                  <div className="card-title">Paste a link</div>
                  <div className="card-desc">Share a link from any app or paste an HTTPS URL. Direct files are instant — media uses your local yt-dlp + ffmpeg (no cloud).</div>
                  <div className="input-row">
                    <input className="input" placeholder="https://..." value={url} onChange={e=> setUrl(e.target.value)} onKeyDown={e=> e.key==='Enter' && onAnalyze()} />
                    <button className="btn btn-primary" disabled={busy} onClick={()=> onAnalyze()}>{busy ? 'Reading…' : 'Fetch'}</button>
                    <button className="btn btn-ghost" disabled={busy || !url.trim()} onClick={()=> onAnalyze('QUICK')} title="Quick check">Quick</button>
                  </div>
                  {error && <div style={{ marginTop:10, color:'var(--danger)', fontSize:13 }}>{error}</div>}
                  <div style={{ marginTop:10, display:'flex', gap:8, flexWrap:'wrap' }}>
                    <span className="kbd">Share → HOLEN</span>
                    <span className="kbd">HTTPS only</span>
                    <span className="kbd">Uses file system, not SAF</span>
                  </div>
                </div>

                {analysis && (
                  <div className="card">
                    {analysis.kind === 'direct' && (
                      <>
                        <div className="spread"><div><div className="card-title" style={{ margin:0 }}>{analysis.title}</div><div className="muted" style={{ fontSize:12 }}>{analysis.mimeType ?? 'file'} · {fmtBytes(analysis.sizeBytes)}</div></div><span className="badge">Direct file</span></div>
                        <div style={{ marginTop:12 }} className="row"><button className="btn btn-primary" onClick={onQueue} disabled={busy}>Download</button><button className="btn" onClick={()=> setAnalysis(null)}>Clear</button></div>
                      </>
                    )}
                    {analysis.kind === 'media' && (
                      <>
                        <div style={{ display:'flex', gap:14 }}>
                          {analysis.thumbnailUrl && <img className="thumb" src={analysis.thumbnailUrl} alt="" />}
                          <div style={{ flex:1, minWidth:0 }}>
                            <div className="card-title" style={{ margin:0, display:'-webkit-box', WebkitLineClamp:2, WebkitBoxOrient:'vertical', overflow:'hidden' }}>{analysis.title}</div>
                            <div className="muted" style={{ fontSize:12 }}>{analysis.uploader ?? ''} {analysis.durationSeconds ? `· ${fmtDur(analysis.durationSeconds)}` : ''}</div>
                          </div>
                          <span className="badge">Media</span>
                        </div>
                        <div style={{ marginTop:14 }}>
                          <div className="muted" style={{ fontSize:12, marginBottom:8 }}>Format</div>
                          <div className="format-grid">
                            {FORMATS.filter(f=> f.id!=='ORIGINAL').map(f=> (
                              <div key={f.id} className={`format ${format===f.id?'active':''}`} onClick={()=> setFormat(f.id)}>
                                <div className="name">{f.label}</div><div className="meta">{fmtBytes((analysis.estimatedSizes as any)[f.id])} · {f.desc}</div>
                              </div>
                            ))}
                          </div>
                        </div>
                        <div className="row" style={{ marginTop:14 }}><button className="btn btn-primary" onClick={onQueue} disabled={busy}>Add to queue</button><button className="btn" onClick={()=> setAnalysis(null)}>Clear</button></div>
                      </>
                    )}
                    {analysis.kind === 'playlist' && (
                      <>
                        <div className="spread"><div><div className="card-title" style={{ margin:0 }}>{analysis.title}</div><div className="muted" style={{ fontSize:12 }}>{analysis.uploader ?? ''} · {analysis.entries.length} items (select up to 25)</div></div><span className="badge">Playlist</span></div>
                        <div style={{ marginTop:12 }}>
                          <div className="muted" style={{ fontSize:12, marginBottom:8 }}>Format</div>
                          <div className="format-grid">
                            {FORMATS.filter(f=> f.id!=='ORIGINAL').map(f=> (
                              <div key={f.id} className={`format ${format===f.id?'active':''}`} onClick={()=> setFormat(f.id)}>
                                <div className="name">{f.label}</div><div className="meta">{f.desc}</div>
                              </div>
                            ))}
                          </div>
                        </div>
                        <div className="row" style={{ marginTop:10 }}>
                          <button className="btn btn-sm" onClick={()=> setSelected(new Set(analysis.entries.map(e=> e.id)))}>Select all</button>
                          <button className="btn btn-sm" onClick={()=> setSelected(new Set())}>Clear</button>
                          <span className="muted" style={{ fontSize:12 }}>{selected.size} selected</span>
                        </div>
                        <div style={{ marginTop:10, maxHeight: 320, overflow: 'auto', display:'flex', flexDirection:'column', gap:8 }}>
                          {analysis.entries.map(e=> (
                            <label key={e.id} className="check" style={{ cursor:'pointer' }}>
                              <input type="checkbox" checked={selected.has(e.id)} onChange={()=> setSelected(s=> { const n=new Set(s); if(n.has(e.id)) n.delete(e.id); else n.add(e.id); return n })} />
                              <img src={e.thumbnailUrl ?? ''} alt="" style={{ width: 72, height: 42, borderRadius:6, objectFit:'cover', background:'#111' }} />
                              <div style={{ flex:1, minWidth:0 }}><div style={{ fontSize:13, fontWeight:600, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{e.title}</div><div className="muted" style={{ fontSize:11 }}>{fmtDur(e.durationSeconds)}</div></div>
                            </label>
                          ))}
                        </div>
                        <div className="row" style={{ marginTop:14 }}><button className="btn btn-primary" disabled={busy || selected.size===0} onClick={onQueue}>Queue {selected.size || ''}</button><button className="btn" onClick={()=> setAnalysis(null)}>Clear</button></div>
                      </>
                    )}
                  </div>
                )}

                {!analysis && (
                  <div className="card" style={{ marginTop:14 }}>
                    <div className="card-title">How it’s fast</div>
                    <div className="card-desc">Windows uses your disk + network directly (no SAF, no cloud). yt-dlp/ffmpeg run locally with `--concurrent-fragments 2` and `--continue` resume. Queue is sequential, downloads are written to a staging folder then moved — crash-safe.</div>
                    <div className="row">
                      <button className="btn btn-sm" onClick={()=> window.holen.openExternal('https://github.com/YashasVM/HOLEN')}>GitHub</button>
                      <button className="btn btn-sm" onClick={()=> setTab('settings')}>Open settings</button>
                    </div>
                  </div>
                )}
              </>
            )}

            {tab==='queue' && (
              <>
                <div className="spread" style={{ marginBottom:12 }}>
                  <h2 style={{ margin:0, fontSize:18 }}>Queue</h2>
                  <div className="row">
                    <button className="btn btn-sm" onClick={()=> window.holen.queueWake()}>Resume</button>
                    <button className="btn btn-sm" onClick={async()=>{ await window.holen.jobsClearFinished(); showToast('Cleared finished') }}>Clear finished</button>
                  </div>
                </div>
                {jobs.length===0 ? (
                  <div className="empty"><h3>Nothing queued</h3><div>Fetch a link on the Download tab and add it to the queue.</div></div>
                ) : jobs.map((j: Job)=> <JobRow key={j.id} job={j} onToast={showToast} />)}
              </>
            )}

            {tab==='settings' && (
              <>
                <div className="card">
                  <div className="card-title">Download folder</div>
                  <div className="card-desc">Windows file system — pick any folder. Fastest when on an SSD. HOLEN stages then moves files (atomic).</div>
                  <div className="row">
                    <div className="mono" style={{ flex:1, background:'#0a0a0a', border:'1px solid var(--border)', borderRadius:10, padding:'10px 12px', fontSize:13, overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}>{prefs?.downloadDir ?? '—'}</div>
                    <button className="btn" onClick={async()=>{ const d=await window.holen.chooseFolder(); if(d) setPrefs(await window.holen.prefsGet()) }}>Choose</button>
                    <button className="btn" onClick={()=> window.holen.openFolder()}>Open</button>
                  </div>
                  <div className="row" style={{ marginTop:10 }}>
                    <label className="check" style={{ flex:1 }}><input type="checkbox" checked={!!prefs?.filenameSuffixEnabled} onChange={async(e)=>{ await window.holen.prefsSet({ filenameSuffixEnabled: e.target.checked }); setPrefs(await window.holen.prefsGet()) }} /> <span>Append <span className="mono">[HOLEN]</span> suffix to filenames</span></label>
                  </div>
                </div>

                <div className="card">
                  <div className="card-title">Engine</div>
                  <div className="card-desc">Local yt-dlp + ffmpeg. Put <span className="mono">yt-dlp.exe</span> + <span className="mono">ffmpeg.exe</span> in <span className="mono">{engine?.binHint ?? 'bin/'}</span> (extraResources/bin in packaged app). Or install yt-dlp to PATH.</div>
                  <div className="row" style={{ marginTop:8 }}>
                    <span className={`badge ${engine?.available?'badge-done':''}`} style={{ fontSize:12 }}>{engine?.available ? '● yt-dlp found' : '○ yt-dlp not found'}</span>
                    <span className="badge" style={{ fontSize:12 }}>{engine?.ffmpeg ? 'ffmpeg ✓' : 'ffmpeg —'}</span>
                    <span className="mono muted" style={{ fontSize:11 }}>{engine?.version ?? ''}</span>
                  </div>
                  <div className="muted" style={{ fontSize:11, marginTop:8 }}>Tip: for speed, download 1080p not “Best” when you don’t need 4K. HOLEN uses concurrent fragments + resume.</div>
                </div>

                <div className="card">
                  <div className="card-title">Cookies</div>
                  <div className="card-desc">Optional Netscape <span className="mono">cookies.txt</span> for age-gated / member content you can access. Stored locally, never uploaded.</div>
                  <textarea className="input" style={{ minHeight: 120, resize:'vertical', fontFamily:'ui-monospace, monospace', fontSize:12 }} placeholder="# Netscape HTTP Cookie File&#10;# https://..." value={cookiesText} onChange={e=> setCookiesText(e.target.value)} />
                  <div className="row" style={{ marginTop:10 }}>
                    <button className="btn btn-primary" onClick={async()=>{ try{ await window.holen.cookiesSave(cookiesText); showToast('Cookies saved'); setCookiesText('') } catch(e:any){ showToast(e.message) } }}>Save cookies</button>
                    <button className="btn" onClick={async()=>{ await window.holen.cookiesClear(); showToast('Cookies cleared') }}>Clear</button>
                    <button className="btn btn-ghost" onClick={async()=>{ const c=await window.holen.cookiesGet(); showToast(c.configured? 'Cookies are configured' : 'No cookies set') }}>Check</button>
                  </div>
                </div>

                <div className="card">
                  <div className="card-title">About</div>
                  <div className="muted" style={{ fontSize:13, lineHeight:1.6 }}>
                    HOLEN for Windows — native, fast, private. No account, no telemetry, no hosted server. Downloads stay on your device.<br/>
                    <span className="mono">HOLEN v{appInfo?.version ?? '—'}</span> · Electron {navigator.userAgent.includes('Electron') ? '✓' : ''} · <a href="#" onClick={(e)=>{e.preventDefault(); window.holen.openExternal('https://github.com/YashasVM/HOLEN')}} style={{ color:'var(--muted)' }}>github.com/YashasVM/HOLEN</a>
                  </div>
                  <div className="muted" style={{ fontSize:12, marginTop:8 }}>Responsible use: only download files you own or are authorized to save. No DRM bypass.</div>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
      {toast && <div className="toast">{toast}</div>}
    </>
  )
}

function JobRow({ job, onToast }: { job: Job; onToast: (m:string)=>void }) {
  const pct = job.progress ?? 0
  const isRunning = job.status==='RUNNING' || job.status==='FINALIZING'
  const isDone = job.status==='COMPLETED'
  const isFailed = job.status==='FAILED'
  const speed = job.speedBytesPerSecond ? `${fmtBytes(job.speedBytesPerSecond)}/s` : null
  const eta = job.etaSeconds != null ? fmtDur(job.etaSeconds) : null
  return (
    <div className="job">
      {job.thumbnailUrl ? <img className="thumb" src={job.thumbnailUrl} alt="" /> : <div className="thumb" style={{ display:'grid', placeItems:'center', color:'var(--muted)', fontSize:18 }}>⬇</div>}
      <div style={{ flex:1, minWidth:0 }}>
        <div className="job-title">{job.title}</div>
        <div className="muted" style={{ fontSize:11, marginTop:4 }}>
          {job.sourceKind === 'DIRECT_FILE' ? 'Direct' : job.format} · <span className={`badge ${isRunning?'badge-running':''} ${isDone?'badge-done':''} ${isFailed?'badge-failed':''}`} style={{ fontSize:10, padding:'2px 6px' }}>{job.status}</span>
          {job.fileName ? ` · ${job.fileName}` : ''} {isFailed && job.errorMessage ? ` — ${job.errorMessage}` : ''}
        </div>
        <div className="progress"><i style={{ width: `${Math.min(100, Math.max(0, pct))}%`, opacity: isDone?1: isFailed?0.4:1 }} /></div>
        <div className="muted" style={{ fontSize:11, marginTop:6, display:'flex', gap:10, flexWrap:'wrap' }}>
          <span>{pct}%</span>
          {job.bytesDownloaded != null && job.totalBytes != null ? <span>{fmtBytes(job.bytesDownloaded)} / {fmtBytes(job.totalBytes)}</span> : null}
          {speed ? <span>{speed}</span> : null}
          {eta && isRunning ? <span>{eta} left</span> : null}
        </div>
        <div className="row" style={{ marginTop:10 }}>
          {isRunning ? <button className="btn btn-sm" onClick={()=> window.holen.jobsCancel(job.id)}>Cancel</button> : null}
          {isFailed ? <button className="btn btn-sm" onClick={()=> window.holen.jobsRetry(job.id)}>Retry</button> : null}
          {isDone && job.outputPath ? <><button className="btn btn-sm btn-primary" onClick={async()=>{ try{ await window.holen.jobsOpen(job.id)} catch(e:any){ onToast(e.message) }}}>Open</button><button className="btn btn-sm" onClick={()=> window.holen.jobsReveal(job.id)}>Show in folder</button></> : null}
          {(job.status==='COMPLETED'||job.status==='FAILED'||job.status==='CANCELLED') ? <button className="btn btn-sm btn-ghost" onClick={async()=>{ if(confirm(`Remove "${job.title}"? ${isDone ? 'This deletes the file too.' : ''}`)) await window.holen.jobsRemove(job.id) }}>Remove</button> : null}
        </div>
      </div>
    </div>
  )
}

function Onboarding({ prefs, onDone, onChooseFolder }: { prefs: any; onDone: () => void; onChooseFolder: () => void }) {
  const [ack, setAck] = useState(false)
  const [folder, setFolder] = useState<string>(prefs.downloadDir ?? '')
  useEffect(()=>{ setFolder(prefs.downloadDir)},[prefs.downloadDir])
  return (
    <div className="onboarding" style={{ padding: 24 }}>
      <h1>HOLEN for Windows</h1>
      <p>Fast, local, private — no server, no account. Your downloads stay on your device.</p>
      <div className="steps"><div className="step on" /><div className="step on" /><div className="step on" /></div>
      <div className="card">
        <div className="card-title">1 · Choose download folder</div>
        <div className="card-desc">Pick any folder (SSD is fastest). You can change it later in Settings.</div>
        <div className="row"><div className="mono" style={{ flex:1, background:'#0a0a0a', border:'1px solid var(--border)', borderRadius:10, padding:'10px 12px', fontSize:12, overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}>{folder || '—'}</div><button className="btn" onClick={async()=>{ await onChooseFolder(); }}>Choose</button></div>
      </div>
      <div className="card">
        <div className="card-title">2 · Responsible use</div>
        <div className="card-desc">Only download files and media you own or are authorized to save. HOLEN does not bypass DRM, logins, or platform terms.</div>
        <label className="check"><input type="checkbox" checked={ack} onChange={e=> setAck(e.target.checked)} /><span>I understand — I’ll only download content I’m allowed to save.</span></label>
      </div>
      <div className="row" style={{ marginTop:16 }}>
        <button className="btn btn-primary" style={{ flex:1, justifyContent:'center', padding:'12px' }} disabled={!ack || !folder} onClick={onDone}>Continue to HOLEN</button>
      </div>
      <div className="muted" style={{ fontSize:11, marginTop:12, textAlign:'center' }}>No telemetry · No cloud · Made by @yashas.vm</div>
    </div>
  )
}
