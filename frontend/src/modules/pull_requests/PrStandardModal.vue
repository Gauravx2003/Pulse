<script setup>
// PR standard (reference 7.3 "PR standard modal"): checked automatically on every pull request from TFS
// and GitHub, set by the admin. The title rule, the checks and their switches, and the comment
// standard come from GET /pull_requests/standard (the rules in force); Admins can edit them in Rules.
import { Badge } from '@/kit/index.js';
import { can } from '@/shell/session.js';
import { PlModal } from '@/ui/index.js';
import { LABELS } from './presentation.js';

defineProps({
  open: { type: Boolean, default: false },
  standard: { type: Object, default: null },
});

const emit = defineEmits(['close']);
</script>

<template>
  <PlModal :open="open" size="lg" title="PR standard" @close="emit('close')">
    <div v-if="standard" class="standard">
      <p class="pl-caption">Checked automatically on every pull request from TFS and GitHub · set by your admin</p>

      <section aria-labelledby="part-title">
        <h3 id="part-title" class="pl-h3">Part 1 · Title</h3>
        <p><span class="pl-mono">WORKITEM type(scope): summary</span> · feat, fix, refactor, perf, test, docs or chore · 10–72 characters, no full stop</p>
        <div class="line"><Badge text="Good" tone="ok" /><span class="pl-mono">PLAT-2231 feat(ingest): rate-limit ingestion per tenant</span></div>
        <div class="line"><Badge text="Not to standard" tone="danger" /><span class="pl-mono">fixed stuff.</span></div>
        <div class="pl-caption pattern">Regex: <span class="pl-mono">{{ standard.titlePattern }}</span></div>
      </section>

      <section aria-labelledby="part-pr">
        <h3 id="part-pr" class="pl-h3">Part 2 · The pull request</h3>
        <ul class="list">
          <li v-for="c in standard.checks" :key="c.k" class="line">
            <Badge :text="c.on ? 'On' : 'Off'" :tone="c.on ? 'ok' : ''" />
            <span>{{ c.l }}</span>
          </li>
        </ul>
        <p class="pl-caption">
          Target {{ standard.target }}% of checks passed per person per month · first review within {{ standard.firstReviewSlaHours }} h is shown, not scored
        </p>
      </section>

      <section aria-labelledby="part-comments">
        <h3 id="part-comments" class="pl-h3">Part 3 · Review comments</h3>
        <ul class="list">
          <li v-for="[label, meaning] in LABELS" :key="label" class="line"><span class="pl-mono">{{ label }}:</span><span>{{ meaning }}</span></li>
        </ul>
        <p class="pl-caption">
          Add (blocking) or (non-blocking) when it helps. issue, suggestion and todo need {{ standard.commentMin }}+ characters after the label.
          Approving a PR over {{ standard.rubberStampLines }} lines with no comment counts as a rubber-stamp.
        </p>
      </section>
    </div>
    <template #actions>
      <RouterLink v-if="can('rules.manage')" class="pl-btn" to="/rules">Edit in Rules &amp; thresholds</RouterLink>
      <button type="button" class="pl-btn pl-btn-primary" @click="emit('close')">Close</button>
    </template>
  </PlModal>
</template>

<style scoped>
.standard {
  display: grid;
  gap: var(--space-4);
}

.line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  overflow-wrap: anywhere;
}

.list {
  list-style: none;
  margin: var(--space-2) 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.pattern {
  overflow-wrap: anywhere;
}

p {
  margin: var(--space-1) 0;
}
</style>
