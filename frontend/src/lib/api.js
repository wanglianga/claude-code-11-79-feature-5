// Minimal fetch wrapper with HTTP Basic auth held in sessionStorage.
// The demo logins are role-scoped; see README for accounts.

const STORAGE_KEY = 'ctrial.auth'

export const ACCOUNTS = [
  { username: 'global', password: 'demo123', label: 'Global analyst', role: 'GLOBAL_ANALYST', note: 'Cross-border deliveries, snapshots' },
  { username: 'china', password: 'demo123', label: 'China analyst', role: 'CN_ANALYST', note: 'Local plane, watermark ops' },
  { username: 'admin', password: 'demo123', label: 'Administrator', role: 'ADMIN', note: 'Full access, consent & event entry' },
  { username: 'auditor', password: 'demo123', label: 'Auditor', role: 'AUDITOR', note: 'Read-only audit, recovery, quarantine' },
  { username: 'sponsor', password: 'demo123', label: 'Sponsor', role: 'SPONSOR', note: 'Approvals, consent entry' }
]

export function getAuth() {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try { return JSON.parse(raw) } catch { return null }
}

export function setAuth(auth) {
  if (auth) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(auth))
  else sessionStorage.removeItem(STORAGE_KEY)
}

function basicHeader(auth) {
  const token = btoa(`${auth.username}:${auth.password}`)
  return { Authorization: `Basic ${token}`, 'Content-Type': 'application/json' }
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || body?.error || `HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

async function request(method, path, body) {
  const auth = getAuth()
  if (!auth) throw new ApiError(401, { error: 'not signed in' })
  const res = await fetch(`/api${path}`, {
    method,
    headers: basicHeader(auth),
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let parsed = null
  if (text) {
    try { parsed = JSON.parse(text) } catch { parsed = text }
  }
  if (!res.ok) throw new ApiError(res.status, typeof parsed === 'object' ? parsed : { message: text })
  return parsed
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body)
}

export async function login(username, password) {
  const token = btoa(`${username}:${password}`)
  const res = await fetch('/api/session', { headers: { Authorization: `Basic ${token}` } })
  if (!res.ok) throw new ApiError(res.status, { error: 'invalid credentials' })
  const session = await res.json()
  const auth = { username, password, roles: session.roles || [] }
  setAuth(auth)
  return session
}

export function logout() {
  setAuth(null)
}
