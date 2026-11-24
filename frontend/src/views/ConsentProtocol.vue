<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast, bump } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const consents = ref([])
const protocols = ref([])
const approvals = ref([])
const study = ref(null)
const tab = ref('consent')

const showGrant = ref(false)
const showWithdrawal = ref(false)
const showProtocol = ref(false)
const busy = ref(false)

const grantForm = ref({
  pseudonym: 'PSN-A1',
  purposes: ['CHINA_LOCAL_ANALYSIS'],
  fieldGroups: ['DEMOG', 'VISIT_VITALS', 'LABS', 'AE', 'IMAGING'],
  regions: ['CN'],
  effectiveFrom: '',
  recordedAt: '',
  documentRef: '',
  note: ''
})

const withdrawalForm = ref({
  pseudonym: 'PSN-B2',
  purposes: ['GLOBAL_ANALYSIS'],
  regions: ['EU', 'SG', 'DE', 'CN'],
  effectiveFrom: '',
  recordedAt: '',
  documentRef: '',
  note: ''
})

const protocolForm = ref({
  version: 'v2.1',
  title: '',
  status: 'DRAFT',
  ethicsApprovedAt: '',
  sponsorApprovedAt: '',
  effectiveFrom: '',
  applicableSites: 'CN-01',
  requiredFieldGroups: 'IMAGING,IMAGING_ADC',
  amendsVersion: 'v2.0',
  note: ''
})

const PURPOSES = ['GLOBAL_ANALYSIS', 'CHINA_LOCAL_ANALYSIS', 'LOCAL_ANALYSIS', 'SAFETY_MONITORING', 'REGULATORY_SUBMISSION']
const FIELDS = ['DEMOG', 'VISIT_VITALS', 'LABS', 'AE', 'IMAGING', 'IMAGING_ADC', 'IMAGING_TUMOR_VOL', 'RANDOMIZATION', 'DEVIATION']
const REGIONS = ['CN', 'SG', 'DE', 'EU', 'GLOBAL']

async function load() {
  loading.value = true
  try {
    const [c, p, a, s] = await Promise.all([
      api.get('/consents'),
      api.get('/protocols'),
      api.get('/approvals'),
      api.get('/study')
    ])
    consents.value = c
    protocols.value = p
    approvals.value = a
    study.value = s
  } finally {
    loading.value = false
  }
}
onMounted(load)

function isoOrNull(v) {
  return v ? new Date(v).toISOString() : null
}

function subjectOptions() {
  return (study.value?.subjects || []).map((s) => ({ pseudonym: s.pseudonym, label: `${s.pseudonym} (${s.site})` }))
}

