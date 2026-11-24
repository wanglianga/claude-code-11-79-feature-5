<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const study = ref(null)
const acceptance = ref(null)
const watermarks = ref(null)
const deliveries = ref([])
const quarantine = ref([])
const runs = ref([])

onMounted(async () => {
  try {
    const [s, a, w, d, q, r] = await Promise.all([
      api.get('/study'),
      api.get(`/acceptance?day=${store.analysisDay}`),
      api.get(`/watermarks?day=${store.analysisDay}`),
      api.get('/deliveries'),
      api.get('/quarantine'),
      api.get('/runs')
    ])
    study.value = s
    acceptance.value = a
    watermarks.value = w
    deliveries.value = d
    quarantine.value = q
    runs.value = r
  } finally {
    loading.value = false
  }
})

const passing = computed(() => (acceptance.value?.checks || []).filter((c) => c.pass).length)
const total = computed(() => (acceptance.value?.checks || []).length)
const unconfirmed = computed(() => (watermarks.value?.rows || []).filter((r) => r.sourceConfirmedSeq > r.analysisSeq))
const activeDeliveries = computed(() => deliveries.value.filter((d) => d.status === 'ACTIVE'))
const openQuarantine = computed(() => quarantine.value.filter((q) => q.state === 'OPEN'))
const staleRuns = computed(() => runs.value.filter((r) => r.status === 'STALE'))

const subjectOrder = ['PSN-A1', 'PSN-B2', 'PSN-C3', 'PSN-D4']
const subjectLabel = { 'PSN-A1': 'A', 'PSN-B2': 'B', 'PSN-C3': 'C', 'PSN-D4': 'D' }

function fmt(dt) {
  if (!dt) return '—'
  return new Date(dt).toISOString().replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div v-if="loading" class="empty">Loading workspace…</div>
  <div v-else>
    <div class="page-head">
      <h1>Dual-domain control overview</h1>
      <p class="lede">
        {{ study.name }} · {{ study.code }} · sponsor {{ study.sponsor }}. Source records stay local in China by
        default; the global plane receives only de-identified derived data inside an approved purpose, field,
        site, window and protocol-version envelope. Analysis day
        <strong>{{ store.analysisDay }}</strong>.
      </p>
    </div>

    <div class="grid cols-4" style="margin-bottom: 16px">
      <div class="stat">
        <div class="label">Acceptance checks</div>
        <div class="value" :style="{ color: acceptance?.allPass ? 'var(--ok)' : 'var(--bad)' }">{{ passing }}/{{ total }}</div>
        <div class="foot">{{ acceptance?.allPass ? 'All requirements hold' : 'See failing checks' }}</div>
      </div>
      <div class="stat">
        <div class="label">Unconfirmed domains</div>
        <div class="value" :style="{ color: unconfirmed.length ? 'var(--warn)' : 'var(--ok)' }">{{ unconfirmed.length }}</div>
        <div class="foot">gaps above the analysis watermark</div>
      </div>
      <div class="stat">
        <div class="label">Active deliveries</div>
        <div class="value">{{ activeDeliveries.length }}</div>
        <div class="foot">{{ deliveries.length }} total compiled</div>
      </div>
      <div class="stat">
        <div class="label">Open quarantine</div>
        <div class="value" :style="{ color: openQuarantine.length ? 'var(--warn)' : 'var(--ok)' }">{{ openQuarantine.length }}</div>
        <div class="foot">{{ staleRuns.length }} stale snapshot(s)</div>
      </div>
    </div>

    <div class="grid cols-2">
      <section class="panel">
        <div class="panel-head">
          <h2>Acceptance scenarios</h2>
          <div class="spacer"></div>
          <router-link to="/acceptance" class="small">Full report →</router-link>
        </div>
        <div class="panel-body tight">
          <table>
            <thead><tr><th>Subject</th><th>Requirement</th><th>Outcome</th></tr></thead>
            <tbody>
              <tr v-for="c in acceptance?.checks || []" :key="c.subject + c.code">
                <td><span class="chip">{{ c.subject }}</span></td>
                <td class="small">{{ c.requirement }}</td>
                <td><StatusBadge :kind="c.pass ? 'PASS' : 'FINDINGS'" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div class="stack">
        <section class="panel">
          <div class="panel-head"><h2>Watermark gaps</h2><div class="spacer"></div>
            <router-link to="/watermarks" class="small">Manage →</router-link></div>
          <div class="panel-body">
            <div v-if="!unconfirmed.length" class="notice ok">Every domain's analysis watermark has caught up with its source.</div>
            <div v-else class="stack">
              <div v-for="w in unconfirmed" :key="w.siteCode + w.domain" class="notice warn">
                <strong>{{ w.siteCode }} · {{ w.domain }}</strong> — source confirmed to
                <span class="num">{{ w.sourceConfirmedSeq }}</span> but analysis at
                <span class="num">{{ w.analysisSeq }}</span>. The intervening records are
                <strong>UNCONFIRMED</strong>, not zero.
              </div>
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Recent deliveries</h2><div class="spacer"></div>
            <router-link to="/deliveries" class="small">Open →</router-link></div>
          <div class="panel-body tight">
            <div v-if="!deliveries.length" class="empty">No deliveries compiled yet.</div>
            <table v-else>
              <thead><tr><th>Code</th><th>Day</th><th>Members</th><th>Status</th></tr></thead>
              <tbody>
                <tr v-for="d in deliveries.slice(0, 5)" :key="d.code">
                  <td class="mono">{{ d.code }}</td>
                  <td class="num">D{{ d.analysisDay }}</td>
                  <td class="num">{{ d.legalMemberCount }}</td>
                  <td><StatusBadge :kind="d.status" /></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>

    <section class="panel" style="margin-top: 16px">
      <div class="panel-head"><h2>Subjects &amp; sites</h2></div>
      <div class="panel-body tight">
        <table>
          <thead><tr><th>Subject</th><th>Site</th><th>Region</th><th>China-resident</th><th>Pseudonym</th></tr></thead>
          <tbody>
            <tr v-for="s in study.subjects" :key="s.pseudonym">
              <td><span class="chip">{{ subjectLabel[s.pseudonym] || '?' }}</span> {{ s.pseudonym }}</td>
              <td>{{ s.site }} — {{ s.siteName }}</td>
              <td>{{ s.region }}</td>
              <td>{{ s.chinaResident ? 'yes — defaults to local residence' : 'no' }}</td>
              <td class="mono-sm faint">{{ s.pseudonym }} (no direct identifier leaves the site)</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
