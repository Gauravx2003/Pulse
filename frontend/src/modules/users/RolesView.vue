<template>
  <div class="pl-page">
    <PlPageHeader
      eyebrow="Administration"
      title="Roles & permissions"
      caption="The menu and every screen are shown or hidden from these permissions. Built-in roles can be edited; Admin always keeps full access."
    >
      <template #actions>
        <button type="button" class="pl-btn pl-btn-primary" :disabled="!roles.length" @click="openNew">New role</button>
      </template>
    </PlPageHeader>

    <PlCard
      title="Permission matrix"
      subtitle="Changes apply immediately to everyone with that role"
      :loading="loading"
      :empty="!loading && !roles.length"
      empty-text="No roles to show."
    >
      <PlAlert v-if="loadError" badge="Error" tone="danger">
        {{ loadError }}
        <template #action>
          <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
        </template>
      </PlAlert>
      <div v-else class="pl-tbl-wrap" role="region" aria-label="Permission matrix" tabindex="0">
        <table class="pl-table matrix">
          <caption class="pl-sr-only">Permission matrix</caption>
          <thead>
            <tr>
              <th scope="col">Permission</th>
              <th v-for="role in roles" :key="role.id" scope="col" class="role-head">
                {{ role.name }}
                <span class="role-count">
                  {{ role.userCount }} {{ role.userCount === 1 ? 'user' : 'users' }}
                </span>
                <button
                  v-if="!role.builtIn"
                  type="button"
                  class="pl-btn pl-btn-sm pl-btn-danger"
                  :aria-label="`Delete role ${role.name}`"
                  @click="deleting = role"
                >
                  Delete
                </button>
              </th>
            </tr>
          </thead>
          <tbody>
            <template v-for="area in areas" :key="area.name">
              <tr class="area-row">
                <th :colspan="roles.length + 1" scope="colgroup">{{ area.name }}</th>
              </tr>
              <tr v-for="perm in area.permissions" :key="perm.key">
                <th scope="row" class="perm">
                  <span class="pl-mono key">{{ perm.key }}</span>
                  <span class="pl-caption">{{ perm.description }}</span>
                </th>
                <td v-for="role in roles" :key="role.id" class="cell">
                  <input
                    type="checkbox"
                    role="switch"
                    class="pl-switch"
                    :checked="role.permissions.includes(perm.key)"
                    :disabled="role.locked || pending.has(`${role.id}:${perm.key}`)"
                    :aria-label="`${role.name}: ${perm.key}`"
                    @change="toggle(role, perm, $event)"
                  />
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </PlCard>

    <PlCard
      title="What each role sees"
      subtitle="The menu each role gets, generated from its permissions"
      :loading="loading"
      :empty="!loading && !roles.length"
    >
      <div class="role-cards">
        <article v-for="role in roles" :key="role.id" class="role-card" :aria-label="`${role.name} menu`">
          <div class="role-card-head">
            <Badge :text="role.name" :tone="ROLE_TONES[role.name] ?? ''" />
            <span class="pl-caption">{{ role.description || '—' }}</span>
          </div>
          <PlEmpty v-if="!role.menu?.length" text="No screens: this role sees only sign-in." />
          <div v-for="section in role.menu" :key="section.group" class="menu-group">
            <div class="pl-eyebrow">{{ section.group }}</div>
            <div class="pl-caption">{{ section.items.map((i) => i.label).join(' · ') }}</div>
          </div>
        </article>
      </div>
    </PlCard>

    <PlModal :open="newOpen" title="New role" @close="newOpen = false">
      <form class="pl-form" novalidate @submit.prevent="create">
        <PlField v-model="draft.name" label="Role name" required :maxlength="60" :error="errors.name" />
        <PlField
          v-model="draft.fromRoleId"
          label="Start from"
          type="select"
          required
          :options="roles.map((r) => ({ value: r.id, label: r.name }))"
          :error="errors.fromRoleId"
        />
        <PlField v-model="draft.description" label="Description" :maxlength="200" :error="errors.description" />
        <PlFormError :message="formError" />
      </form>
      <template #actions>
        <button type="button" class="pl-btn" @click="newOpen = false">Cancel</button>
        <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="create">Create role</button>
      </template>
    </PlModal>

    <PlConfirm
      :open="Boolean(deleting)"
      :title="`Delete role ${deleting?.name ?? ''}?`"
      :message="deleting?.userCount ? `${deleting.userCount} user(s) still hold this role. Move them to another role first.` : 'Nobody holds this role. It is removed for good.'"
      confirm-label="Delete role"
      :busy="busy"
      @cancel="deleting = null"
      @confirm="remove"
    />
  </div>
