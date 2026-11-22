<template>
  <select
    v-if="options.length > 1"
    class="pl-input is-sm"
    aria-label="Person"
    :value="personState.id ?? selfId ?? ''"
    @change="setPerson($event.target.value === selfId ? null : $event.target.value)"
  >
    <option v-for="p in options" :key="p.id" :value="p.id">{{ p.name }}{{ p.id === selfId ? ' (me)' : '' }}</option>
  </select>
</template>

<script setup>
// Person selector on person pages for managers and Admin (reference section 3); the list is the
// server-side policy scope from GET /people, never filtered in the browser.
import { computed, onMounted } from 'vue';
import { loadPeople, people, personState, setPerson } from './person.js';
import { session } from './session.js';

const selfId = computed(() => session.me?.user?.personId ?? null);

const options = computed(() => people.value ?? []);

onMounted(() => {
  loadPeople().catch((e) => console.error('GET /people failed', e));
});
</script>
