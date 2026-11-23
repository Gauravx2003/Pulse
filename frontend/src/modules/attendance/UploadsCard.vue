<template>
  <PlCard title="Monthly uploads" :subtitle="subtitle">
    <PlTable :columns="columns" :rows="rows" row-key="month" label="Monthly uploads" empty-text="No months yet.">
      <template #cell-label="{ row }">
        <strong>{{ row.label }}</strong>
      </template>
      <template #cell-status="{ row }">
        <Badge :text="row.status" :tone="row.tone" />
      </template>
      <template #cell-file="{ row }">
        <span class="pl-mono att-file">{{ row.file ?? '—' }}</span>
      </template>
      <template #cell-uploaded="{ row }">
        <span class="pl-caption">{{ row.uploaded ?? '—' }}</span>
      </template>
      <template #cell-action="{ row }">
        <RouterLink
          v-if="row.open"
          class="pl-btn pl-btn-sm"
          :to="UPLOAD_LINK"
          :aria-label="`${row.file ? 'Replace' : 'Upload'} ${row.label}`"
        >
          {{ row.file ? 'Replace' : 'Upload' }}
        </RouterLink>
        <span v-else class="pl-caption">—</span>
      </template>
    </PlTable>
  </PlCard>
</template>

<script setup>
// Monthly uploads (reference attUploadsCard): each month Pulse keeps, newest first, with its upload.
import { computed } from 'vue';
import { Badge } from '@/kit/index.js';
import { formatDateTime } from '@/ui/format.js';
import { PlCard, PlTable } from '@/ui/index.js';
import { addDays } from './presentation.js';

const UPLOAD_LINK = { path: '/imports', query: { type: 'attendance' } };

const props = defineProps({
  /** GET /attendance/periods months: [{ key: 'm:2026-08', label, uploaded }] */
  months: { type: Array, default: () => [] },
  /** [{ month: '2026-08', uploadedBy, uploadedAt, fileName, rows }] */
  uploads: { type: Array, default: () => [] },
  today: { type: String, default: null },
  canUpload: { type: Boolean, default: false },
});

const columns = computed(() => [
  { key: 'label', label: 'Month' },
  { key: 'status', label: 'Status' },
  { key: 'file', label: 'File' },
  { key: 'uploaded', label: 'Uploaded' },
  { key: 'count', label: 'Rows', num: true },
  ...(props.canUpload ? [{ key: 'action', label: 'Upload' }] : []),
]);

function lastDay(month) {
  const [y, m] = month.split('-').map(Number);
  const next = m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, '0')}-01`;

  return addDays(next, -1);
}

const rows = computed(() =>
  props.months.map((m) => {
    const month = m.key.slice(2);
    const up = props.uploads.find((u) => u.month === month);
    const today = props.today ?? '9999-12-31';
    const started = `${month}-01` <= today;
    let status = 'Not started';
    let tone = '';

    if (up) {
      status = 'Uploaded';
      tone = 'ok';
    } else if (started) {
      status = lastDay(month) >= today ? 'Due after month end' : 'Missing';
      tone = 'accent';
    }

    return {
      month,
      label: m.label,
      status,
      tone,
      file: up?.fileName ?? null,
      uploaded: up ? `${up.uploadedBy ?? '—'} · ${formatDateTime(up.uploadedAt)}` : null,
      count: up?.rows ?? null,
      open: started,
    };
  }),
);

const subtitle = computed(() =>
  props.canUpload
    ? 'Upload once a month from the HR / biometric export · re-uploading a month replaces it · every upload can be undone from Data imports'
    : 'HR uploads one file a month · you see the result as soon as it is in',
);
</script>

<style scoped>
.att-file {
  font-size: 12px;
}
</style>
