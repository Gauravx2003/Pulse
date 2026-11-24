<template>
  <div class="pl-page">
    <PlPageHeader
      eyebrow="Learning & onboarding"
      title="Training plans"
      caption="Week-by-week plans for new joiners and targeted development · checkpoint reviews use the same review form as 360° feedback"
    />
    <PlTabs v-if="tabs.length > 1" v-model="tab" :tabs="tabs" label="Training view" />
    <MyTrainingTab v-if="tab === 'mine' && myPerson" :person-id="myPerson" />
    <MentoringTab v-else-if="tab === 'mentor'" />
    <TraineesTab v-else-if="tab === 'trainees'" />
    <LibraryTab v-else-if="tab === 'library'" />
    <PlCard v-else title="Training" empty empty-text="Nothing to show for your role yet." />
  </div>
</template>

<script setup>
// Training & mentoring (reference pTraining; spec growth/training-mentoring). Tabs by role: My training
// (anyone with a profile), Mentoring (anyone with mentees), Trainees & assignments and Plan library
// (training.manage). Every list comes from /api/v1/training, which applies the visibility rules.
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '@/api/client.js';
import { can, session } from '@/shell/session.js';
import { PlCard, PlPageHeader, PlTabs } from '@/ui/index.js';
import LibraryTab from './LibraryTab.vue';
import MentoringTab from './MentoringTab.vue';
import MyTrainingTab from './MyTrainingTab.vue';
import TraineesTab from './TraineesTab.vue';
import './training.css';

const route = useRoute();

const myPerson = computed(() => session.me?.user?.personId ?? null);
const mentors = ref(false);

api
  .get('/training/mentoring/pending', { quiet: true })
  .then((r) => {
    mentors.value = r.kpis.mentees > 0;
  })
  .catch(() => {
    mentors.value = false;
  });

const tabs = computed(() => {
  const t = [];

  if (myPerson.value) t.push({ key: 'mine', label: 'My training' });
  if (mentors.value) t.push({ key: 'mentor', label: 'Mentoring' });
  if (can('training.manage')) t.push({ key: 'trainees', label: 'Trainees & assignments' }, { key: 'library', label: 'Plan library' });

  return t;
});

// A tab asked for in the link (?tab=mentor) wins once it is available: the Mentoring tab appears only
// after the mentees are known.
const requested = typeof route.query.tab === 'string' ? route.query.tab : '';
const tab = ref('');
let touched = false;

watch(
  tabs,
  (list) => {
    if (requested && list.some((t) => t.key === requested) && tab.value !== requested && !touched) {
      tab.value = requested;
    } else if (!list.some((t) => t.key === tab.value)) {
      tab.value = list[0]?.key ?? '';
    }
  },
  { immediate: true },
);

watch(tab, (now, before) => {
  if (before && now !== requested) {
    touched = true;
  }
});
</script>
