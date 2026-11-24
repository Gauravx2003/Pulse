<script setup>
// Road map (reference 9.2 "Screen detail — Road map", spec growth/roadmap): an identity header with Add
// milestone; the milestone timeline (agreed in 1:1s: the employee proposes, the manager confirms) beside
// promotion readiness (target and criteria Met / Open, from the calc service); and the skills matrix
// rated by the manager (current, needed for the next level, gap). Everything comes from
// GET /roadmap/people/:id, which also says who may propose and confirm; a person the user may not see
// gets the no-access card. `?person=<id>` opens a given person's road map (links from other screens).
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { loadPeriods } from '@/shell/period.js';
import { loadPeople, people } from '@/shell/person.js';
import NoAccess from '@/shell/NoAccess.vue';
import { PlAlert, PlCard, PlLoading, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import { quarterLabel, quarterOf, quartersAround } from '../okrs/presentation.js';
import { useViewedPerson } from '../work/useViewedPerson.js';
import AddMilestoneModal from './AddMilestoneModal.vue';

const SKILL_COLUMNS = [
  { key: 'skill', label: 'Skill' },
  { key: 'current', label: 'Current' },
  { key: 'needed', label: 'Needed for next level' },
  { key: 'gap', label: 'Gap' },
];

const STAGE_DOT = { done: 'is-done', now: 'is-now', next: '' };
const STAGE_TONE = { done: 'ok', now: 'brand', next: '' };
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const route = useRoute();
const { id: viewedId, selfId } = useViewedPerson();

const personId = computed(() => (typeof route.query.person === 'string' && route.query.person) || viewedId.value);

const data = ref(null);
const error = ref('');
const noAccess = ref(false);
const adding = ref(false);
const confirming = ref(null);
const today = ref(null);

onMounted(async () => {
  loadPeople().catch((e) => console.error('GET /people failed', e));

  try {
    today.value = (await loadPeriods()).today;
  } catch (e) {
    console.error('GET /periods failed', e);
  }
});

let request = 0;

watch(personId, load, { immediate: true });

async function load() {
  if (!personId.value) {
    return;
  }

  const mine = ++request;

  error.value = '';
  noAccess.value = false;

  try {
    const body = await api.get(`/roadmap/people/${encodeURIComponent(personId.value)}`, { quiet: true });

    if (mine === request) {
      data.value = body;
    }
  } catch (e) {
    if (mine !== request) {
      return;
    }

    data.value = null;

    if (e.status === 403 || e.status === 404) {
      noAccess.value = true;
    } else {
      error.value = e.message || 'The road map could not be loaded.';
    }
  }
}

const person = computed(() => (people.value ?? []).find((p) => p.id === personId.value) ?? null);
const self = computed(() => !!personId.value && personId.value === selfId.value);

const title = computed(() => person.value?.name ?? (self.value ? 'My road map' : 'Road map'));
const caption = computed(() => [person.value?.title, person.value?.department].filter(Boolean).join(' · '));

const can = computed(() => data.value?.can ?? {});
const milestones = computed(() => data.value?.milestones ?? []);

/** The readiness payload, or null when the calc service has nothing for this person. */
const readiness = computed(() => {
  const r = data.value?.readiness;

  return r && !r.notApplicable ? r : null;
});

const target = computed(() => {
  const t = readiness.value?.target;

  return t && !t.notApplicable ? t : null;
});

const track = computed(() => (target.value?.title ? `${target.value.title} track` : 'Current track'));

function monthLabel(date) {
  const m = /^(\d{4})-(\d{2})/.exec(date ?? '');

  return m ? `${MONTHS[Number(m[2]) - 1]} ${m[1]}` : '';
}

const targetText = computed(() => {
  const t = target.value;

  if (!t) {
    return '';
  }

  return [`${t.title}${t.level ? ` (${t.level})` : ''}`, monthLabel(t.targetOn)].filter(Boolean).join(' · ');
});

const criteria = computed(() => readiness.value?.criteria ?? []);

const skills = computed(() => data.value?.skills?.items ?? []);

/** The "When" the Add milestone form suggests: the quarter after today's. */
const nextQuarter = computed(() => (today.value ? quarterLabel(quartersAround(quarterOf(today.value), 0, 1)[0]) : ''));

function proposed(m) {
  return m.status === 'proposed';
}

function badgeText(m) {
  return proposed(m) ? 'Proposed' : m.note || { done: 'Done', now: 'In progress', next: 'Planned' }[m.stage] || '';
}

function badgeTone(m) {
  return proposed(m) ? 'info' : STAGE_TONE[m.stage] ?? '';
}

function progressOf(m) {
  return typeof m.progress === 'number' ? Math.min(Math.max(m.progress, 0), 100) : null;
}

async function confirm(m) {
  confirming.value = m.id;

  try {
    await api.post(`/roadmap/milestones/${m.id}/confirm`, { version: m.version }, { quiet: true });

    showToast('Milestone confirmed');

    await load();
  } catch (e) {
    showToast(e.status === 409 ? 'Someone else changed this milestone. The road map is reloaded.' : e.message || 'The milestone could not be confirmed.');

    if (e.status === 409) {
      await load();
    }
  } finally {
    confirming.value = null;
  }
}

function saved() {
  adding.value = false;
  load();
}
</script>

<template>
  <NoAccess v-if="noAccess" />
  <div v-else class="pl-page">
    <PlPageHeader eyebrow="Road map" :title="title" :caption="caption">
      <template #actions>
        <button v-if="can.propose" type="button" class="pl-btn pl-btn-primary" @click="adding = true">Add milestone</button>
      </template>
    </PlPageHeader>

    <PlAlert v-if="error" badge="Error" tone="danger">
      {{ error }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>

    <PlLoading v-if="!data && !error" />

    <template v-if="data">
      <div class="pl-grid g21">
        <PlCard
          title="Road map"
          subtitle="Agreed in 1:1s · employee proposes, manager confirms"
          :empty="!milestones.length"
          empty-text="No milestones yet. Propose the first one in your next 1:1."
        >
          <template #actions>
            <Badge :text="track" tone="brand" />
          </template>
          <ol class="pl-road">
            <li v-for="m in milestones" :key="m.id" class="pl-road-item">
              <span :class="['pl-road-dot', STAGE_DOT[m.stage]]" aria-hidden="true"></span>
              <div class="body">
                <div class="pl-h3">{{ m.title }}</div>
                <div class="pl-caption">{{ [m.when, m.value].filter(Boolean).join(' · ') }}</div>
                <div v-if="progressOf(m) !== null" class="pl-road-prog" role="img" :aria-label="`${m.title}: ${progressOf(m)}%`">
                  <i :style="{ width: `${progressOf(m)}%` }"></i>
                </div>
              </div>
              <div class="side">
                <Badge :text="badgeText(m)" :tone="badgeTone(m)" />
                <button
                  v-if="proposed(m) && can.confirm"
                  type="button"
                  class="pl-btn pl-btn-sm"
                  :disabled="confirming === m.id"
                  :aria-label="`Confirm: ${m.title}`"
                  @click="confirm(m)"
                >Confirm</button>
              </div>
            </li>
          </ol>
        </PlCard>

        <PlCard title="Promotion readiness" :empty="!readiness" empty-text="No promotion target set yet.">
          <div class="pl-eyebrow">Target</div>
          <div class="pl-h3 target">{{ targetText || 'No target level set' }}</div>
          <ul class="criteria">
            <li v-for="c in criteria" :key="c.key" class="criterion">
              <Badge :text="c.status" :tone="c.met ? 'ok' : ''" />
              <div>
                <div>{{ c.label }}</div>
                <div v-if="c.detail" class="pl-caption">{{ c.detail }}</div>
              </div>
            </li>
          </ul>
        </PlCard>
      </div>

      <PlCard title="Skills matrix" subtitle="Rated by manager against the level guide" :empty="!skills.length" empty-text="No skills matrix yet for this role.">
        <PlTable :columns="SKILL_COLUMNS" :rows="skills" row-key="id" label="Skills matrix">
          <template #cell-current="{ row }">
            <span class="pl-dots" role="img" :aria-label="`Current ${row.current} of 5`"><i v-for="d in 5" :key="d" :class="{ on: d <= row.current }"></i></span>
          </template>
          <template #cell-needed="{ row }">
            <span class="pl-dots" role="img" :aria-label="`Needed ${row.needed} of 5`"><i v-for="d in 5" :key="d" :class="{ on: d <= row.needed }"></i></span>
          </template>
          <template #cell-gap="{ row }">
            <Badge :text="row.gap" :tone="row.gap === 'Ready' ? 'ok' : 'accent'" />
          </template>
        </PlTable>
      </PlCard>
    </template>

    <AddMilestoneModal :open="adding" :person-id="personId ?? ''" :default-when="nextQuarter" @close="adding = false" @saved="saved" />
  </div>
</template>

<style scoped>
.g21 {
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  align-items: start;
}

@media (max-width: 900px) {
  .g21 {
    grid-template-columns: minmax(0, 1fr);
  }
}

.pl-road {
  list-style: none;
  margin: 0;
  padding: 0;
}

.body {
  min-width: 0;
  overflow-wrap: anywhere;
}

.side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-2);
}

.target {
  margin: var(--space-1) 0 var(--space-3);
}

.criteria {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.criterion {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--space-2);
  align-items: start;
}
</style>
