import { createApp } from 'vue';
import App from './App.vue';

// Import design system tokens and bundle styles
import './assets/styles/tokens.css';
import './assets/styles/bundle.css';

const app = createApp(App);
app.mount('#app');
