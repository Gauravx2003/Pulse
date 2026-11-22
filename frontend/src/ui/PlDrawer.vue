<template>
  <Teleport to="body">
    <template v-if="open">
      <div class="pl pl-overlay" @mousedown="close"></div>
      <aside
        ref="panel"
        class="pl pl-drawer"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
      >
        <div class="pl-drawer-head">
          <div class="pl-drawer-title">
            <div v-if="eyebrow" class="pl-eyebrow">{{ eyebrow }}</div>
            <h2 :id="titleId" class="pl-h2">{{ title }}</h2>
          </div>
          <button type="button" class="pl-x" aria-label="Close" @click="close">×</button>
        </div>
        <div class="pl-drawer-body">
          <slot />
        </div>
      </aside>
    </template>
  </Teleport>
</template>

<script setup>
// Drawer (reference section 3): right-side detail panel with a scrim; same keyboard rules as a modal.
import { ref, toRef } from 'vue';
import { useDialog, uid } from './dialog.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  eyebrow: { type: String, default: '' },
});

const emit = defineEmits(['close']);

const panel = ref(null);
const titleId = uid('drawer');

function close() {
  emit('close');
}

useDialog(toRef(props, 'open'), panel, close);
</script>
