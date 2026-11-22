<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Administration" title="Users" :caption="caption">
      <template #actions>
        <input
          v-model="query"
          class="pl-input users-search"
          type="search"
          placeholder="Search name, email, role"
          aria-label="Search name, email, role"
        />
        <RouterLink class="pl-btn" :to="{ path: '/imports', query: { type: 'users' } }">Import CSV</RouterLink>
        <button type="button" class="pl-btn" :disabled="exporting" @click="exportCsv">Export CSV</button>
        <button type="button" class="pl-btn pl-btn-primary" :disabled="!options" @click="openAdd">Add user</button>
      </template>
    </PlPageHeader>

    <PlKpiRow :columns="4" label="Users by role">
      <StatTile
        v-for="role in BUILT_IN_ROLES"
        :key="role.key"
        :eyebrow="`${role.name}s`"
        :value="counts[role.key] ?? 0"
        :foot="role.description"
      />
      <StatTile eyebrow="Invited" :value="counts.invited ?? 0" foot="Waiting for first login" />
    </PlKpiRow>

    <PlCard
      title="Directory"
      subtitle="Managers can add employees to their own team · only Admin can assign Manager or Admin roles"
    >
      <PlAlert v-if="loadError" badge="Error" tone="danger">
        {{ loadError }}
        <template #action>
          <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
        </template>
      </PlAlert>
      <PlTable
        v-else
        :columns="columns"
        :rows="shown"
        row-key="id"
        label="Directory"
        :loading="loading"
        :empty-text="query ? 'No user matches the search.' : 'No users to show.'"
      >
        <template #cell-name="{ row }">
          <span class="user-cell">
            <span class="pl-avatar" aria-hidden="true">{{ initials(row.name) }}</span>
            <span class="user-text">
              <span>{{ row.name }} <span v-if="row.isSelf" class="pl-caption">(you)</span></span>
              <span class="pl-caption">{{ subtitle(row) }}</span>
            </span>
          </span>
        </template>
        <template #cell-email="{ row }">
          <span class="pl-mono email">{{ row.email }}</span>
        </template>
        <template #cell-role="{ row }">
          <Badge :text="row.role" :tone="ROLE_TONES[row.role] ?? ''" />
        </template>
        <template #cell-manager="{ row }">{{ row.manager?.name ?? '—' }}</template>
        <template #cell-status="{ row }">
          <Badge :text="row.status" :tone="STATUS_TONES[row.status] ?? ''" />
        </template>
        <template #cell-lastLogin="{ row }">
          <span class="pl-caption">{{ row.lastLoginAt ? formatDateTime(row.lastLoginAt) : 'Never' }}</span>
        </template>
        <template #cell-actions="{ row }">
          <span v-if="row.canEdit || row.canDelete" class="row-actions">
            <button
              v-if="row.canEdit"
              type="button"
              class="pl-btn pl-btn-sm"
              :aria-label="`Edit ${row.name}`"
              @click="openEdit(row)"
            >
              Edit
            </button>
            <button
              v-if="row.canDelete"
              type="button"
              class="pl-btn pl-btn-sm pl-btn-danger"
              :aria-label="`Delete ${row.name}`"
              @click="askDelete(row)"
            >
              Delete
            </button>
          </span>
          <span v-else class="pl-caption">{{ row.isSelf ? '—' : 'No permission' }}</span>
        </template>
      </PlTable>
    </PlCard>

    <UserFormModal
      :open="formOpen"
      :user="editing"
      :options="options"
      :me-id="session.me?.user?.id ?? ''"
      @close="formOpen = false"
      @saved="onSaved"
    />

    <PlConfirm
      :open="Boolean(deleting)"
      :title="`Delete ${deleting?.name ?? 'user'}?`"
      :message="deleteMessage"
      confirm-label="Delete user"
      :busy="deleteBusy"
      @cancel="deleting = null"
      @confirm="confirmDelete"
    />
  </div>
</template>

