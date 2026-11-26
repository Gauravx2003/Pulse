<template>
  <PlCard
    title="Unmatched accounts"
    subtitle="Tool accounts seen by live syncs. Link each to a person, or mark it as a bot so it stays out of figures."
  >
    <div class="filters">
      <PlField
        v-model="tool"
        label="Tool"
        type="select"
        placeholder="All tools"
        :options="tools.map((t) => ({ value: t.id, label: t.name }))"
      />
      <PlField v-model="status" label="Show" type="select" required :options="STATUS_OPTIONS" />
    </div>

    <PlAlert v-if="loadError" badge="Error" tone="danger">
      {{ loadError }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>

    <PlTable :columns="COLUMNS" :rows="rows" row-key="id" label="Tool accounts" :loading="loading" :empty-text="emptyText">
      <template #cell-tool="{ row }">{{ toolName(row.tool) }}</template>
      <template #cell-login="{ row }">
        <span class="pl-mono">{{ row.login ?? row.accountId }}</span>
        <div v-if="row.displayName" class="pl-caption">{{ row.displayName }}</div>
      </template>
      <template #cell-firstSeen="{ row }"><span class="pl-caption">{{ formatDateTime(row.firstSeenAt) }}</span></template>
      <template #cell-person="{ row }">{{ row.personName ?? '—' }}</template>
      <template #cell-bot="{ row }">
        <label class="bot">
          <input
            type="checkbox"
            role="switch"
            class="pl-switch"
            :checked="row.isBot"
            :disabled="busyId === row.id"
            :aria-label="`Bot: ${row.login ?? row.accountId}`"
            @change="setBot(row, $event.target.checked)"
          />
          <span class="pl-caption">{{ row.isBot ? 'Bot' : 'Person' }}</span>
        </label>
      </template>
      <template #cell-actions="{ row }">
        <button
          v-if="row.personId"
          type="button"
          class="pl-btn pl-btn-sm"
          :disabled="busyId === row.id"
          :aria-label="`Unlink ${row.login ?? row.accountId}`"
          @click="unlink(row)"
        >
          Unlink
        </button>
        <button
          v-else
          type="button"
          class="pl-btn pl-btn-sm"
          :disabled="busyId === row.id || row.isBot"
          :aria-label="`Link ${row.login ?? row.accountId} to a person`"
          @click="linking = row"
        >
          Link to person
        </button>
      </template>
    </PlTable>

    <div v-if="total > rows.length || page > 1" class="pager">
      <span class="pl-caption">Page {{ page }} · {{ total }} accounts</span>
      <button type="button" class="pl-btn pl-btn-sm" :disabled="page <= 1 || loading" @click="go(page - 1)">Previous</button>
      <button type="button" class="pl-btn pl-btn-sm" :disabled="page * pageSize >= total || loading" @click="go(page + 1)">
        Next
      </button>
    </div>

    <LinkPersonModal :open="linking !== null" :account="linking" @close="linking = null" @linked="onLinked" />
  </PlCard>
</template>

<script setup>
// Unmatched accounts panel (task 24.7, spec admin/integrations-connectors "Unmatched accounts
// panel"): tool accounts seen by live syncs, filterable by tool. An Admin links one to a person
// (search), unlinks it, or marks it as a bot; each change is audited by the server, and figures
// follow on the next read (task 25.3) with no re-sync.
import { computed, onMounted, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { formatDateTime } from '@/ui/format.js';
import { PlAlert, PlCard, PlField, PlTable, showToast } from '@/ui/index.js';
import LinkPersonModal from './LinkPersonModal.vue';

const props = defineProps({
  /** tools that have a live connector: [{id, name}] */
  tools: { type: Array, default: () => [] },
});

const COLUMNS = [
  { key: 'tool', label: 'Tool' },
  { key: 'login', label: 'Account' },
  { key: 'firstSeen', label: 'First seen' },
  { key: 'person', label: 'Person' },
  { key: 'bot', label: 'Bot' },
  { key: 'actions', label: 'Actions' },
];

const STATUS_OPTIONS = [
  { value: 'unmatched', label: 'Unmatched' },
  { value: 'linked', label: 'Linked' },
  { value: 'bot', label: 'Bots' },
  { value: 'all', label: 'All' },
];

const tool = ref('');
const status = ref('unmatched');
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(25);
const loading = ref(true);
const loadError = ref('');
const busyId = ref('');
const linking = ref(null);

const emptyText = computed(() =>
  status.value === 'unmatched' ? 'Every account seen by live syncs is linked or marked as a bot.' : 'No accounts to show.',
);

function toolName(id) {
  return props.tools.find((t) => t.id === id)?.name ?? id;
}

async function load() {
  loading.value = true;
  loadError.value = '';

  try {
    const res = await api.get('/integrations/accounts', {
      query: { status: status.value, tool: tool.value || null, page: page.value, pageSize: pageSize.value },
      quiet: true,
    });

    rows.value = res.items ?? [];
    total.value = res.total ?? rows.value.length;
    pageSize.value = res.pageSize ?? pageSize.value;
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

function go(next) {
  page.value = next;
  load();
}

watch([tool, status], () => go(1));

async function change(row, call, done) {
  busyId.value = row.id;

  try {
    await call();
    showToast(done);
    await load();
  } catch {
    // shown by the API client
    await load();
  } finally {
    busyId.value = '';
  }
}

function unlink(row) {
  return change(row, () => api.delete(`/integrations/accounts/${row.id}/link`), `Unlinked · ${row.login ?? row.accountId}`);
}

function setBot(row, isBot) {
  return change(
    row,
    () => api.put(`/integrations/accounts/${row.id}/bot`, { isBot }),
    `${row.login ?? row.accountId} · ${isBot ? 'marked as a bot' : 'no longer a bot'}`,
  );
}

function onLinked() {
  linking.value = null;
  load();
}

defineExpose({ load });

onMounted(load);
</script>

<style scoped>
.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 220px));
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.bot {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.pager {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  align-items: center;
  justify-content: flex-end;
  margin-top: var(--space-3);
}
</style>
