<script setup>
// Progress (reference 6.2 "Screen detail — Progress", spec insight/progress-pulse): the identity header
// (Start 1:1 for managers, Weekly pulse for the person while this week's pulse is open); five KPIs; next
// steps and achievements; the performance index trend and utilization by sprint; culture values last
// quarter → this one and goals, PRs and training; the weekly pulse (private to the person, the manager and
// HR) and team wins. An Employee sees exactly what their manager sees about them (spec "Employee parity").
// GET /progress/people/:id, /progress/people/:id/pulse and /progress/wins.
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "@/api/client.js";
import { Badge, StatTile, UtilizationMeter } from "@/kit/index.js";
import { loadPeople, people } from "@/shell/person.js";
import { formatDate } from "@/ui/format.js";
import {
  PlAlert,
  PlCard,
  PlEmpty,
  PlKpiRow,
  PlLoading,
  PlPageHeader,
} from "@/ui/index.js";
import { openTarget } from "../action_center/targets.js";
import { STATUS_TONES } from "../okrs/presentation.js";
import { band, num, val } from "../work/presentation.js";
import { useViewedPerson } from "../work/useViewedPerson.js";
import PulseModal from "./PulseModal.vue";
import WinsList from "./WinsList.vue";

const STAGE_TONES = {
  Completed: "ok",
  Active: "brand",
  "In progress": "brand",
  Paused: "accent",
  "Not completed": "danger",
};

const route = useRoute();
const router = useRouter();
const { id: personId, selfId } = useViewedPerson();

const data = ref(null);
const error = ref("");
const answers = ref(null);
const answersError = ref("");
const wins = ref(null);
const pulseOpen = ref(false);

onMounted(() => {
  loadPeople().catch((e) => console.error("GET /people failed", e));

  api
    .get("/progress/wins")
    .then((body) => {
      wins.value = body.items ?? [];
    })
    .catch((e) => console.error("GET /progress/wins failed", e));
});

let request = 0;

watch(personId, load, { immediate: true });

async function load() {
  if (!personId.value) {
    return;
  }

  const mine = ++request;

  error.value = "";
  answersError.value = "";

  try {
    const body = await api.get(`/progress/people/${personId.value}`);

    if (mine === request) {
      data.value = body;

      if (route.query.pulse === "open" && body.can?.pulse && body.pulse?.open) {
        pulseOpen.value = true;
      }
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || "Progress could not be loaded.";
    }
  }

  try {
    const body = await api.get(`/progress/people/${personId.value}/pulse`, {
      quiet: true,
    });

    if (mine === request) {
      answers.value = body.items ?? [];
    }
  } catch (e) {
    if (mine === request) {
      answers.value = null;
      answersError.value =
        e.status === 403 || e.status === 404
          ? "Private to the person, their manager and HR."
          : e.message;
    }
  }
}

const person = computed(
  () => (people.value ?? []).find((p) => p.id === personId.value) ?? null,
);
const self = computed(
  () => !!personId.value && personId.value === selfId.value,
);
const firstName = computed(
  () => (person.value?.name ?? "").split(" ")[0] || "them",
);
const caption = computed(() =>
  [person.value?.title, person.value?.department].filter(Boolean).join(" · "),
);

const kpis = computed(() => data.value?.kpis ?? {});
const trends = computed(() => data.value?.trends ?? {});
const steps = computed(() => data.value?.nextSteps ?? []);
const achievements = computed(() => data.value?.achievements ?? []);

const previousQuarter = computed(
  () => (trends.value.index ?? [])[2]?.quarter ?? "last quarter",
);

const indexFoot = computed(() => {
  const change = val(kpis.value.indexChange);

  return change === null
    ? "No earlier quarter"
    : `${change >= 0 ? "+" : ""}${change.toFixed(2)} vs ${previousQuarter.value}`;
});

const trainingScore = computed(() =>
  val(kpis.value.training?.score ?? kpis.value.training),
);
const pulse = computed(() =>
  val(kpis.value.pulse) === null ? null : kpis.value.pulse,
);

// ----- performance index chart (4 quarters)
const W = 520;
const H = 150;

const points = computed(() =>
  (trends.value.index ?? []).map((q, i) => ({
    label: q.quarter,
    value: val(q.index),
    x: 40 + (i * (W - 80)) / 3,
  })),
);