<script setup>
// Users (reference 11.1 "Screen detail — Users"; spec admin/users-roles). Admins (users.all) see the
// whole organisation; managers (users.team) see the Employees who report to them. The server applies
// every limit; the screen only shows what GET /users/items returns, with its canEdit / canDelete.
import { computed, onMounted, ref } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { session } from '@/shell/session.js';
import { formatDateTime } from '@/ui/format.js';
import { PlAlert, PlCard, PlConfirm, PlKpiRow, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import UserFormModal from './UserFormModal.vue';
import { BUILT_IN_ROLES, ROLE_TONES, STATUS_TONES, initials } from './presentation.js';

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'email', label: 'Email' },
  { key: 'role', label: 'Role' },
  { key: 'manager', label: 'Manager' },
  { key: 'status', label: 'Status' },
  { key: 'lastLogin', label: 'Last login' },
  { key: 'actions', label: 'Actions' },
];

const rows = ref([]);
const counts = ref({});
const scope = ref('all');
const options = ref(null);
const loading = ref(true);
const loadError = ref('');
const query = ref('');

const formOpen = ref(false);
const editing = ref(null);

const deleting = ref(null);
const deletion = ref(null);
const deleteBusy = ref(false);

const exporting = ref(false);

const shown = computed(() => {
  const q = query.value.trim().toLowerCase();

  if (!q) {
    return rows.value;
  }

  return rows.value.filter((r) => `${r.name} ${r.email} ${r.role}`.toLowerCase().includes(q));
});

const caption = computed(
  () => `${scope.value === 'all' ? 'All users in the organisation' : 'Employees who report to you'} · ${shown.value.length} shown`,
);

const deleteMessage = computed(() => {
  const d = deletion.value;

  if (!d) {
    return 'Loading what will be removed…';
  }

  const removed = (d.removed ?? []).join(', ');
  const reports = d.reports ? ` Their ${d.reports} direct report${d.reports === 1 ? '' : 's'} will report to ${d.reassignTo?.name ?? 'you'}.` : '';

  return `This removes ${removed}.${reports} ${d.suggestion ?? ''}`.trim();
});

function subtitle(row) {
  return [row.title, row.department].filter(Boolean).join(' · ') || '—';
}

async function load() {
  loading.value = true;
  loadError.value = '';

  try {
    const res = await api.get('/users/items', { query: { pageSize: 200 } });

    rows.value = res.items ?? [];
    counts.value = res.counts ?? {};
    scope.value = res.scope ?? 'team';
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function loadOptions() {
  try {
    options.value = await api.get('/users/options');
  } catch {
    options.value = null;
  }
}

function openAdd() {
  editing.value = null;
  formOpen.value = true;
}

function openEdit(row) {
  editing.value = row;
  formOpen.value = true;
}

function onSaved() {
  formOpen.value = false;
  load();
}

async function askDelete(row) {
  deleting.value = row;
  deletion.value = null;

  try {
    deletion.value = await api.get(`/users/items/${row.id}/deletion`);
  } catch {
    deleting.value = null;
  }
}

async function confirmDelete() {
  const row = deleting.value;

  deleteBusy.value = true;

  try {
    await api.delete(`/users/items/${row.id}`);
    showToast(`User deleted · ${row.name}`);
    deleting.value = null;
    await load();
  } catch {
    // the API client showed the error; the confirmation stays open
  } finally {
    deleteBusy.value = false;
  }
}

/** Export CSV through the CSV framework (reference 11.5): GET /imports/users/export. */
async function exportCsv() {
  exporting.value = true;

  try {
    const res = await fetch('/api/v1/imports/users/export', { credentials: 'same-origin' });

    if (!res.ok) {
      showToast(res.status === 404 ? 'Users export is not available yet.' : 'Export failed. Try again.');

      return;
    }

    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');

    a.href = url;
    a.download = 'users_export.csv';
    a.click();
    URL.revokeObjectURL(url);
    showToast('Users exported');
  } catch {
    showToast('Export failed. Try again.');
  } finally {
    exporting.value = false;
  }
}

onMounted(() => {
  load();
  loadOptions();
});
</script>

<style scoped>
.users-search {
  width: 240px;
  max-width: 100%;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-width: 200px;
}

.user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.email {
  font-size: 13px;
}

.row-actions {
  display: inline-flex;
  gap: var(--space-1);
  flex-wrap: nowrap;
}
</style>
