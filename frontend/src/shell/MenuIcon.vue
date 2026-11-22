<script setup>
// One menu icon (src/shell/menuIcons.js): decorative, so hidden from assistive technology; the link
// text names the screen.
import { computed, h } from 'vue';
import { FALLBACK_ICON, MENU_ICONS } from './menuIcons.js';

const props = defineProps({
  /** Menu key from GET /me, e.g. 'work-items'. */
  name: { type: String, required: true },
});

const shapes = computed(() => MENU_ICONS[props.name] ?? FALLBACK_ICON);

const Icon = () =>
  h(
    'svg',
    {
      class: 'pl-nav-icon',
      viewBox: '0 0 24 24',
      width: 20,
      height: 20,
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': 1.5,
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
      'aria-hidden': 'true',
      focusable: 'false',
    },
    shapes.value.map(([tag, attrs]) => h(tag, attrs)),
  );
</script>

<template>
  <Icon />
</template>
