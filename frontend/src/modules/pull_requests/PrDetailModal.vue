<script setup>
// PR detail (reference 7.3 "PR detail modal"): source, number, repo, status, title; author, dates, size,
// first review, work item; "PR standard · n%" with Pass / Fail / Pending and the reason of each failure;
// each reviewer with their vote and every comment with its class, status and a "Try: …" rewrite; the
// rubber-stamp flag; the link to TFS or GitHub.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlLoading, PlModal } from '@/ui/index.js';
import { num, val } from '../work/presentation.js';
import { classTone, STATE_LABELS, STATE_TONES } from './presentation.js';

const props = defineProps({
  prId: { type: String, default: null },
});

const emit = defineEmits(['close']);

const data = ref(null);
const error = ref('');

watch(
  () => props.prId,
  async (id) => {
    data.value = null;
    error.value = '';

    if (!id) {
      return;
    }

    try {
      data.value = await api.get(`/pull_requests/prs/${id}`);
    } catch (e) {
      error.value = e.status === 404 ? 'This pull request was not found.' : e.message || 'The pull request could not be loaded.';
    }
  },
  { immediate: true },
);
</script>

<template>
  <PlModal :open="!!prId" size="lg" :title="data ? `${data.pr.number} · ${data.pr.title}` : 'Pull request'" @close="emit('close')">
    <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
    <PlLoading v-else-if="!data" />
    <div v-else class="detail">
      <div class="line">
        <Badge :text="data.pr.tool" :tone="data.pr.tool === 'GitHub' ? 'info' : 'brand'" />
        <span class="pl-mono">{{ data.pr.number }}</span>
        <span class="pl-caption">{{ data.pr.repo }}</span>
        <Badge :text="data.pr.status" :tone="data.pr.status === 'Completed' ? 'ok' : data.pr.status === 'Active' ? 'info' : ''" />
        <Badge v-if="data.rubberStamp" text="Rubber-stamp" tone="accent" />
      </div>
      <div class="pl-caption">
        {{ data.pr.authorName ?? '—' }} · created {{ formatDate(val(data.pr.openedAt)) }}
        <template v-if="val(data.pr.mergedAt)"> · merged {{ formatDate(val(data.pr.mergedAt)) }}</template>
        · {{ num(data.pr.linesChanged, 0) }} lines in {{ num(data.pr.filesChanged, 0) }} files
        · first review after {{ num(data.pr.firstReviewHours) }} h · work item {{ val(data.pr.workItemKey) ?? 'none' }}
      </div>
      <div v-if="val(data.suggestedTitle)" class="pl-caption">Try: <span class="pl-mono">{{ data.suggestedTitle }}</span></div>

      <section aria-labelledby="pr-standard">
        <h3 id="pr-standard" class="pl-h3">PR standard · {{ Math.round((val(data.score) ?? 0) * 100) }}%</h3>
        <ul class="list">
          <li v-for="c in data.checks" :key="c.k" class="line">
            <Badge :text="STATE_LABELS[c.state]" :tone="STATE_TONES[c.state]" />
            <span>{{ c.l }}</span>
            <span v-if="c.state === 'fail' && val(c.why)" class="pl-caption">— {{ c.why }}</span>
          </li>
        </ul>
      </section>

      <section aria-labelledby="pr-reviews">
        <h3 id="pr-reviews" class="pl-h3">Reviewers and comments</h3>
        <p v-if="!data.reviews.length" class="pl-caption">No reviews yet.</p>
        <ul class="list">
          <li v-for="r in data.reviews" :key="r.reviewerId" class="review">
            <div class="line"><b>{{ r.reviewerName ?? '—' }}</b><Badge :text="r.vote ?? 'No vote'" :tone="String(r.vote).startsWith('Approved') ? 'ok' : ''" /></div>
            <p v-if="!r.comments.length" class="pl-caption">No comments.</p>
            <div v-for="(c, i) in r.comments" :key="i" class="comment">
              <div class="line">
                <Badge :text="c.class" :tone="classTone(c.class)" />
                <span class="pl-caption">{{ c.isResolved ? 'Resolved' : 'Open' }}</span>
              </div>
              <div>{{ c.body }}</div>
              <div v-if="val(c.rewrite)" class="pl-caption">Try: {{ c.rewrite }}</div>
            </div>
          </li>
        </ul>
      </section>
    </div>
    <template #actions>
      <a v-if="data && val(data.pr.url)" class="pl-btn" :href="data.pr.url" target="_blank" rel="noopener noreferrer">
        Open in {{ data.pr.tool }}
      </a>
      <button type="button" class="pl-btn pl-btn-primary" @click="emit('close')">Close</button>
    </template>
  </PlModal>
</template>

<style scoped>
.detail {
  display: grid;
  gap: var(--space-3);
}

.line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.list {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.review {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  border-top: 1px solid var(--border);
}

.comment {
  display: grid;
  gap: var(--space-1);
  padding-left: var(--space-3);
  overflow-wrap: anywhere;
}
</style>