function y(v) {
  return H - 26 - ((Math.min(Math.max(v, 2.5), 5) - 2.5) / 2.5) * (H - 44);
}

const line = computed(() =>
  points.value
    .filter((p) => p.value !== null)
    .map((p) => `${p.x},${y(p.value)}`)
    .join(" "),
);

// ----- utilization by sprint (as on Overview)
const utilization = computed(() =>
  val(data.value?.utilization) === null ? null : data.value.utilization,
);
const utilBand = computed(() => band(utilization.value?.band));
const utilRows = computed(() =>
  (utilization.value?.sprints ?? [])
    .filter((s) => val(s.util) !== null)
    .map((s) => ({
      key: s.period,
      label: `${s.short}${s.live ? " · forecast" : ""}`,
      value: val(s.util),
      tone: band(s.band).tone,
    })),
);

// ----- culture, goals, PRs, training
const culture = computed(() => trends.value.culture ?? []);
const okrs = computed(() => trends.value.okrs ?? []);
const prByMonth = computed(() => trends.value.prByMonth ?? []);
const training = computed(() =>
  Array.isArray(trends.value.training) ? trends.value.training : [],
);

function gainText(g) {
  const v = val(g);

  return v === null ? "—" : `${v >= 0 ? "+" : ""}${v.toFixed(1)}`;
}

function fill(tone) {
  return tone && tone !== "ok" ? `is-${tone}` : "";
}

function open(step) {
  if (step.target?.module === "progress" && step.target?.tab === "pulse") {
    pulseOpen.value = true;
    return;
  }

  openTarget(router, step.target);
}

