<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'

const loading = ref(true)
const logs = ref([])
const filter = ref('')

async function load() {
  loading.value = true
  try {
    logs.value = await api.get('/audit')
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const f = filter.value.trim().toLowerCase()
  if (!f) return logs.value
  return logs.value.filter((l) =>
    [l.actor, l.action, l.entityType, l.entityRef, l.detail].join(' ').toLowerCase().includes(f)
  )
})

function fmt(dt) {
  return new Date(dt).toISOString().replace('T', ' ').slice(0, 19)
}

const colorFor = (action) => {
  if (action.includes('WITHDRAW') || action.includes('INVALIDATED') || action.includes('QUARANTIN')) return 'warn'
  if (action.includes('DELIVERY') || action.includes('MATERIALIZED') || action.includes('SEEDED')) return 'ok'
  if (action.includes('RECOVERY')) return 'local'
  return ''
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Audit trail</h1>
      <p class="lede">
        Every state transition that matters to a regulator is recorded here: consent and protocol entry, snapshot
        materialisation and invalidation, delivery compilation, withdrawal propagation, recovery checks and
        quarantine. Regulator-facing writes are append-only.
      </p>
    </div>

    <div class="row" style="margin-bottom: 14px">
      <input v-model="filter" placeholder="Filter actor / action / entity…" style="width: 320px" />
      <button @click="load">Reload</button>
      <div class="spacer" style="flex: 1"></div>
      <span class="small faint">{{ filtered.length }} entries</span>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <section v-else class="panel">
      <div class="panel-body tight scroll-y" style="max-height: calc(100vh - 260px)">
        <table>
          <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Entity</th><th>Detail</th></tr></thead>
          <tbody>
            <tr v-for="l in filtered" :key="l.id">
              <td class="mono-sm faint" style="white-space: nowrap">{{ fmt(l.at) }}</td>
              <td class="mono-sm">{{ l.actor }}</td>
              <td><span class="badge" :class="colorFor(l.action)">{{ l.action }}</span></td>
              <td class="mono-sm">{{ l.entityType }}<span class="faint"> · {{ l.entityRef }}</span></td>
              <td class="small muted">{{ l.detail }}</td>
            </tr>
            <tr v-if="!filtered.length"><td colspan="5" class="empty">No matching entries.</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
