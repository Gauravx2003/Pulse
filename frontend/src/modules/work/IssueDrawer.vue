<script setup>
// Issue drawer (reference 7.2 "Screen detail — Work items", spec work/work-items "Issue drawer" and
// "Nudge"): status, type and SP, assignee and due date, signal badges, Original / Logged / Remaining,
// the date and time checks, and the activity timeline in which bot and planning events are greyed and
// not counted. Nudge on Slack is for managers on a stale or not-started issue; the server rate-limits it.
// The issue's source shows as a badge, and a live Jira issue links to Jira in a new tab (task 25.2).
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { statusTone } from '@/kit/tones.js';
import { can } from '@/shell/session.js';
import { formatDate, formatDateTime } from '@/ui/format.js';
import { PlAlert, PlDrawer, PlLoading, showToast } from '@/ui/index.js';
import { flags, hours, idleText, num, sourceBadge, toolUrl, val } from './presentation.js';

const props = defineProps({
  issueKey: { type: String, default: null },
});

const emit = defineEmits(['close', 'nudged']);

const data = ref(null);
const loading = ref(false);
const error = ref('');
const nudging = ref(false);

watch(
  () => props.issueKey,
  (key) => {
    data.value = null;
    error.value = '';

    if (key) {
      load(key);
    }
  },
  { immediate: true },
);

async function load(key) {
  loading.value = true;

  try {
    data.value = await api.get(`/work/issues/${encodeURIComponent(key)}`);
  } catch (e) {
    error.value = e.status === 404 ? 'This issue was not found.' : e.message || 'The issue could not be loaded.';
  } finally {
    loading.value = false;
  }
}

const issue = computed(() => data.value?.issue ?? null);
const checks = computed(() => data.value?.checks ?? {});
const nudge = computed(() => data.value?.nudge ?? null);
const source = computed(() => sourceBadge(issue.value?.source));
const url = computed(() => toolUrl(issue.value));

const checkRows = computed(() => {
  const c = checks.value;

  if (!data.value) {
    return [];
  }

  const rows = [
    {
      label: 'Idle working days',
      value: `${num(c.idleDays, 0)} d`,
      ok: !c.stale,
      text: `Stale after more than ${num(c.staleAfter, 0)} working days without assignee activity.`,
    },
  ];

  if (val(c.loggedShare) !== null) {
    rows.push({
      label: 'Logged vs elapsed',
      value: `${Math.round(val(c.loggedShare) * 100)}%`,
      ok: !c.lowLogged,
      text: `${hours(issue.value?.spentHours)} logged against ${hours(c.expectedHours)} expected at 75% of focus for ${num(
        c.workingDaysInProgress,
        0,
      )} working days in progress.${c.lowLogged ? ' Low logged time.' : ''}`,
    });
  }

  if (val(c.estimateDrift) !== null) {
    rows.push({
      label: 'Estimate drift',
      value: `× ${num(c.estimateDrift, 2)}`,
      ok: !c.estimateGrew,
      text: c.estimateGrew ? 'The estimate grew by half or more since the issue started.' : 'The estimate is where it started.',
    });
  }

  rows.push({
    label: 'Longer than estimated',
    value: c.longerThanEstimate ? 'Yes' : 'No',
    ok: !c.longerThanEstimate,
    text: 'In progress for more than twice the estimate in working days.',
  });

  if (val(c.daysLeft) !== null) {
    rows.push({
      label: 'Days of work left',
      value: `${num(c.daysLeft)} d`,
      ok: null,
      text: 'Remaining hours at the focus hours of a working day.',
    });
  }

  return rows;
});

const timeline = computed(() =>
  (data.value?.timeline ?? []).map((e) => ({
    id: e.id,
    when: formatDateTime(e.occurredAt),
    who: e.actorName ?? '—',
    text: e.summary || e.kind,
    counted: e.counted,
    bot: e.isBot,
  })),
);

const canNudge = computed(() => can('team.view', 'all.view'));

const nudgeBlocked = computed(() => {
  const n = nudge.value;

  if (!n || !n.enabled) {
    return 'Nudges are turned off in Rules & thresholds.';
  }

  if (!n.nudgeable) {
    return 'Only stale or not-started issues can be nudged.';
  }

  if (!n.channel) {
    return 'Connect Slack or Teams to send nudges.';
  }

  if (val(n.retryAfter)) {
    return 'You nudged this issue recently.';
  }

  return '';
});

