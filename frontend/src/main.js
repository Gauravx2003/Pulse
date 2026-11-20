import { createApp } from 'vue';
import App from './App.vue';
import { router } from './router.js';

// Import design system tokens and bundle styles
import './assets/styles/tokens.css';
import './assets/styles/bundle.css';

createApp(App).use(router).mount('#app');
