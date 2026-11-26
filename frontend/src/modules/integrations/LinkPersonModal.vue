<template>
  <PlModal :open="open" :title="`Link ${accountLabel} to a person`" @close="emit('close')">
    <form class="pl-form" novalidate autocomplete="off" @submit.prevent="link">
      <p class="pl-caption">
        {{ toolLabel }} account <span class="pl-mono">{{ accountLabel }}</span>
        <template v-if="account?.displayName"> · {{ account.displayName }}</template>
        <template v-if="account?.email"> · {{ account.email }}</template>
      </p>
      <PlField v-model="q" label="Search people" placeholder="Name or email" autocomplete="off" />

      <fieldset class="people" :aria-busy="loading ? 'true' : 'false'">
        <legend class="pl-field-label">Person</legend>
        <p v-if="!loading && !people.length" class="pl-caption">No person matches “{{ q }}”.</p>
        <label v-for="p in people" :key="p.id" class="person">
          <input v-model="personId" type="radio" name="link-person" :value="p.id" />
          <span>
            <b>{{ p.name }}</b>
            <span class="pl-caption">{{ [p.title, p.department, p.email].filter(Boolean).join(' · ') }}</span>
          </span>
        </label>
      </fieldset>
      <PlFormError :message="formError" />
    </form>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="!personId || busy" @click="link">Link</button>
    </template>
  </PlModal>
</template>

<script setup>
// Link a live tool account to a person (task 24.7): search people by name or email
// (GET /integrations/people), pick one, link. 409 when the account is already linked to someone.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { PlField, PlFormError, PlModal, showToast } from '@/ui/index.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  /** a row of GET /integrations/accounts */
  account: { type: Object, default: null },
});

const emit = defineEmits(['close', 'linked']);

const SEARCH_DELAY_MS = 250;

const q = ref('');
const people = ref([]);
const personId = ref('');
const loading = ref(false);
const busy = ref(false);
const formError = ref('');

const accountLabel = computed(() => props.account?.login ?? props.account?.accountId ?? '');
const toolLabel = computed(() => props.account?.tool ?? '');

let timer = null;
let seq = 0;

async function search() {
  const mine = ++seq;

  loading.value = true;

  try {
    const res = await api.get('/integrations/people', { query: { q: q.value.trim() || null, pageSize: 10 }, quiet: true });

    if (mine === seq) {
      people.value = res.items ?? [];

      if (!people.value.some((p) => p.id === personId.value)) {
        personId.value = '';
      }
    }
  } catch (e) {
    if (mine === seq) {
      formError.value = e.message;
    }
  } finally {
    if (mine === seq) {
      loading.value = false;
    }
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      q.value = '';
      personId.value = '';
      formError.value = '';
      people.value = [];
      search();
    }
  },
  { immediate: true },
);

watch(q, () => {
  clearTimeout(timer);
  timer = setTimeout(search, SEARCH_DELAY_MS);
});

async function link() {
  if (!personId.value) {
    return;
  }

  busy.value = true;
  formError.value = '';

  try {
    await api.put(`/integrations/accounts/${props.account.id}/link`, { personId: personId.value }, { quiet: true });

    const name = people.value.find((p) => p.id === personId.value)?.name ?? 'person';

    showToast(`Linked · ${accountLabel.value} → ${name}`);
    emit('linked');
  } catch (e) {
    formError.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>

<style scoped>
.people {
  border: 0;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-height: 280px;
  overflow-y: auto;
}

.people legend {
  margin-bottom: var(--space-2);
}

.person {
  display: flex;
  gap: var(--space-2);
  align-items: flex-start;
  font-size: 14px;
}

.person .pl-caption {
  display: block;
}
</style>
