import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import './style.css'
import App from './App.vue'
import Login from './views/Login.vue'
import Overview from './views/Overview.vue'
import Acceptance from './views/Acceptance.vue'
import ConsentProtocol from './views/ConsentProtocol.vue'
import Eligibility from './views/Eligibility.vue'
import Watermarks from './views/Watermarks.vue'
import Deliveries from './views/Deliveries.vue'
import DefinitionComparison from './views/DefinitionComparison.vue'
import Revocation from './views/Revocation.vue'
import Recovery from './views/Recovery.vue'
import Audit from './views/Audit.vue'
import { getAuth } from './lib/api.js'

const routes = [
  { path: '/', redirect: '/overview' },
  { path: '/login', component: Login, meta: { public: true } },
  { path: '/overview', component: Overview, meta: { title: 'Overview' } },
  { path: '/acceptance', component: Acceptance, meta: { title: 'Acceptance' } },
  { path: '/consent', component: ConsentProtocol, meta: { title: 'Consent & protocol' } },
  { path: '/eligibility', component: Eligibility, meta: { title: 'Dual-domain eligibility' } },
  { path: '/watermarks', component: Watermarks, meta: { title: 'Watermarks & transfer' } },
  { path: '/deliveries', component: Deliveries, meta: { title: 'Snapshots & deliveries' } },
  { path: '/definition-comparison', component: DefinitionComparison, meta: { title: 'Definition-impact comparison' } },
  { path: '/revocation', component: Revocation, meta: { title: 'Withdrawal propagation' } },
  { path: '/recovery', component: Recovery, meta: { title: 'Recovery & quarantine' } },
  { path: '/audit', component: Audit, meta: { title: 'Audit trail' } }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  if (!to.meta.public && !getAuth()) return { path: '/login' }
  if (to.path === '/login' && getAuth()) return { path: '/overview' }
  return true
})

createApp(App).use(router).mount('#app')
