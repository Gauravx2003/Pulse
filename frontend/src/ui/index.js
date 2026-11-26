/**
 * Shared UI primitives (task 6.3, spec platform/app-shell-ui). Every screen builds from these and the
 * design-system wrappers in '@/kit'; styling lives in assets/styles/base.css on design tokens only.
 * The /kit/primitives page renders each one.
 */
export { default as PlPageHeader } from './PlPageHeader.vue';
export { default as PlKpiRow } from './PlKpiRow.vue';
export { default as PlCard } from './PlCard.vue';
export { default as PlTable } from './PlTable.vue';
export { default as PlTabs } from './PlTabs.vue';
export { default as PlPeriodControl } from './PlPeriodControl.vue';
export { default as PlModal } from './PlModal.vue';
export { default as PlDrawer } from './PlDrawer.vue';
export { default as PlConfirm } from './PlConfirm.vue';
export { default as PlToastHost } from './PlToastHost.vue';
export { default as PlAlert } from './PlAlert.vue';
export { default as PlEmpty } from './PlEmpty.vue';
export { default as PlLoading } from './PlLoading.vue';
export { default as PlField } from './PlField.vue';
export { default as PlFormError } from './PlFormError.vue';
export { showToast, TOAST_MS } from './toast.js';
export { useDialog } from './dialog.js';
export { KIND_LABELS, kindOf, defaultKey, isKnown, releaseSprints } from './periods.js';
