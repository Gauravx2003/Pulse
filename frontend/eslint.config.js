import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import globals from 'globals';
import noHexColors from './eslint-rules/no-hex-colors.js';

export default [
  { ignores: ['dist/**', '.audit-dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
    },
  },
  {
    files: ['**/*.vue'],
    plugins: { pulse: { rules: { 'no-hex-colors': noHexColors } } },
    rules: {
      'pulse/no-hex-colors': 'error',
    },
  },
  {
    // Kit components keep the design-system component names (Badge, StatTile, ...).
    files: ['src/kit/*.vue'],
    rules: { 'vue/multi-word-component-names': 'off' },
  },
  {
    files: ['*.config.js', 'eslint-rules/**/*.js', 'e2e/**/*.js'],
    languageOptions: { globals: { ...globals.node } },
  },
];
