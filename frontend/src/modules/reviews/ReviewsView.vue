<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Growth" title="Reviews" caption="Performance index, 1:1s, kudos and 360° feedback · checkpoint reviews of training plans use the same form" />
    <PlTabs v-model="tab" :tabs="TABS" label="Reviews view" />
    <PerformanceTab v-if="tab === 'performance'" />
    <OneOnOnesTab v-else-if="tab === 'oneonone'" />
    <Review360Tab v-else />
  </div>
</template>

<script setup>
// Reviews (reference pReviews): Performance, 1:1s & feedback and 360°. Notification links open a tab and,
// for a review request, its form (/reviews?tab=360&request=<id>).
import { ref } from 'vue';
import { useRoute } from 'vue-router';
import { PlPageHeader, PlTabs } from '@/ui/index.js';
import OneOnOnesTab from './OneOnOnesTab.vue';
import PerformanceTab from './PerformanceTab.vue';
import Review360Tab from './Review360Tab.vue';
import './reviews.css';

const TABS = [
  { key: 'performance', label: 'Performance' },
  { key: 'oneonone', label: '1:1s & feedback' },
  { key: '360', label: '360° feedback' },
];

const route = useRoute();

const tab = ref(TABS.some((t) => t.key === route.query.tab) ? route.query.tab : 'oneonone');
</script>
