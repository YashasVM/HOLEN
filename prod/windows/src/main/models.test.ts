import { describe, it, expect } from 'vitest'
import { sanitizeFileName, validateHttpsUrl, parseMachineTransferLine, parseTransferLine, formatBytes, formatDuration, canTransition } from './models'

describe('sanitizeFileName', () => {
  it('strips illegal chars', () => expect(sanitizeFileName('a<b>.mp4')).toBe('a_b_.mp4'))
  it('trims and caps 180', () => expect(sanitizeFileName('  hello world  ')).toBe('hello world'))
  it('falls back on blank', () => expect(sanitizeFileName('   ')).toBe('download'))
  it('uses fallback param', () => expect(sanitizeFileName('', 'fallback')).toBe('fallback'))
  it('collapses whitespace', () => expect(sanitizeFileName('a   b c')).toBe('a b c'))
  it('drops dot-only', () => expect(sanitizeFileName('...')).toBe('download'))
})

describe('validateHttpsUrl', () => {
  it('accepts https', () => expect(validateHttpsUrl('https://example.com/a')).toMatch(/^https:\/\//))
  it('rejects http', () => expect(() => validateHttpsUrl('http://example.com')).toThrow())
  it('rejects credentials', () => expect(() => validateHttpsUrl('https://user:pass@example.com')).toThrow())
  it('rejects overflow', () => expect(() => validateHttpsUrl('https://example.com/' + 'a'.repeat(5000))).toThrow())
  it('rejects blank', () => expect(() => validateHttpsUrl('')).toThrow())
})

describe('parseMachineTransferLine', () => {
  it('parses percent and total', () => {
    const p = parseMachineTransferLine('HOLEN_PROGRESS  12.3%|123|1000|1000|500|30')
    expect(p?.percent).toBe(12)
    expect(p?.totalBytes).toBe(1000)
  })
  it('handles NA', () => {
    const p = parseMachineTransferLine('HOLEN_PROGRESS  50%|NA|NA|NA|NA|NA')
    expect(p?.bytesDownloaded).toBeNull()
    expect(p?.totalBytes).toBeNull()
  })
  it('rejects non-marker', () => expect(parseMachineTransferLine(' 50% | 123')).toBeNull())
  it('uses estimate when total is NA', () => {
    const p = parseMachineTransferLine('HOLEN_PROGRESS  10%|100|NA|999|NA|NA')
    expect(p?.totalBytes).toBe(999)
  })
})

describe('parseTransferLine', () => {
  it('falls back to human parsing', () => {
    const p = parseTransferLine('[download]  45.0% of 10.00MiB at 1.00MiB/s ETA 00:10')
    expect(p?.percent).toBe(45)
  })
})

describe('format helpers', () => {
  it('formatBytes', () => {
    expect(formatBytes(500)).toBe('500 B')
    expect(formatBytes(1500)).toBe('1.5 KB')
  })
  it('formatDuration', () => {
    expect(formatDuration(61)).toBe('1:01')
    expect(formatDuration(3661)).toBe('1:01:01')
  })
})

describe('canTransition', () => {
  it('queued -> running', () => expect(canTransition('QUEUED', 'RUNNING')).toBe(true))
  it('completed -> queued false', () => expect(canTransition('COMPLETED', 'QUEUED')).toBe(false))
  it('running -> finalizing', () => expect(canTransition('RUNNING', 'FINALIZING')).toBe(true))
})