</template>

<script setup>
// Roles & permissions (reference 11.2 "Screen detail — Roles & permissions"; spec admin/users-roles).
// A switch grants or revokes at once for everyone with the role; Admin switches are locked on. Custom
// roles start from an existing role and can be deleted only when nobody holds them.
import { computed, onMounted, reactive, ref } from 'vue';
import { ApiError, api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { PlAlert, PlCard, PlConfirm, PlEmpty, PlField, PlFormError, PlModal, PlPageHeader, showToast } from '@/ui/index.js';
import { ROLE_TONES } from './presentation.js';

const data = ref({ areas: [], permissions: [], roles: [] });
const loading = ref(true);
const loadError = ref('');
const pending = reactive(new Set());

const newOpen = ref(false);
const draft = reactive({ name: '', fromRoleId: '', description: '' });
const errors = reactive({});
const formError = ref('');
const busy = ref(false);

const deleting = ref(null);

const roles = computed(() => data.value.roles ?? []);

/** Permissions grouped by area, in the server's area order; empty areas are left out. */
const areas = computed(() =>
  (data.value.areas ?? [])
    .map((name) => ({ name, permissions: (data.value.permissions ?? []).filter((p) => p.area === name) }))
    .filter((a) => a.permissions.length),
);

async function load() {
  loading.value = true;
  loadError.value = '';

  try {
    data.value = await api.get('/users/roles');
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function toggle(role, perm, event) {
  const granted = event.target.checked;
  const key = `${role.id}:${perm.key}`;

  pending.add(key);

  try {
    await api.put(`/users/roles/${role.id}/permissions/${encodeURIComponent(perm.key)}`, { granted });
    showToast(`${role.name} · ${perm.key} ${granted ? 'granted' : 'removed'}`);
    await load();
  } catch {
    event.target.checked = !granted;
  } finally {
    pending.delete(key);
  }
}

function openNew() {
  Object.assign(draft, { name: '', fromRoleId: roles.value.find((r) => r.name === 'Employee')?.id ?? '', description: '' });
  Object.keys(errors).forEach((k) => delete errors[k]);
  formError.value = '';
  newOpen.value = true;
}

async function create() {
  Object.keys(errors).forEach((k) => delete errors[k]);
  formError.value = '';

  if (!draft.name.trim()) {
    errors.name = 'Enter a role name.';
  }

  if (!draft.fromRoleId) {
    errors.fromRoleId = 'Choose a role to start from.';
  }

  if (Object.keys(errors).length) {
    return;
  }

  busy.value = true;

  try {
    const body = { name: draft.name.trim(), fromRoleId: draft.fromRoleId };

    if (draft.description.trim()) {
      body.description = draft.description.trim();
    }

    await api.post('/users/roles', body, { quiet: true });
    showToast(`Role created · ${body.name}`);
    newOpen.value = false;
    await load();
  } catch (e) {
    if (e instanceof ApiError && e.field && e.field in draft) {
      errors[e.field] = e.message;
    } else {
      formError.value = e.message;
    }
  } finally {
    busy.value = false;
  }
}

async function remove() {
  const role = deleting.value;

  busy.value = true;

  try {
    await api.delete(`/users/roles/${role.id}`);
    showToast(`Role deleted · ${role.name}`);
    deleting.value = null;
    await load();
  } catch {
    // the API client showed the reason (for example the role is still in use)
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.matrix .role-head {
  text-align: center;
  white-space: nowrap;
}

.role-count {
  display: block;
  text-transform: none;
  letter-spacing: 0;
  font-weight: 400;
}

.role-head .pl-btn {
  margin-top: var(--space-1);
}

.area-row th {
  background: var(--surface-sunken);
  text-align: left;
}

.perm {
  text-align: left;
  font-weight: 400;
  min-width: 240px;
}

.perm .key {
  display: block;
  font-size: 13px;
  color: var(--ink);
}

.cell {
  text-align: center;
}

.role-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-4);
}

.role-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-width: 0;
}

.role-card-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  align-items: flex-start;
}

.menu-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
