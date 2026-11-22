<template>
  <span :class="['pl-badge', tone && `is-${tone}`]">{{ text }}<slot /></span>
</template>

<script setup>
import { TONES } from './tones.js';

/**
 * Badge (reference/3-design-system/components/Badge): names a status or band in words, at most
 * two words, sentence case. The dot is decoration; never pass an empty text.
 */
defineProps({
  text: { type: String, default: '' },
  tone: { type: String, default: '', validator: (v) => v === '' || TONES.includes(v) },
});
</script>
