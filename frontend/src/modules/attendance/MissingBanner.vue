<template>
  <PlAlert v-if="missing.length" badge="Not uploaded">
    {{ text }}
    <template #action>
      <RouterLink
        v-if="canUpload"
        class="pl-btn pl-btn-sm pl-btn-primary"
        :to="{ path: '/imports', query: { type: 'attendance' } }"
      >
        Upload CSV
      </RouterLink>
    </template>
  </PlAlert>
</template>

<script setup>
// Not-uploaded banner (reference attMissingBanner): months of the period that HR has not uploaded yet.
import { computed } from 'vue';
import { PlAlert } from '@/ui/index.js';
import { monthLabel } from './presentation.js';

const props = defineProps({
  /** [{ month, label }] or ['2026-09'] */
  missing: { type: Array, default: () => [] },
  canUpload: { type: Boolean, default: false },
});

const text = computed(() => {
  const names = props.missing.map((m) => (typeof m === 'string' ? monthLabel(m) : m.label)).join(', ');
  const many = props.missing.length > 1;
  const lead = `${names} attendance ${many ? 'have' : 'has'} not been uploaded yet, so ${many ? 'those months show' : 'that month shows'} no record.`;

  return props.canUpload
    ? `${lead} Export the month from the HR system and upload it here.`
    : `${lead} HR uploads each month after it closes (by the 5th working day).`;
});
</script>
