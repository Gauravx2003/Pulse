<template>
  <section class="pl-card" :aria-labelledby="title ? headingId : undefined" :aria-busy="loading || undefined">
    <div v-if="title || $slots.actions" class="pl-card-head">
      <div>
        <h2 v-if="title" :id="headingId" class="pl-h2">{{ title }}</h2>
        <div v-if="subtitle" class="pl-caption">{{ subtitle }}</div>
      </div>
      <div v-if="$slots.actions" class="pl-card-actions">
        <slot name="actions" />
      </div>
    </div>
    <PlLoading v-if="loading" />
    <PlEmpty v-else-if="empty" :text="emptyText">
      <slot name="empty" />
    </PlEmpty>
    <slot v-else />
  </section>
</template>

<script setup>
// Card (reference section 3): H2 title and caption subtitle, a right slot, and the loading and
// empty states every card must have (spec platform/app-shell-ui, "Empty and loading states").
import PlEmpty from './PlEmpty.vue';
import PlLoading from './PlLoading.vue';
import { uid } from './dialog.js';

defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: 'Nothing to show yet.' },
});

const headingId = uid('card');
</script>
