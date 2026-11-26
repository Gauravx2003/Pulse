<template>
  <PlModal :open="open" :title="title" size="lg" @close="emit('close')">
    <form class="pl-form" novalidate autocomplete="off" @submit.prevent="test">
      <PlField
        v-if="methods.length > 1"
        v-model="method"
        label="Authentication method"
        type="select"
        required
        :options="methods.map((m) => ({ value: m.name, label: m.name }))"
      />
      <p v-else-if="methods.length === 1" class="pl-caption">Authentication method: {{ methods[0].name }}</p>

      <div class="pl-form-grid">
        <PlField
          v-for="f in fields"
          :key="`${method}:${f.key}`"
          v-model="values[f.key]"
          :label="f.label"
          :type="inputType(f.type)"
          :required="f.required"
          :placeholder="f.placeholder"
          :hint="f.hint"
          :autocomplete="f.type === 'secret' ? 'new-password' : 'off'"
          :error="errors[f.key] ?? ''"
        />
      </div>

      <p class="pl-note">
        Secrets are stored only as the last 4 characters in this version, and the test makes no request to the tool.
      </p>

      <div v-if="result" class="test-result" role="status">
        <Badge :text="result.ok ? 'Passed' : 'Failed'" :tone="result.ok ? 'ok' : 'danger'" />
        <div>
          <div>{{ result.message }}</div>
          <div v-if="result.scopes?.length" class="pl-caption">
            Scopes{{ result.simulated ? ' (simulated)' : '' }}: {{ result.scopes.join(', ') }}
          </div>
          <div v-if="result.scopeWarning" class="pl-caption warn">{{ result.scopeWarning }}</div>
        </div>
      </div>
      <PlFormError :message="formError" />
    </form>
    <template #actions>
      <button type="button" class="pl-btn" :disabled="busy" @click="test">Test connection</button>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="!result?.ok || busy" @click="save">
        {{ connected ? 'Save new credentials' : 'Save & connect' }}
      </button>
    </template>
  </PlModal>
</template>

<script setup>
// Seeded (Phase 1) tools. Connect / Change credentials (reference 11.3 and section 5 "Connect"): the tool's auth method and
// its fields marked *, secrets as password inputs. Test connection checks the form on the server
// (simulated in Phase 1, no outbound call) and shows the result inline; Save is enabled only after a
// pass and is disabled again by any edit.
import { computed, reactive, ref, watch } from 'vue';
import { ApiError, api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { PlField, PlFormError, PlModal, showToast } from '@/ui/index.js';
import { inputType } from './presentation.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  /** GET /integrations/tools/:tool */
  tool: { type: Object, default: null },
});

const emit = defineEmits(['close', 'saved']);

const method = ref('');
const values = reactive({});
const errors = reactive({});
const result = ref(null);
const formError = ref('');
const busy = ref(false);

const methods = computed(() => props.tool?.methods ?? []);
const fields = computed(() => methods.value.find((m) => m.name === method.value)?.fields ?? []);
const connected = computed(() => Boolean(props.tool?.tile?.connected));
const title = computed(() => `${connected.value ? 'Change credentials' : 'Connect'} · ${props.tool?.tile?.name ?? ''}`);

function clear(obj) {
  Object.keys(obj).forEach((k) => delete obj[k]);
}

function reset() {
  method.value = props.tool?.method ?? methods.value[0]?.name ?? '';
  fillValues();
  result.value = null;
  formError.value = '';
}

/** Non-secret stored values are prefilled; secrets always start blank. */
function fillValues() {
  clear(values);
  clear(errors);

  const stored = props.tool?.method === method.value ? props.tool?.credentials ?? {} : {};

  fields.value.forEach((f) => {
    values[f.key] = f.type === 'secret' || f.type === 'textarea' ? '' : stored[f.key] ?? '';
  });
}

watch(
  () => props.open,
  (open) => open && reset(),
  { immediate: true },
);

watch(method, () => {
  if (props.open) {
    fillValues();
    result.value = null;
  }
});

// any edit invalidates an earlier pass
watch(
  values,
  () => {
    result.value = null;
  },
  { deep: true },
);

function validate() {
  clear(errors);

  fields.value.forEach((f) => {
    if (f.required && !String(values[f.key] ?? '').trim()) {
      errors[f.key] = `${f.label} is required.`;
    }
  });

  return Object.keys(errors).length === 0;
}

function body() {
  const out = {};

  fields.value.forEach((f) => {
    const v = String(values[f.key] ?? '').trim();

    if (v) {
      out[f.key] = v;
    }
  });

  return { method: method.value, values: out };
}

function applyError(e) {
  const key = e instanceof ApiError && e.field?.startsWith('values.') ? e.field.slice('values.'.length) : null;

  if (key && fields.value.some((f) => f.key === key)) {
    errors[key] = e.message;
  }

  return key;
}

async function test() {
  formError.value = '';

  if (!validate()) {
    return;
  }

  busy.value = true;

  try {
    const res = await api.post(`/integrations/tools/${props.tool.tile.id}/test`, body(), { quiet: true });

    result.value = res;
  } catch (e) {
    applyError(e);
    result.value = { ok: false, message: e.message };
  } finally {
    busy.value = false;
  }
}

async function save() {
  busy.value = true;

  try {
    await api.put(`/integrations/tools/${props.tool.tile.id}/credentials`, body(), { quiet: true });
    showToast(`${connected.value ? 'Credentials changed' : 'Connected'} · ${props.tool.tile.name}`);
    emit('saved');
  } catch (e) {
    if (!applyError(e)) {
      formError.value = e.message;
    }

    result.value = null;
  } finally {
    busy.value = false;
  }
}
</script>

<style scoped>
.test-result {
  display: flex;
  gap: var(--space-3);
  align-items: flex-start;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.warn {
  color: var(--accent-ink);
}
</style>
