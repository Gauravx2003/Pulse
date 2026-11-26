<template>
  <PlModal :open="open" :title="title" size="lg" @close="emit('close')">
    <form class="pl-form" novalidate autocomplete="off" @submit.prevent="test">
      <LiveFields
        v-model:secret="secret"
        :schema="connection?.configSchema"
        :secret-spec="connection?.secretSpec"
        :values="values"
        :errors="errors"
        :stored-last4="connection?.connection?.last4 ?? ''"
        @set="setValue"
      />

      <p class="pl-note">
        Test connection signs in to the tool with these values and reads only. Save stays off until the test passes, and
        the secret is stored encrypted, never shown again.
      </p>

      <div v-if="result" class="test-result" role="status" data-test="live-test-result">
        <div class="result-head">
          <Badge :text="result.ok ? 'Passed' : `Failed${failCode ? ` · ${failCode}` : ''}`" :tone="result.ok ? 'ok' : 'danger'" />
          <span v-if="stale" class="pl-caption">Values changed since this test. Run Test connection again to save.</span>
        </div>

        <dl class="facts">
          <template v-if="account">
            <dt>Account</dt>
            <dd>{{ account }}</dd>
          </template>
          <template v-if="result.ok || result.scopes?.length || result.scopeNote">
            <dt>Scopes</dt>
            <dd>
              <span v-if="result.scopes?.length" class="pl-mono">{{ result.scopes.join(', ') }}</span>
              <span v-else-if="!result.scopeNote">None reported</span>
              <div v-if="result.scopeNote" class="pl-caption">{{ result.scopeNote }}</div>
            </dd>
          </template>
          <template v-if="result.ok">
            <dt>Expiry</dt>
            <dd>{{ result.expiresAt ? `Expires on ${formatDateWithYear(result.expiresAt)}` : 'No expiry reported' }}</dd>
          </template>
          <template v-if="!result.ok && result.hint">
            <dt>Check</dt>
            <dd>{{ result.hint }}</dd>
          </template>
        </dl>

        <ul v-if="warnings.length" class="warnings" aria-label="Warnings">
          <li v-for="(w, i) in warnings" :key="i"><Badge text="Warning" tone="accent" /> {{ w }}</li>
        </ul>
      </div>

      <section v-if="proposed.length" class="proposals" aria-labelledby="proposals-title">
        <h3 id="proposals-title" class="pl-field-label">Proposed by the test · confirm to use</h3>
        <div v-for="p in proposed" :key="p.field" class="proposal">
          <span>{{ p.label }}</span>
          <span class="choices">
            <button
              v-for="c in p.candidates.slice(0, 5)"
              :key="c.value"
              type="button"
              class="pl-btn pl-btn-sm"
              :aria-pressed="String(values[p.field] ?? '') === c.value ? 'true' : 'false'"
              :aria-label="`Use ${c.label} for ${p.label}`"
              @click="setValue(p.field, c.value)"
            >
              {{ String(values[p.field] ?? '') === c.value ? `✓ ${c.label}` : `Use ${c.label}` }}
            </button>
          </span>
        </div>
      </section>

      <PlFormError :message="formError" />
    </form>
    <template #actions>
      <button type="button" class="pl-btn" :disabled="Boolean(busy)" @click="test">{{ busy === 'test' ? 'Testing…' : 'Test connection' }}</button>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="!canSave || Boolean(busy)" @click="save">
        {{ connected ? 'Save new credentials' : 'Save & connect' }}
      </button>
    </template>
  </PlModal>
</template>

<script setup>
// Connect / Change credentials of a live connector (task 24.7, spec admin/integrations-connectors
// "Generic live connector screens" and "Real connection test"; secrets-credentials "Save bound to a
// passing test"). The form comes from the connector's configSchema and secretSpec only (LiveFields),
// so a new connector needs no change here. Test connection is the real test: it shows the account,
// scopes or a scope note, the expiry, warnings and the fields it proposes for the Admin to confirm.
// Save is enabled only after a pass with the current values; any edit disables it again, matching
// the server's single-use proof bound to the values. The secret is never pre-filled.
import { computed, reactive, ref, watch } from 'vue';
import { ApiError, api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { formatDateWithYear } from '@/ui/format.js';
import { PlFormError, PlModal, showToast } from '@/ui/index.js';
import LiveFields from './LiveFields.vue';
import { accountText, configBody, initialValues, proposals, validate, warningText } from './liveForm.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  /** the tool's tile from GET /integrations/tools */
  tile: { type: Object, default: null },
  /** GET /integrations/:tool/connection: {tool, configSchema, secretSpec, requiredScopes, connection} */
  connection: { type: Object, default: null },
});