async function sendNudge() {
  nudging.value = true;

  try {
    const sent = await api.post(`/work/issues/${encodeURIComponent(props.issueKey)}/nudge`, {}, { quiet: true });

    showToast(`${sent.channel === 'teams' ? 'Teams' : 'Slack'} DM sent to ${sent.recipient} about ${sent.key}`);

    emit('nudged', sent);

    await load(props.issueKey);
  } catch (e) {
    showToast(e.message || 'The nudge could not be sent.');
  } finally {
    nudging.value = false;
  }
}
</script>

<template>
  <PlDrawer :open="!!issueKey" :eyebrow="issueKey ?? ''" :title="issue?.summary ?? 'Issue'" @close="emit('close')">
    <PlLoading v-if="loading" />
    <PlAlert v-else-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
    <div v-else-if="issue" class="drawer">
      <div class="row">
        <Badge :text="issue.status" :tone="statusTone(issue.status)" />
        <span class="pl-caption">{{ issue.issueType }} · {{ num(issue.storyPoints) }} SP</span>
        <Badge :text="source.text" :tone="source.tone" />
        <a v-if="url" class="pl-btn pl-btn-sm" :href="url" target="_blank" rel="noopener noreferrer">
          Open in Jira<span class="pl-sr-only"> (opens in a new tab)</span>
        </a>
      </div>
      <div class="pl-caption">
        Assignee {{ data.assignee?.name ?? '—' }} · Due {{ formatDate(val(issue.dueOn)) }} · Last activity
        {{ idleText(checks.idleDays) }}
      </div>
      <div v-if="flags(data).length" class="row">
        <Badge v-for="f in flags(data)" :key="f.text" :text="f.text" :tone="f.tone" />
      </div>

      <div class="tiles">
        <div class="pl-stat">
          <div class="pl-eyebrow">Original</div>
          <div class="pl-stat-value">{{ hours(issue.estimateHours) }}</div>
        </div>
        <div class="pl-stat">
          <div class="pl-eyebrow">Logged</div>
          <div class="pl-stat-value">{{ hours(issue.spentHours) }}</div>
        </div>
        <div class="pl-stat">
          <div class="pl-eyebrow">Remaining</div>
          <div class="pl-stat-value">{{ hours(data.remainingHours) }}</div>
        </div>
      </div>

      <section aria-labelledby="checks-title">
        <h3 id="checks-title" class="pl-h3">Date &amp; time verification</h3>
        <ul class="checks">
          <li v-for="row in checkRows" :key="row.label">
            <div class="row">
              <b>{{ row.label }}</b>
              <span class="pl-mono">{{ row.value }}</span>
              <Badge
                :text="row.ok === null ? 'Info' : row.ok ? 'OK' : 'Check'"
                :tone="row.ok === null ? '' : row.ok ? 'ok' : 'accent'"
              />
            </div>
            <div class="pl-caption">{{ row.text }}</div>
          </li>
        </ul>
      </section>

      <section aria-labelledby="activity-title">
        <h3 id="activity-title" class="pl-h3">Activity (Jira + GitHub)</h3>
        <p v-if="!timeline.length" class="pl-caption">No activity recorded yet.</p>
        <ul v-else class="timeline">
          <li v-for="e in timeline" :key="e.id" :class="{ muted: !e.counted }">
            <div class="row">
              <span class="pl-caption">{{ e.when }}</span>
              <b>{{ e.who }}</b>
              <Badge v-if="e.bot" text="Bot" tone="" />
            </div>
            <div>{{ e.text }}</div>
            <div v-if="!e.counted" class="pl-caption">Not counted as activity</div>
          </li>
        </ul>
      </section>

      <div v-if="canNudge" class="actions">
        <button type="button" class="pl-btn pl-btn-primary" :disabled="!!nudgeBlocked || nudging" @click="sendNudge">
          Nudge on {{ nudge?.channel === 'teams' ? 'Teams' : 'Slack' }}
        </button>
        <span v-if="nudgeBlocked" class="pl-caption">{{ nudgeBlocked }}</span>
      </div>
    </div>
  </PlDrawer>
</template>

<style scoped>
.drawer {
  display: grid;
  gap: var(--space-4);
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
}

.tiles .pl-stat-value {
  font-size: 20px;
  line-height: 24px;
}

.checks,
.timeline {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: grid;
  gap: var(--space-3);
}

.timeline .muted {
  opacity: 0.55;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}
</style>
