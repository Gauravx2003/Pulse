<template>
  <Teleport to="body">
    <div v-if="open" class="pl pl-overlay pl-modal" @mousedown.self="onScrim">
      <div
        ref="panel"
        class="pl-modal-box"
        :class="{ 'is-lg': size === 'lg' }"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
      >
        <div class="pl-modal-head">
          <h2 :id="titleId" class="pl-h2">{{ title }}</h2>
          <button type="button" class="pl-x" aria-label="Close" @click="close">×</button>
        </div>
        <slot />
        <div v-if="$slots.actions" class="pl-modal-foot">
          <slot name="actions" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
// Modal (reference section 3): centered, 480 px (900 px for wizards), max 90 vh and scrolls inside;
// Cancel left of the primary action; focus trap, Esc and scrim click close, focus returns to the opener.
import { ref, toRef } from 'vue';
import { useDialog, uid } from './dialog.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  size: { type: String, default: 'md', validator: (s) => s === 'md' || s === 'lg' },
  closeOnScrim: { type: Boolean, default: true },
  /** Returns the element to focus first; defaults to the first focusable control. */
  initialFocus: { type: Function, default: null },
});

const emit = defineEmits(['close']);

const panel = ref(null);
const titleId = uid('modal');

function close() {
  emit('close');
}

function onScrim() {
  if (props.closeOnScrim) {
    close();
  }
}

useDialog(toRef(props, 'open'), panel, close, { initialFocus: () => props.initialFocus?.() ?? null });
</script>
