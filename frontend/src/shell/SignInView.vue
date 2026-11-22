<template>
  <main class="pl pl-signin">
    <div class="pl-card pl-signin-card">
      <div class="logo"><i aria-hidden="true"></i>Pulse</div>
      <div>
        <h1>Sign in</h1>
        <p class="pl-muted">Performance, road maps and live workload</p>
      </div>
      <PlAlert v-if="session.notice" badge="Signed out">{{ session.notice }}</PlAlert>
      <form class="pl-form" novalidate @submit.prevent="submit">
        <PlField v-model="email" label="Work email" type="email" autocomplete="username" :maxlength="254" />
        <PlField
          v-model="password"
          label="Password"
          type="password"
          autocomplete="current-password"
          :maxlength="200"
        />
        <PlFormError :message="error" />
        <button type="submit" class="pl-btn pl-btn-primary" :disabled="busy">Sign in</button>
      </form>
      <section v-if="demo" aria-labelledby="demo-title">
        <h2 id="demo-title" class="pl-eyebrow">Demo accounts · password {{ demo.password }}</h2>
        <div class="pl-demo-cards">
          <button
            v-for="account in demo.accounts"
            :key="account.email"
            type="button"
            class="pl-demo-card"
            :aria-label="`Use ${account.role} demo account ${account.email}`"
            @click="fill(account)"
          >
            <b>{{ account.role }}</b>
            <span>{{ account.name }}</span>
            <span class="pl-caption">{{ account.email }}</span>
          </button>
        </div>
        <p v-if="demo.others?.length" class="pl-caption">Other demo employees: {{ demo.others.join(', ') }}.</p>
      </section>
    </div>
  </main>
</template>

<script setup>
// Sign-in screen (spec platform/app-shell-ui, "Sign-in screen"; reference section 2): the error lines
// come from the auth service word for word; demo cards only while PULSE_DEMO_SEED is on.
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api/client.js';
import { PlAlert, PlField, PlFormError, showToast } from '../ui/index.js';
import { session, signIn } from './session.js';

const route = useRoute();
const router = useRouter();

const email = ref('');
const password = ref('');
const error = ref('');
const busy = ref(false);
const demo = ref(null);

onMounted(async () => {
  try {
    demo.value = (await api.get('/auth/options', { quiet: true }))?.demo ?? null;
  } catch {
    demo.value = null;
  }
});

function fill(account) {
  email.value = account.email;
  password.value = demo.value.password;
  error.value = '';
}

/** Only same-app paths: never an absolute or protocol-relative URL from the query string. */
function safeNext(next) {
  return typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') && next !== '/sign-in'
    ? next
    : null;
}

async function submit() {
  if (busy.value) {
    return;
  }

  error.value = '';
  busy.value = true;

  try {
    const me = await signIn(email.value.trim(), password.value);

    password.value = '';
    showToast(`Signed in as ${me.user.name} · ${me.role}`);
    await router.replace(safeNext(route.query.next) ?? me.landing ?? '/');
  } catch (e) {
    error.value = e.message || 'Sign-in failed. Try again.';
  } finally {
    busy.value = false;
  }
}
</script>
