<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { store } from './lib/store.js'
import { getAuth, logout } from './lib/api.js'

const route = useRoute()
const router = useRouter()
const auth = computed(() => getAuth())
const isPublic = computed(() => route.meta.public)

const navGroups = [
  {
    label: 'Workspace',
    items: [
      { to: '/overview', label: 'Overview', title: 'Overview' },
      { to: '/acceptance', label: 'Acceptance', title: 'Acceptance' },
      { to: '/consent', label: 'Consent & protocol', title: 'Consent & protocol' }
    ]
  },
  {
    label: 'Dual-domain control',
    items: [
      { to: '/eligibility', label: 'Eligibility', title: 'Dual-domain eligibility' },
      { to: '/watermarks', label: 'Watermarks', title: 'Watermarks & transfer' },
      { to: '/deliveries', label: 'Deliveries', title: 'Snapshots & deliveries' },
      { to: '/definition-comparison', label: 'Definition comparison', title: 'Definition-impact comparison versions' }
    ]
  },
  {
    label: 'Governance',
    items: [
      { to: '/revocation', label: 'Withdrawal', title: 'Withdrawal propagation' },
      { to: '/recovery', label: 'Recovery', title: 'Recovery & quarantine' },
      { to: '/audit', label: 'Audit trail', title: 'Audit trail' }
    ]
  }
]

function signOut() {
  logout()
  router.push('/login')
}
</script>

<template>
  <div v-if="isPublic" class="public-shell">
    <router-view />
  </div>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="mark"><span class="dot"></span><span>APEX-3 Console</span></div>
        <div class="sub">Dual-domain sync &amp; egress control</div>
      </div>
      <template v-for="group in navGroups" :key="group.label">
        <div class="nav-group-label">{{ group.label }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="nav-link"
        >{{ item.label }}</router-link>
      </template>
      <div style="flex: 1"></div>
      <div v-if="auth" class="small faint" style="padding: 10px">
        <div class="row" style="justify-content: space-between">
          <span>{{ auth.username }}</span>
          <button class="sm ghost" @click="signOut">Sign out</button>
        </div>
        <div class="mono-sm" style="margin-top: 4px">{{ (auth.roles || []).join(', ') }}</div>
      </div>
    </aside>
    <div class="main">
      <header class="topbar">
        <h2 style="font-size: 15px">{{ route.meta.title || 'Workspace' }}</h2>
        <div class="spacer"></div>
        <div class="daypicker small">
          <label class="faint" style="font-size: 12px">Analysis day</label>
          <input type="number" min="1" max="365" v-model.number="store.analysisDay" />
        </div>
      </header>
      <main class="content">
        <router-view :key="route.path + ':' + store.analysisDay" />
      </main>
    </div>
    <div class="toasts">
      <div v-for="t in store.toasts" :key="t.id" class="toast" :class="t.kind">{{ t.message }}</div>
    </div>
  </div>
</template>
