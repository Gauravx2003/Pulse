<template>
  <div class="pl-form-grid">
    <template v-for="f in fields" :key="f.name">
      <label v-if="f.type === 'boolean'" class="switch-row">
        <input
          type="checkbox"
          role="switch"
          class="pl-switch"
          :checked="values[f.name] === true"
          :aria-describedby="f.help ? `${idBase}-${f.name}-help` : undefined"
          @change="emit('set', f.name, $event.target.checked)"
        />
        <span>
          {{ f.label ?? f.name }}
          <span v-if="f.help" :id="`${idBase}-${f.name}-help`" class="pl-caption help">{{ f.help }}</span>
        </span>
      </label>
      <PlField
        v-else-if="f.type === 'choice'"
        :model-value="values[f.name]"
        :label="f.label ?? f.name"
        type="select"
        :required="f.required === true"
        :options="choiceOptions(f)"
        :hint="f.help ?? ''"
        :error="errors[`config.${f.name}`] ?? ''"
        @update:model-value="emit('set', f.name, $event)"
      />
      <PlField
        v-else
        :model-value="values[f.name]"
        :label="f.label ?? f.name"
        :type="liveInputType(f.type)"
        :required="f.required === true"
        :hint="hintOf(f)"
        :placeholder="f.type === 'list' ? 'Separate values with commas' : ''"
        autocomplete="off"
        :error="errors[`config.${f.name}`] ?? ''"
        @update:model-value="emit('set', f.name, $event)"
      />
    </template>

    <PlField
      v-model="secretModel"
      :label="secretSpec?.label ?? 'Secret'"
      type="password"
      required
      autocomplete="new-password"
      :hint="secretHint"
      :error="errors.secret ?? ''"
    />
  </div>
</template>

<script setup>
// The live connector form fields (task 24.7): rendered from the connector's configSchema and
// secretSpec only, so a new connector needs no change here. The secret is a password input that is
// never pre-filled; a stored one shows as its last 4 characters in the hint.
import { computed } from 'vue';
import { uid } from '@/ui/dialog.js';
import { PlField } from '@/ui/index.js';
import { choiceOptions, liveInputType, visibleFields } from './liveForm.js';

const props = defineProps({
  schema: { type: Object, default: null },
  secretSpec: { type: Object, default: null },
  /** config values; edits are emitted as `set(name, value)` */
  values: { type: Object, required: true },
  secret: { type: String, default: '' },
  /** errors keyed `config.<name>` and `secret` */
  errors: { type: Object, default: () => ({}) },
  /** last 4 characters of the stored secret, if any */
  storedLast4: { type: String, default: '' },
});

const emit = defineEmits(['update:secret', 'set']);

const idBase = uid('live');

const fields = computed(() => visibleFields(props.schema, props.values));

const secretModel = computed({
  get: () => props.secret,
  set: (v) => emit('update:secret', v),
});

const secretHint = computed(() =>
  [props.secretSpec?.help, props.storedLast4 ? `Stored: ••••${props.storedLast4}. Enter it again to test and save.` : null]
    .filter(Boolean)
    .join(' '),
);

function hintOf(f) {
  return [f.help, f.type === 'list' ? 'Separate values with commas.' : null].filter(Boolean).join(' ');
}
</script>

<style scoped>
.switch-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  font-size: 14px;
  grid-column: 1 / -1;
}

.help {
  display: block;
}
</style>
