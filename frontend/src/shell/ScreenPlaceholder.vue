<template>
  <div class="pl-page">
    <PlPageHeader :eyebrow="item.group" :title="item.label" caption="This screen is being built.">
      <template v-if="kinds" #actions>
        <PlPeriodControl v-model="period.key.value" :kinds="kinds" :periods="period.periods.value" />
      </template>
    </PlPageHeader>
    <PlCard>
      <PlEmpty text="Nothing to show here yet. This screen arrives with its module." />
    </PlCard>
  </div>
</template>

<script setup>
// Stands in for a screen-map screen until its module registers a route (task 6.4), with the shared
// period control on screens that have one, so the period choice already carries between screens.
import { computed } from 'vue';
import { PlCard, PlEmpty, PlPageHeader, PlPeriodControl } from '../ui/index.js';
import { useAttendancePeriod, useSharedPeriod } from './period.js';
import { ATTENDANCE_SCREEN, PERIOD_SCREENS } from './screens.js';

const props = defineProps({
  item: { type: Object, required: true },
});

const isAttendance = props.item.key === ATTENDANCE_SCREEN;

const kinds = computed(() => (isAttendance ? ['month', 'sprint'] : PERIOD_SCREENS[props.item.key] ?? null));

const period = isAttendance ? useAttendancePeriod(['month', 'sprint']) : useSharedPeriod(kinds.value ?? ['sprint']);
</script>
