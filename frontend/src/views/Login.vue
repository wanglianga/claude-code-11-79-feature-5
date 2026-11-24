<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ACCOUNTS, login, ApiError } from '../lib/api.js'

const router = useRouter()
const username = ref('global')
const password = ref('demo123')
const error = ref('')
const busy = ref(false)

async function signIn() {
  busy.value = true
  error.value = ''
  try {
    await login(username.value.trim(), password.value)
    router.push('/overview')
  } catch (e) {
    error.value = e instanceof ApiError ? 'Those credentials were not accepted.' : String(e)
  } finally {
    busy.value = false
  }
}

function pick(account) {
  username.value = account.username
  password.value = account.password
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand" style="border: none; text-align: center; padding: 0 0 20px">
        <div class="mark" style="justify-content: center; font-size: 18px">
          <span class="dot"></span><span>APEX-3 Console</span>
        </div>
        <div class="sub">Multinational trial · consent, protocol &amp; cross-border egress</div>
      </div>

      <div class="panel">
        <div class="panel-head"><h2>Sign in</h2></div>
        <div class="panel-body stack">
          <label class="field">
            <span>Username</span>
            <input v-model="username" autocomplete="username" @keyup.enter="signIn" />
          </label>
          <label class="field">
            <span>Password</span>
            <input v-model="password" type="password" autocomplete="current-password" @keyup.enter="signIn" />
          </label>
          <div v-if="error" class="notice bad">{{ error }}</div>
          <button class="primary" :disabled="busy" @click="signIn">
            {{ busy ? 'Signing in…' : 'Sign in' }}
          </button>

          <div class="divider"></div>
          <div class="faint small" style="margin-bottom: 6px">Demo accounts — password <code>demo123</code></div>
          <div class="account-list">
            <button
              v-for="a in ACCOUNTS"
              :key="a.username"
              class="account"
              :class="{ active: username === a.username }"
              @click="pick(a)"
            >
              <div style="flex: 1">
                <div style="font-weight: 600">{{ a.label }}</div>
                <div class="role">{{ a.username }} · {{ a.role }}</div>
              </div>
              <span class="small faint" style="max-width: 150px; text-align: right">{{ a.note }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
