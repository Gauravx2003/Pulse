<script setup>
// Weekly pulse (reference 6.2 "Weekly pulse modal", spec insight/progress-pulse): two questions answered
// with five 1–5 buttons each and an optional note; both answers are required ("Answer both questions.").
// POST /progress/pulse saves this week's answer; the note is shown as text and never logged.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { formatDate } from '@/ui/format.js';
import { PlField, PlFormError, PlModal, showToast } from '@/ui/index.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  /** Monday of the week, "2026-09-28". */
  week: { type: String, default: '' },
});

const emit = defineEmits(['close', 'saved']);

const QUESTIONS = [
  { key: 'mood', label: 'How was your week?', hint: '1 rough · 5 great' },
  { key: 'workload', label: 'How heavy was your workload?', hint: '1 too light · 3 right · 5 too much' },
];

const answers = ref({ mood: null, workload: null });
const note = ref('');
const error = ref('');
const saving = ref(false);

watch(
  () => props.open,
  (open) => {
    if (open) {
      answers.value = { mood: null, workload: null };
      note.value = '';
      error.value = '';
    }
  },
);

async function send() {
  if (answers.value.mood === null || answers.value.workload === null) {
    error.value = 'Answer both questions.';
    return;
  }

  saving.value = true;
  error.value = '';

  try {
    await api.post('/progress/pulse', { mood: answers.value.mood, workload: answers.value.workload, note: note.value.trim() }, { quiet: true });

    showToast('Thanks — your pulse is in');

    emit('saved');
  } catch (e) {
    error.value = e.message || 'Your answer could not be saved.';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <PlModal :open="open" :title="`Weekly pulse · week of ${formatDate(week)}`" @close="emit('close')">
    <p class="pl-caption">Takes 20 seconds. Your manager sees it and follows up in your next 1:1.</p>
    <PlFormError v-if="error" :message="error" />
    <fieldset v-for="q in QUESTIONS" :key="q.key" class="question">
      <legend class="pl-h3">{{ q.label }}</legend>
      <div class="scores" role="group" :aria-label="q.label">
        <button
          v-for="n in 5"
          :key="n"
          type="button"
          class="pl-btn score"
          :class="{ 'pl-btn-primary': answers[q.key] === n }"
          :aria-pressed="answers[q.key] === n"
          :aria-label="`${q.label} ${n}`"
          @click="answers[q.key] = n"
        >{{ n }}</button>
      </div>
      <span class="pl-caption">{{ q.hint }}</span>
    </fieldset>
    <PlField v-model="note" type="textarea" label="Anything blocking you or worth celebrating?" :maxlength="500" hint="Optional" />
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="saving" @click="send">Send</button>
    </template>
  </PlModal>
</template>

<style scoped>
.question {
  border: 0;
  margin: var(--space-3) 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.scores {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.score {
  min-width: 44px;
}
</style>
