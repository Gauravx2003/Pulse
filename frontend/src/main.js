import { createApp } from 'vue';
import App from './App.vue';
import { router } from './router.js';
import { initTheme } from './theme.js';
import { installApiHooks } from './shell/apiHooks.js';

// Self-hosted IBM Plex (CSP allows fonts from 'self' only): the weights bundle.css uses.
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import '@fontsource/ibm-plex-sans/700.css';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/600.css';

// The only styling base: design-system tokens and component bundle (design.md decision 9).
import './assets/styles/tokens.css';
import './assets/styles/bundle.css';
import './assets/styles/base.css';

initTheme();
installApiHooks(router);

createApp(App).use(router).mount('#app');
