<template>
  <RouterView v-if="route.meta.bare" />
  <AppShell v-else-if="session.status === 'signed-in'" @sign-out="onSignOut">
    <NoAccess v-if="denied" />
    <RouterView v-else />
  </AppShell>
  <PlToastHost />
</template>

<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppShell from './shell/AppShell.vue';
import NoAccess from './shell/NoAccess.vue';
import { allowed } from './shell/access.js';
import { session, signOut } from './shell/session.js';
import { PlToastHost, showToast } from './ui/index.js';

const route = useRoute();
const router = useRouter();

// A module route the user's permissions do not allow renders the no-access card in place of the
// screen, so the screen never mounts and makes no request.
const denied = computed(() => !allowed(route));

async function onSignOut() {
  try {
    await signOut();
  } catch {
    // the local session is cleared either way
  }

  await router.push({ name: 'sign-in' });
  showToast('Signed out');
}
</script>
