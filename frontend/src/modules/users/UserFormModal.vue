<template>
  <PlModal :open="open" :title="isNew ? 'Add user' : `Edit ${user?.name ?? 'user'}`" @close="emit('close')">
    <form class="pl-form" novalidate @submit.prevent="save">
      <div class="pl-form-grid">
        <PlField v-model="form.name" label="Full name" required autocomplete="off" :maxlength="120" :error="errors.name" />
        <PlField
          v-model="form.email"
          label="Work email"
          type="email"
          required
          autocomplete="off"
          :maxlength="254"
          :disabled="!isNew"
          :hint="isNew ? '' : 'The email cannot be changed.'"
          :error="errors.email"
        />
        <PlField v-model="form.roleId" label="Role" type="select" required :options="roleOptions" :error="errors.roleId" />
        <PlField
          v-model="form.managerId"
          label="Reports to"
          type="select"
          placeholder="—"
          :options="managerOptions"
          :disabled="options?.managerLocked"
          :error="errors.managerId"
        />
        <PlField v-model="form.title" label="Job title" :maxlength="120" :error="errors.title" />
        <PlField v-model="form.department" label="Department" type="select" placeholder="—" :options="departmentOptions" />
        <PlField v-model="form.level" label="Level" type="select" placeholder="—" :options="levelOptions" :error="errors.level" />
        <PlField
          v-if="!isNew"
          v-model="form.status"
          label="Status"
          type="select"
          required
          :options="statusOptions"
          :error="errors.status"
        />
      </div>
      <p v-if="isNew" class="pl-note">
        No password is set here. The user gets an invite email with a link to set their own password, and becomes
        Active at first sign-in.
      </p>
      <PlFormError :message="formError" />
    </form>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="save">
        {{ isNew ? 'Add user' : 'Save' }}
      </button>
    </template>
  </PlModal>
</template>

<script setup>
// Add / Edit user modal (reference 11.1). Spec admin/users-roles "No password entered by admin": the
// Add form has no password field and the user is invited by a set-password link (this overrides the
// reference's "Temporary password"). Managers can only add Employees who report to them.
import { computed, reactive, ref, watch } from 'vue';
import { ApiError, api } from '@/api/client.js';
import { PlField, PlFormError, PlModal, showToast } from '@/ui/index.js';
import { DEPARTMENTS } from './presentation.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  /** The row to edit; null to add. */
  user: { type: Object, default: null },
  /** GET /users/options */
  options: { type: Object, default: null },
  /** The signed-in user's id (a manager's new users report to them). */
  meId: { type: String, default: '' },
});

const emit = defineEmits(['close', 'saved']);

const isNew = computed(() => !props.user);

const form = reactive({ name: '', email: '', roleId: '', managerId: '', title: '', department: '', level: '', status: '' });
const errors = reactive({});
const formError = ref('');
const busy = ref(false);

const roleOptions = computed(() => (props.options?.roles ?? []).map((r) => ({ value: r.id, label: r.name })));
const managerOptions = computed(() => (props.options?.managers ?? []).map((m) => ({ value: m.id, label: m.name })));
const levelOptions = computed(() => (props.options?.levels ?? []).map((l) => ({ value: l, label: l })));
const statusOptions = computed(() => (props.options?.statuses ?? []).map((s) => ({ value: s, label: s })));
const departmentOptions = computed(() => {
  const all = [...DEPARTMENTS];

  if (form.department && !all.includes(form.department)) {
    all.push(form.department);
  }

  return all.map((d) => ({ value: d, label: d }));
});

function employeeRoleId() {
  return (props.options?.roles ?? []).find((r) => r.name === 'Employee')?.id ?? '';
}

function reset() {
  Object.keys(errors).forEach((k) => delete errors[k]);
  formError.value = '';

  const u = props.user;

  Object.assign(form, {
    name: u?.name ?? '',
    email: u?.email ?? '',
    roleId: u?.roleId ?? employeeRoleId(),
    managerId: u?.managerId ?? (props.options?.managerLocked ? props.meId : ''),
    title: u?.title ?? '',
    department: u?.department ?? '',
    level: u?.level ?? '',
    status: u?.status ?? '',
  });
}

watch(
  () => props.open,
  (open) => open && reset(),
  { immediate: true },
);

/** Client checks with the server's messages; the server checks everything again. */
function validate() {
  Object.keys(errors).forEach((k) => delete errors[k]);

  if (!form.name.trim()) {
    errors.name = 'Enter a name.';
  }

  if (isNew.value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
    errors.email = 'Enter a valid work email.';
  }

  if (!form.roleId) {
    errors.roleId = 'Choose a role.';
  }

  return Object.keys(errors).length === 0;
}

function body() {
  const out = {
    name: form.name.trim(),
    roleId: form.roleId,
    title: form.title.trim(),
    department: form.department,
  };

  if (form.managerId && !props.options?.managerLocked) {
    out.managerId = form.managerId;
  }

  if (form.level) {
    out.level = form.level;
  }

  if (isNew.value) {
    out.email = form.email.trim();
  } else {
    out.status = form.status;
    out.version = props.user.version;
  }

  return out;
}

async function save() {
  formError.value = '';

  if (!validate()) {
    return;
  }

  busy.value = true;

  try {
    if (isNew.value) {
      await api.post('/users/items', body(), { quiet: true });
      showToast(`User added · invite sent to ${form.email.trim()}`);
    } else {
      await api.put(`/users/items/${props.user.id}`, body(), { quiet: true });
      showToast(`Saved · ${form.name.trim()}`);
    }

    emit('saved');
  } catch (e) {
    if (e instanceof ApiError && e.field && e.field in form) {
      errors[e.field] = e.message;
    } else {
      formError.value = e.message;
    }
  } finally {
    busy.value = false;
  }
}
</script>