async function submitConsent(kind) {
  const f = kind === 'GRANT' ? grantForm.value : withdrawalForm.value
  busy.value = true
  try {
    const res = await api.post('/consents', {
      pseudonym: f.pseudonym,
      kind,
      purposes: f.purposes,
      fieldGroups: kind === 'GRANT' ? f.fieldGroups : [],
      regions: f.regions,
      effectiveFrom: isoOrNull(f.effectiveFrom),
      recordedAt: isoOrNull(f.recordedAt),
      documentRef: f.documentRef,
      note: f.note
    })
    toast(`${kind === 'GRANT' ? 'Grant' : 'Withdrawal'} recorded for ${f.pseudonym}; ` +
      `${res.runsInvalidated} snapshot run(s) invalidated.`, 'ok')
    showGrant.value = false
    showWithdrawal.value = false
    await load()
    bump()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function submitProtocol() {
  busy.value = true
  try {
    const f = protocolForm.value
    const res = await api.post('/protocols', {
      version: f.version,
      title: f.title,
      status: f.status,
      ethicsApprovedAt: isoOrNull(f.ethicsApprovedAt),
      sponsorApprovedAt: isoOrNull(f.sponsorApprovedAt),
      effectiveFrom: isoOrNull(f.effectiveFrom) || new Date().toISOString(),
      applicableSites: f.applicableSites.split(',').map((x) => x.trim()).filter(Boolean),
      requiredFieldGroups: f.requiredFieldGroups.split(',').map((x) => x.trim()).filter(Boolean),
      amendsVersion: f.amendsVersion,
      note: f.note
    })
    toast(`Protocol ${f.version} saved; ${res.runsInvalidated} snapshot run(s) invalidated.`, 'ok')
    showProtocol.value = false
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

function toggle(list, value) {
  const i = list.indexOf(value)
  if (i >= 0) list.splice(i, 1)
  else list.push(value)
}

const consensusBySubject = computed(() => {
  const m = {}
  for (const c of consents.value) {
    m[c.subject] = m[c.subject] || []
    m[c.subject].push(c)
  }
  return m
})

function fmt(dt) {
  if (!dt) return '—'
  return new Date(dt).toISOString().replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Consent ledger &amp; protocol catalogue</h1>
      <p class="lede">
        Consent is recorded per subject, purpose, field group and region, and is valid on the
        <strong>local signed or withdrawn instant</strong> — never on the platform receipt time. Protocol
        amendments apply only from their explicit effective date, once both ethics and sponsor approval exist.
      </p>
    </div>

    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'consent' }" @click="tab = 'consent'">Consent ledger</button>
      <button class="tab" :class="{ active: tab === 'protocol' }" @click="tab = 'protocol'">Protocol catalogue</button>
      <button class="tab" :class="{ active: tab === 'approval' }" @click="tab = 'approval'">Approved envelopes</button>
    </div>

    <div v-if="loading" class="empty">Loading…</div>

    <template v-else-if="tab === 'consent'">
      <div class="row" style="margin-bottom: 16px">
        <button class="primary" @click="showGrant = !showGrant">+ Record grant</button>
        <button class="danger" @click="showWithdrawal = !showWithdrawal">+ Record withdrawal</button>
        <div class="spacer"></div>
        <router-link to="/revocation" class="small">Propagate to deliveries →</router-link>
      </div>

      <section v-if="showGrant" class="panel" style="margin-bottom: 16px">
        <div class="panel-head"><h2>New consent grant</h2></div>
        <div class="panel-body stack">
          <div class="grid cols-2">
            <label class="field"><span>Subject</span>
              <select v-model="grantForm.pseudonym">
                <option v-for="s in subjectOptions()" :key="s.pseudonym" :value="s.pseudonym">{{ s.label }}</option>
              </select>
            </label>
            <label class="field"><span>Document reference</span>
              <input v-model="grantForm.documentRef" placeholder="ICF-2026-…" />
            </label>
            <label class="field"><span>Effective from (local signature time)</span>
              <input v-model="grantForm.effectiveFrom" type="datetime-local" />
            </label>
            <label class="field"><span>Platform receipt time</span>
              <input v-model="grantForm.recordedAt" type="datetime-local" />
            </label>
          </div>
          <div class="grid cols-2">
            <div>
              <div class="small faint" style="margin-bottom: 5px">Purposes</div>
              <div class="row wrap">
                <button v-for="p in PURPOSES" :key="p" class="sm"
                  :class="{ primary: grantForm.purposes.includes(p) }"
                  @click="toggle(grantForm.purposes, p)">{{ p }}</button>
              </div>
            </div>
            <div>
              <div class="small faint" style="margin-bottom: 5px">Regions</div>
              <div class="row wrap">
                <button v-for="r in REGIONS" :key="r" class="sm"
                  :class="{ primary: grantForm.regions.includes(r) }"
                  @click="toggle(grantForm.regions, r)">{{ r }}</button>
              </div>
            </div>
          </div>
          <div>
            <div class="small faint" style="margin-bottom: 5px">Field groups</div>
            <div class="row wrap">
              <button v-for="f in FIELDS" :key="f" class="sm"
                :class="{ primary: grantForm.fieldGroups.includes(f) }"
                @click="toggle(grantForm.fieldGroups, f)">{{ f }}</button>
            </div>
          </div>
          <label class="field"><span>Note</span><textarea v-model="grantForm.note"></textarea></label>
          <div class="row">
            <button class="primary" :disabled="busy" @click="submitConsent('GRANT')">Save grant</button>
            <button @click="showGrant = false">Cancel</button>
          </div>
        </div>
      </section>

      <section v-if="showWithdrawal" class="panel" style="margin-bottom: 16px">
        <div class="panel-head"><h2>New withdrawal</h2></div>
        <div class="panel-body stack">
          <div class="notice warn">
            A withdrawal stops future queries from its effective instant. Previously delivered content is not
            erased — propagate it from the Withdrawal page to revoke access and apply the registered disposition.
          </div>
          <div class="grid cols-2">
            <label class="field"><span>Subject</span>
              <select v-model="withdrawalForm.pseudonym">
                <option v-for="s in subjectOptions()" :key="s.pseudonym" :value="s.pseudonym">{{ s.label }}</option>
              </select>
            </label>
            <label class="field"><span>Document reference</span>
              <input v-model="withdrawalForm.documentRef" />
            </label>
            <label class="field"><span>Effective from (local signature time)</span>
              <input v-model="withdrawalForm.effectiveFrom" type="datetime-local" />
            </label>
            <label class="field"><span>Platform receipt time</span>
              <input v-model="withdrawalForm.recordedAt" type="datetime-local" />
            </label>
          </div>
          <div class="grid cols-2">
            <div>
              <div class="small faint" style="margin-bottom: 5px">Withdrawn purposes</div>
              <div class="row wrap">
                <button v-for="p in PURPOSES" :key="p" class="sm"
                  :class="{ primary: withdrawalForm.purposes.includes(p) }"
                  @click="toggle(withdrawalForm.purposes, p)">{{ p }}</button>
              </div>
            </div>
            <div>
              <div class="small faint" style="margin-bottom: 5px">Regions</div>
              <div class="row wrap">
                <button v-for="r in REGIONS" :key="r" class="sm"
                  :class="{ primary: withdrawalForm.regions.includes(r) }"
                  @click="toggle(withdrawalForm.regions, r)">{{ r }}</button>
              </div>
            </div>
          </div>
          <label class="field"><span>Note</span><textarea v-model="withdrawalForm.note"></textarea></label>
          <div class="row">
            <button class="danger" :disabled="busy" @click="submitConsent('WITHDRAWAL')">Save withdrawal</button>
            <button @click="showWithdrawal = false">Cancel</button>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head"><h2>Ledger</h2><div class="spacer"></div>
          <span class="small faint">append-only · validity uses the local signature instant</span></div>
        <div class="panel-body tight">
          <table>
            <thead>
              <tr>
                <th>#</th><th>Subject</th><th>Kind</th><th>Purposes</th><th>Regions</th>
                <th>Effective (local)</th><th>Received</th><th>Fields</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in consents" :key="c.id">
                <td class="num">{{ c.id }}</td>
                <td><span class="chip">{{ c.subject }}</span> <span class="faint mono-sm">{{ c.site }}</span></td>
                <td><StatusBadge :kind="c.kind" compact /></td>
                <td><span class="chip" v-for="p in c.purposes.split(',')" :key="p">{{ p }}</span></td>
                <td><span class="chip" v-for="r in c.regions.split(',')" :key="r">{{ r }}</span></td>
                <td class="mono-sm">D{{ c.effectiveFromDay }} · {{ fmt(c.effectiveFrom) }}</td>
                <td class="mono-sm faint">{{ fmt(c.recordedAt) }}</td>
                <td class="mono-sm faint" style="max-width: 240px">{{ c.fieldGroups || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Per-subject timelines</h2></div>
        <div class="panel-body">
          <div class="grid cols-2">
            <div v-for="(entries, subject) in consensusBySubject" :key="subject">
              <div class="row" style="margin-bottom: 10px">
                <span class="chip">{{ subject }}</span>
                <span class="faint small">{{ entries[0]?.site }}</span>
              </div>
              <div class="timeline">
                <div v-for="e in entries" :key="e.id" class="tl-item" :class="e.kind.toLowerCase()">
                  <div class="row">
                    <StatusBadge :kind="e.kind" compact />
                    <span class="small">D{{ e.effectiveFromDay }}</span>
                    <span class="mono-sm faint">{{ fmt(e.effectiveFrom) }}</span>
                  </div>
                  <div class="small muted" style="margin-top: 2px">
                    {{ e.purposes }} · {{ e.regions }}
                  </div>
                  <div class="small faint" style="margin-top: 2px">{{ e.note }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>

    <template v-else-if="tab === 'protocol'">
      <div class="row" style="margin-bottom: 16px">
        <button class="primary" @click="showProtocol = !showProtocol">+ Register protocol version</button>
      </div>

      <section v-if="showProtocol" class="panel" style="margin-bottom: 16px">
        <div class="panel-head"><h2>Register / update protocol version</h2></div>
        <div class="panel-body stack">
          <div class="notice">
            A version governs visits only when both approvals exist and the visit falls at or after the explicit
            effective instant. Approval alone does not apply it early.
          </div>
          <div class="grid cols-2">
            <label class="field"><span>Version</span><input v-model="protocolForm.version" /></label>
            <label class="field"><span>Title</span><input v-model="protocolForm.title" /></label>
            <label class="field"><span>Status</span>
              <select v-model="protocolForm.status">
                <option>DRAFT</option><option>PARTIALLY_APPROVED</option><option>APPROVED</option><option>WITHDRAWN</option>
              </select>
            </label>
            <label class="field"><span>Amends version</span><input v-model="protocolForm.amendsVersion" /></label>
            <label class="field"><span>Ethics approved at</span><input v-model="protocolForm.ethicsApprovedAt" type="datetime-local" /></label>
            <label class="field"><span>Sponsor approved at</span><input v-model="protocolForm.sponsorApprovedAt" type="datetime-local" /></label>
            <label class="field"><span>Explicit effective from</span><input v-model="protocolForm.effectiveFrom" type="datetime-local" /></label>
            <label class="field"><span>Applicable sites (comma-separated, empty = all)</span><input v-model="protocolForm.applicableSites" /></label>
          </div>
          <label class="field"><span>Required field groups (comma-separated)</span><input v-model="protocolForm.requiredFieldGroups" /></label>
          <label class="field"><span>Note</span><textarea v-model="protocolForm.note"></textarea></label>
          <div class="row">
            <button class="primary" :disabled="busy" @click="submitProtocol">Save</button>
            <button @click="showProtocol = false">Cancel</button>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head"><h2>Catalogue</h2></div>
        <div class="panel-body tight">
          <table>
            <thead>
              <tr><th>Version</th><th>Status</th><th>Approvals</th><th>Effective from</th><th>Sites</th><th>Field groups</th><th>Amends</th></tr>
            </thead>
            <tbody>
              <tr v-for="p in protocols" :key="p.version">
                <td class="mono" style="font-weight: 600">{{ p.version }}</td>
                <td><StatusBadge :kind="p.status" compact /></td>
                <td class="mono-sm faint">
                  <div>ethics {{ fmt(p.ethicsApprovedAt) }}</div>
                  <div>sponsor {{ fmt(p.sponsorApprovedAt) }}</div>
                  <div v-if="!p.fullyApproved" class="badge warn" style="margin-top: 3px">not fully approved</div>
                </td>
                <td class="mono-sm">D{{ p.effectiveFromDay }}<br />{{ fmt(p.effectiveFrom) }}</td>
                <td class="mono-sm">{{ p.applicableSites || 'all sites' }}</td>
                <td class="mono-sm faint" style="max-width: 220px">{{ p.requiredFieldGroups }}</td>
                <td class="mono-sm faint">{{ p.amendsVersion || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <div class="small muted" style="margin-top: 10px" v-for="p in protocols" :key="p.version + 'n'">
        <strong>{{ p.version }}:</strong> {{ p.note }}
      </div>
    </template>

    <template v-else>
      <section class="panel">
        <div class="panel-head"><h2>Approved data-use envelopes</h2></div>
        <div class="panel-body tight">
          <table>
            <thead>
              <tr><th>Code</th><th>Purpose</th><th>Cross-border</th><th>Destinations</th><th>Sites</th><th>Versions</th><th>Window</th></tr>
            </thead>
            <tbody>
              <tr v-for="a in approvals" :key="a.code">
                <td class="mono">{{ a.code }}</td>
                <td><span class="chip">{{ a.purpose }}</span></td>
                <td>{{ a.crossBorder ? 'yes' : 'no' }}</td>
                <td class="mono-sm">{{ a.destinationRegions }}</td>
                <td class="mono-sm">{{ a.siteScope }}</td>
                <td class="mono-sm">{{ a.protocolVersions }}</td>
                <td class="mono-sm">{{ fmt(a.observationFrom) }} → {{ fmt(a.observationTo) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <div class="small muted" style="margin-top: 10px">
        Envelopes only ever narrow what consent grants — the intersection, never the union, of all scopes.
      </div>
    </template>
  </div>
</template>