const emit = defineEmits(['close', 'saved']);

const values = reactive({});
const errors = reactive({});
const secret = ref('');
const result = ref(null);
const stale = ref(false);
const proposed = ref([]);
const formError = ref('');
/** '' when idle, else 'test' or 'save' */
const busy = ref('');

const connected = computed(() => Boolean(props.tile?.connected));
const title = computed(() => `${connected.value ? 'Change credentials' : 'Connect'} · ${props.tile?.name ?? props.connection?.tool ?? ''}`);
const canSave = computed(() => Boolean(result.value?.ok && result.value?.proof) && !stale.value);
const account = computed(() => accountText(result.value?.account));
const warnings = computed(() => (result.value?.warnings ?? []).map(warningText).filter(Boolean));
const failCode = computed(() => (result.value?.ok ? '' : (result.value?.status ?? result.value?.code ?? '')));

function clear(obj) {
  Object.keys(obj).forEach((k) => delete obj[k]);
}

function reset() {
  clear(values);
  clear(errors);
  Object.assign(values, initialValues(props.connection?.configSchema, props.connection?.connection?.config));
  secret.value = '';
  result.value = null;
  stale.value = false;
  proposed.value = [];
  formError.value = '';
}

watch(
  () => [props.open, props.connection],
  ([open]) => open && reset(),
  { immediate: true },
);

// the secret never outlives the modal
watch(
  () => props.open,
  (open) => {
    if (!open) {
      secret.value = '';
    }
  },
);

function setValue(name, value) {
  values[name] = value;
}

// any edit after a test invalidates its proof
watch([values, secret], () => {
  if (result.value) {
    stale.value = true;
  }
});

function body() {
  return { config: configBody(props.connection?.configSchema, values), secret: secret.value };
}

/** Puts a 400 on its field (`config.<name>` or `secret`); false when the error names no known field. */
function applyError(e) {
  if (!(e instanceof ApiError) || !e.field) {
    return false;
  }

  if (e.field === 'secret' || e.field.startsWith('config.')) {
    errors[e.field] = e.message;

    return true;
  }

  return false;
}

async function test() {
  formError.value = '';
  clear(errors);

  Object.assign(errors, validate(props.connection?.configSchema, props.connection?.secretSpec, values, secret.value));

  if (Object.keys(errors).length) {
    return;
  }

  busy.value = 'test';

  try {
    const res = await api.post(`/integrations/${encodeURIComponent(props.connection.tool)}/test`, body(), { quiet: true });

    result.value = res;
    stale.value = false;

    const next = proposals(props.connection?.configSchema, res.fields);

    if (next.length) {
      proposed.value = next;
    }
  } catch (e) {
    result.value = null;

    if (!applyError(e)) {
      formError.value = e.message;
    }
  } finally {
    busy.value = '';
  }
}

async function save() {
  formError.value = '';
  busy.value = 'save';

  try {
    const saved = await api.put(
      `/integrations/${encodeURIComponent(props.connection.tool)}/connection`,
      { ...body(), proof: result.value.proof },
      { quiet: true },
    );
    secret.value = '';
    // a changed config clears the sync cursor: say so, since that sync takes as long as the first one
    const full = saved?.fullSyncNext ? ' · the next sync reads everything again' : '';
    showToast(`${connected.value ? 'Credentials changed' : 'Connected'} · ${props.tile?.name ?? props.connection.tool}${full}`);
    emit('saved');
  } catch (e) {
    // the proof is used up either way: test again
    result.value = null;

    if (!applyError(e)) {
      formError.value = e.message;
    }
  } finally {
    busy.value = '';
  }
}
</script>

<style scoped>
.test-result {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.result-head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  align-items: center;
}

.facts {
  display: grid;
  grid-template-columns: minmax(80px, max-content) minmax(0, 1fr);
  gap: var(--space-1) var(--space-4);
  margin: 0;
}

.facts dt {
  color: var(--ink-muted);
  font-size: 13px;
}

.facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.warnings {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: 13px;
}

.proposals {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.proposals h3 {
  margin: 0;
}

.proposal {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  align-items: center;
  font-size: 14px;
}

.choices {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}
</style>
