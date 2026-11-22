<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Settings" title="Rules & thresholds" caption="Changes apply instantly across every screen.">
      <template #actions>
        <button type="button" class="pl-btn" :disabled="loading || !groups.length" @click="askReset">Reset defaults</button>
      </template>
    </PlPageHeader>

    <PlAlert v-if="loadError" badge="Error" tone="danger">
      {{ loadError }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>

    <PlCard v-if="loading" title="Rules" loading />

    <div v-else class="rule-cards">
      <PlCard v-for="group in groups" :key="group.key" :title="group.label" :subtitle="changedLine(group)">
        <template v-if="group.totalOf || group.key === 'pr_standard'" #actions>
          <Badge
            v-if="group.totalOf"
            :text="`Total ${formatTotal(group.total)}%`"
            :tone="isHundred(group.total) ? 'ok' : 'danger'"
          />
          <button v-if="group.key === 'pr_standard'" type="button" class="pl-btn pl-btn-sm" @click="previewOpen = true">
            Preview standard
          </button>
        </template>
        <PlAlert v-if="group.key === 'index_weights' && !isHundred(group.total)" badge="Check">
          Weights total {{ formatTotal(group.total) }}%, not 100%. The index is normalized until they do.
        </PlAlert>
        <RuleRow
          v-for="field in group.fields"
          :key="`${field.path}:${revision}`"
          :field="field"
          :disabled="saving.has(group.key)"
          :error="errors[field.path] ?? ''"
          @save="(value) => save(group, field, value)"
        />
      </PlCard>
    </div>

    <PlConfirm
      :open="resetOpen"
      title="Reset to defaults?"
      :message="resetMessage"
      confirm-label="Reset defaults"
      :busy="resetBusy"
      @cancel="resetOpen = false"
      @confirm="reset"
    />

    <PlModal :open="previewOpen" title="Pull request standard" size="lg" @close="previewOpen = false">
      <ul class="checks">
        <li v-for="check in prChecks" :key="check.label">
          <Badge :text="check.on ? 'On' : 'Off'" :tone="check.on ? 'ok' : ''" />
          <span>{{ check.label }}</span>
        </li>
      </ul>
      <PlField v-model="sampleTitle" label="Try a PR title" placeholder="PLAT-142 feat(api): add the sync status endpoint" />
      <p class="pl-caption" role="status">
        <template v-if="!sampleTitle">Type a title to check it against the title pattern.</template>
        <template v-else-if="titleOk === null">The title pattern can't be checked in this browser.</template>
        <template v-else-if="titleOk">Matches the title pattern.</template>
        <template v-else>Does not match the title pattern.</template>
      </p>
      <template #actions>
        <button type="button" class="pl-btn" @click="previewOpen = false">Close</button>
      </template>
    </PlModal>
  </div>
</template>

<script setup>
// Rules & thresholds (reference 11.4 "Screen detail — Rules & thresholds"; spec platform/rules-
// thresholds). Every card and its ranges come from GET /rules/groups; each change is saved at once
// (PUT /rules/groups/:group with the rules version) and the server validates ranges, the safe regex
// and weight totals. Appraisal standards are edited on the Appraisal screen, so they are left out here.
import { computed, onMounted, reactive, ref } from 'vue';
import { ApiError, api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { formatDateTime } from '@/ui/format.js';
import { PlAlert, PlCard, PlConfirm, PlField, PlModal, PlPageHeader, showToast } from '@/ui/index.js';
import RuleRow from './RuleRow.vue';

/** Set in Appraisal by appraisal.approve holders (reference 11.4). */
const ELSEWHERE = new Set(['appraisal_standards']);

const groups = ref([]);
const version = ref(null);
const loading = ref(true);
const loadError = ref('');
const saving = reactive(new Set());
const errors = reactive({});
const revision = ref(0);

const resetOpen = ref(false);
const resetPreview = ref(null);
const resetBusy = ref(false);

const previewOpen = ref(false);
const sampleTitle = ref('');

const resetMessage = computed(() => {
  const n = resetPreview.value?.changes;

  if (n === undefined) {
    return 'Checking what would change…';
  }

  if (n === 0) {
    return 'Every rule already has its default value.';
  }

  return `${n} rule${n === 1 ? '' : 's'} will go back to the defaults of section 12. The change is recorded in the audit log.`;
});

const prGroup = computed(() => groups.value.find((g) => g.key === 'pr_standard'));

function prValue(path) {
  return prGroup.value?.fields.find((f) => f.path === path)?.value;
}

const prChecks = computed(() => [
  { label: 'Title matches the pattern', on: Boolean(prValue('prTitle')) },
  { label: 'Linked work item', on: Boolean(prValue('prItem')) },
  { label: 'Description template', on: Boolean(prValue('prDesc')) },
  { label: 'Blocking threads resolved before merge', on: Boolean(prValue('prThreads')) },
  { label: `At most ${prValue('prMaxLines') ?? '—'} lines changed`, on: true },
  { label: `At least ${prValue('prMinAppr') ?? '—'} approvals`, on: true },
  { label: `First review within ${prValue('prSla') ?? '—'} h`, on: true },
]);

/** The server validated the pattern as a safe regex; null when this browser cannot compile it. */
const titleOk = computed(() => {
  try {
    return new RegExp(prValue('prTitle') ?? '').test(sampleTitle.value);
  } catch {
    return null;
  }
});

function isHundred(total) {
  return Math.abs(Number(total) - 100) < 1e-9;
}

function formatTotal(total) {
  const n = Number(total);

  return Number.isFinite(n) ? String(Math.round(n * 10) / 10) : '—';
}

function changedLine(group) {
  const last = group.lastChanged;

  return last ? `Last changed by ${last.actor?.name ?? 'system'} · ${formatDateTime(last.at)}` : 'Defaults of section 12';
}

async function load() {
  loading.value = true;
  loadError.value = '';

  try {
    const res = await api.get('/rules/groups');

    version.value = res.version;
    groups.value = (res.groups ?? []).filter((g) => !ELSEWHERE.has(g.key));
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function save(group, field, value) {
  delete errors[field.path];
  saving.add(group.key);

  try {
    const view = await api.put(
      `/rules/groups/${group.key}`,
      { values: { [field.path]: value }, version: version.value },
      { quiet: true },
    );

    version.value = view.version ?? version.value;

    const i = groups.value.findIndex((g) => g.key === group.key);

    if (i >= 0) {
      groups.value[i] = { ...view, key: view.key ?? group.key };
    }

    showToast(`${field.label} · saved`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      showToast('Someone else changed the rules. The latest values are shown.');
      await load();
    } else {
      errors[field.path] = e.message;
      // remount the rows so each control shows its stored value again
      revision.value += 1;
    }
  } finally {
    saving.delete(group.key);
  }
}

async function askReset() {
  resetPreview.value = null;
  resetOpen.value = true;

  try {
    resetPreview.value = await api.get('/rules/reset');
  } catch {
    resetOpen.value = false;
  }
}

async function reset() {
  resetBusy.value = true;

  try {
    const res = await api.post('/rules/reset', { version: resetPreview.value?.version ?? version.value });

    showToast(res.changes ? `Defaults restored · ${res.changes} rule${res.changes === 1 ? '' : 's'} changed` : 'Already at defaults');
    resetOpen.value = false;
    await load();
  } catch {
    // the API client showed the error
  } finally {
    resetBusy.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.rule-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 460px), 1fr));
  gap: var(--space-4);
  align-items: start;
}

.checks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.checks li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
</style>
