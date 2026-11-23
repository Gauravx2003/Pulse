<template>
  <PlTable :columns="columns" :rows="visits" row-key="id" :label="label" clickable :empty-text="emptyText" @row-click="emit('open', $event)">
    <template #cell-when="{ row }">{{ dates(row) }} <span class="pl-caption">· {{ row.days }} d</span></template>
    <template #cell-customer="{ row }">
      <b>{{ row.customer }}</b>
      <span class="pl-caption"> · {{ row.city }} · {{ row.purpose }}</span>
    </template>
    <template #cell-status="{ row }"><Badge :text="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
  </PlTable>
</template>

<script setup>
// A list of visits; a row opens its detail.
import { computed } from 'vue';
import { Badge } from '@/kit/index.js';
import { PlTable } from '@/ui/index.js';
import { dates, statusLabel, statusTone } from './presentation.js';

const props = defineProps({
  visits: { type: Array, required: true },
  label: { type: String, required: true },
  showPerson: { type: Boolean, default: true },
  emptyText: { type: String, default: 'No visits.' },
});

const emit = defineEmits(['open']);

const columns = computed(() => [
  ...(props.showPerson ? [{ key: 'personName', label: 'Engineer' }] : []),
  { key: 'customer', label: 'Customer' },
  { key: 'when', label: 'Dates' },
  { key: 'ownerName', label: 'Account owner' },
  { key: 'status', label: 'Status' },
]);
</script>