function saved() {
  pulseOpen.value = false;
  load();
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader
      eyebrow="Progress"
      :title="person?.name ?? (self ? 'My progress' : 'Progress')"
      :caption="caption"
    >
      <template #actions>
        <button
          v-if="data?.can?.pulse && data?.pulse?.open"
          type="button"
          class="pl-btn pl-btn-primary"
          @click="pulseOpen = true"
        >
          Weekly pulse
        </button>
        <RouterLink
          v-if="data?.can?.startOneOnOne"
          class="pl-btn"
          :to="{ path: '/reviews', query: { tab: 'oneonone' } }"
          >Start 1:1</RouterLink
        >
      </template>
    </PlPageHeader>

    <PlAlert v-if="error" badge="Error" tone="danger">
      {{ error }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">
          Retry
        </button>
      </template>
    </PlAlert>

    <PlLoading v-if="!data && !error" />

    <template v-if="data">
      <PlKpiRow :columns="5" label="Progress summary">
        <StatTile
          eyebrow="Performance index"
          :value="num(kpis.index, 2)"
          :unit="val(kpis.index) === null ? '' : '/ 5'"
          :foot="indexFoot"
        />
        <StatTile
          eyebrow="Achievements"
          :value="num(kpis.achievements, 0)"
          foot="this quarter"
        />
        <StatTile
          eyebrow="Next steps"
          :value="num(kpis.nextSteps, 0)"
          :badge="kpis.nextSteps ? 'See list' : 'All clear'"
          :badge-tone="kpis.nextSteps ? 'accent' : 'ok'"
        />
        <StatTile
          eyebrow="Training rating"
          :value="trainingScore === null ? '—' : num(trainingScore, 1)"
          :unit="trainingScore === null ? '' : '/ 5'"
          :foot="`${num(kpis.trainingPlans, 0)} plan${kpis.trainingPlans === 1 ? '' : 's'}`"
        />
        <StatTile
          eyebrow="Weekly pulse"
          :value="pulse ? pulse.mood : '—'"
          :unit="pulse ? '/ 5 mood' : ''"
          :foot="
            pulse
              ? `workload ${pulse.workload}/5 · ${formatDate(pulse.weekStart)}`
              : 'not yet'
          "
        />
      </PlKpiRow>

      <div class="pl-grid two">
        <PlCard
          :title="self ? 'Your next steps' : `Next steps for ${firstName}`"
          subtitle="Collected from every screen"
          :empty="!steps.length"
          empty-text="Nothing waiting — nice work."
        >
          <ul class="list">
            <li v-for="(s, i) in steps" :key="i" class="step">
              <span>{{ s.text }}</span>
              <button
                v-if="s.actionable"
                type="button"
                class="pl-btn pl-btn-sm"
                :aria-label="`Open: ${s.text}`"
                @click="open(s)"
              >
                Open
              </button>
            </li>
          </ul>
        </PlCard>

        <PlCard
          title="Achievements"
          subtitle="Earned from real data · shown on Team wins"
          :empty="!achievements.length"
          empty-text="Achievements appear here as you hit them."
        >
          <ul class="list">
            <li v-for="(a, i) in achievements" :key="i" class="achievement">
              <span class="dot" aria-hidden="true"></span>
              <div class="grow">
                <b>{{ a.title }}</b>
                <div class="pl-caption">{{ a.detail }}</div>
              </div>
              <Badge :text="a.value" tone="brand" />
            </li>
          </ul>
        </PlCard>
      </div>

      <div class="pl-grid two">
        <PlCard
          title="Performance index trend"
          subtitle="Delivery, quality, culture values, OKRs and growth"
          :empty="!line"
          empty-text="No index yet."
        >
          <div class="chart">
            <svg
              class="trend"
              :viewBox="`0 0 ${W} ${H}`"
              role="img"
              :aria-label="`Performance index by quarter: ${points.map((p) => `${p.label} ${p.value === null ? 'no value' : p.value.toFixed(1)}`).join(', ')}`"
            >
              <g v-for="v in [3, 4, 5]" :key="v">
                <line class="grid" x1="30" :x2="W - 20" :y1="y(v)" :y2="y(v)" />
                <text x="4" :y="y(v) + 4">{{ v.toFixed(1) }}</text>
              </g>
              <polyline class="line" :points="line" />
              <g v-for="p in points" :key="p.label">
                <template v-if="p.value !== null">
                  <circle class="point" :cx="p.x" :cy="y(p.value)" r="4" />
                  <text :x="p.x" :y="y(p.value) - 10" text-anchor="middle">
                    {{ p.value.toFixed(1) }}
                  </text>
                </template>
                <text :x="p.x" :y="H - 6" text-anchor="middle">
                  {{ p.label }}
                </text>
              </g>
            </svg>
          </div>
        </PlCard>

        <PlCard
          title="Utilization by sprint"
          :empty="!utilRows.length"
          empty-text="No finished sprint yet."
        >
          <template #actions>
            <Badge
              v-if="val(utilization?.overall) !== null"
              :text="`Overall ${num(utilization.overall, 0)}% · ${utilBand.label}`"
              :tone="utilBand.tone"
            />
          </template>
          <UtilizationMeter
            v-for="s in utilRows"
            :key="s.key"
            :label="s.label"
            :value="s.value"
            :band="s.tone || 'ok'"
          />
        </PlCard>
      </div>

      <div class="pl-grid two">
        <PlCard
          :title="`Culture values · ${trends.cultureFrom} → ${trends.cultureTo}`"
          subtitle="Biggest gains first"
          :empty="!culture.length"
          empty-text="No culture ratings yet."
        >
          <template #actions>
            <RouterLink class="pl-btn pl-btn-sm" to="/culture"
              >Details →</RouterLink
            >
          </template>
          <ul class="list">
            <li v-for="c in culture" :key="c.value" class="value-row">
              <span>{{ c.value }}</span>
              <span class="pl-mono pl-caption"
                >{{ num(c.before, 1) }} → {{ num(c.now, 1) }}</span
              >
              <Badge :text="gainText(c.gain)" :tone="c.tone ?? ''" />
            </li>
          </ul>
        </PlCard>

        <PlCard
          title="Goals, PRs and training"
          subtitle="Where you are against your goals"
        >
          <div class="pl-eyebrow block">OKRs · {{ trends.okrQuarter }}</div>
          <ul v-if="okrs.length" class="list">
            <li v-for="o in okrs" :key="o.id" class="bar-row">
              <span class="grow">{{ o.title }}</span>
              <span
                class="pl-meter"
                role="img"
                :aria-label="`${o.title}: ${Math.round(val(o.progress) * 100)}%`"
              >
                <span
                  class="pl-meter-fill"
                  :class="fill(STATUS_TONES[o.status])"
                  :style="{
                    width: `${Math.min(val(o.progress) ?? 0, 1) * 100}%`,
                  }"
                />
              </span>
              <span class="pl-mono"
                >{{ Math.round((val(o.progress) ?? 0) * 100) }}%</span
              >
            </li>
          </ul>
          <p v-else class="pl-caption">No OKRs this quarter</p>

          <div class="pl-eyebrow block">PR standard by month</div>
          <ul class="list">
            <li v-for="m in prByMonth" :key="m.month" class="bar-row">
              <span class="pl-caption grow">{{ m.label }}</span>
              <span v-if="val(m.comp) === null" class="pl-caption">no PRs</span>
              <span
                v-else
                class="pl-meter"
                role="img"
                :aria-label="`${m.label}: ${m.comp}% of PR checks passed`"
              >
                <span
                  class="pl-meter-fill"
                  :class="fill(m.tone)"
                  :style="{ width: `${val(m.comp)}%` }"
                />
              </span>
              <span class="pl-mono">{{
                val(m.comp) === null ? "—" : `${m.comp}%`
              }}</span>
            </li>
          </ul>

          <div class="pl-eyebrow block">Training</div>
          <ul v-if="training.length" class="list">
            <li v-for="t in training" :key="t.plan" class="bar-row">
              <span class="grow">{{ t.plan }}</span>
              <Badge :text="t.stage" :tone="STAGE_TONES[t.stage] ?? ''" />
              <span class="pl-mono">{{
                val(t.rating) === null ? "—" : num(t.rating, 1)
              }}</span>
            </li>
          </ul>
          <p v-else class="pl-caption">No training plans</p>
        </PlCard>
      </div>

      <div class="pl-grid two">
        <PlCard
          title="Weekly pulse"
          :subtitle="
            self
              ? 'Two questions every Friday; your manager sees your answers and follows up'
              : `Private to ${firstName}, you and HR`
          "
          :empty="!!answers && !answers.length"
          empty-text="No check-ins yet."
        >
          <p v-if="answersError" class="pl-caption">{{ answersError }}</p>
          <PlLoading v-else-if="!answers" />
          <ul v-else class="list">
            <li v-for="a in answers" :key="a.weekStart" class="pulse-row">
              <span class="pl-caption"
                >Week of {{ formatDate(a.weekStart) }}</span
              >
              <span class="badges">
                <Badge :text="`Mood ${a.mood}/5`" :tone="a.moodTone ?? ''" />
                <Badge
                  :text="`Workload ${a.workload}/5`"
                  :tone="a.workloadTone ?? ''"
                />
                <span v-if="a.note" class="pl-caption note"
                  >“{{ a.note }}”</span
                >
              </span>
            </li>
          </ul>
        </PlCard>

        <PlCard
          title="Team wins"
          subtitle="Everyone’s wins, visible to the whole team"
          :loading="wins === null"
        >
          <WinsList :items="wins ?? []" :limit="5" />
        </PlCard>
      </div>
    </template>

    <PlEmpty v-if="!personId" text="Pick a person to see their progress." />

    <PulseModal
      :open="pulseOpen"
      :week="data?.pulse?.week ?? ''"
      @close="pulseOpen = false"
      @saved="saved"
    />
  </div>
</template>

<style scoped>
.pl-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
}

@media (max-width: 900px) {
  .pl-grid.two {
    grid-template-columns: minmax(0, 1fr);
  }
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.step {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
  overflow-wrap: anywhere;
}

.step:last-child {
  border-bottom: 0;
}

.achievement {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: var(--space-2);
  align-items: start;
  padding: var(--space-1) 0;
}

.dot {
  width: 10px;
  height: 10px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--ok);
}

.grow {
  min-width: 0;
  overflow-wrap: anywhere;
}

.trend {
  width: 100%;
  height: auto;
}

.trend text {
  font-size: 11px;
  fill: var(--ink-muted);
}

.trend .grid {
  stroke: var(--border);
}

.trend .line {
  fill: none;
  stroke: var(--brand);
  stroke-width: 2;
}

.trend .point {
  fill: var(--brand);
}

.value-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: var(--space-3);
  align-items: center;
}

.bar-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(50px, 120px) 44px;
  gap: var(--space-2);
  align-items: center;
}

.block {
  margin: var(--space-3) 0 var(--space-2);
}

.block:first-child {
  margin-top: 0;
}

.pulse-row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: var(--space-2);
  align-items: start;
}

.badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  align-items: center;
  min-width: 0;
}

.note {
  overflow-wrap: anywhere;
}
</style>
