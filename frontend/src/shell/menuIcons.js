/**
 * Menu icons (reference section 3: "Lucide-style 16 px line icons in the menu"; design-system README:
 * Lucide-style line icons, 20 px in the nav). The shapes are the prototype's own icon set
 * (reference/2-prototype/src/app.js ICONS), keyed by the GET /me menu key. Each icon is a list of
 * [element, attributes] drawn on a 24 x 24 viewBox with a 1.5 px stroke in the current color.
 */
/** Icon for a module's own menu entry (beyond the screen map): a plain square with a dot. */
export const FALLBACK_ICON = [['rect', { x: '4', y: '4', width: '16', height: '16', rx: '3' }], ['circle', { cx: '12', cy: '12', r: '2' }]];

export const MENU_ICONS = {
  attendance: [['rect', {'x': '3', 'y': '4', 'width': '18', 'height': '17', 'rx': '2'}], ['path', {'d': 'M3 9h18M8 2v4M16 2v4M8.5 15l2.5 2.5 4.5-5'}]],
  imports: [['path', {'d': 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12'}]],
  actions: [['path', {'d': 'M13 2 3 14h9l-1 8 10-12h-9z'}]],
  progress: [['path', {'d': 'M3 3v18h18'}], ['path', {'d': 'M7 15l4-4 3 3 5-6'}]],
  'pull-requests': [['circle', {'cx': '6', 'cy': '6', 'r': '2.5'}], ['circle', {'cx': '6', 'cy': '18', 'r': '2.5'}], ['circle', {'cx': '18', 'cy': '18', 'r': '2.5'}], ['path', {'d': 'M6 8.5v7M18 15.5V9a3 3 0 0 0-3-3h-4M13 3.5 10.5 6 13 8.5'}]],
  visits: [['path', {'d': 'M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z'}], ['circle', {'cx': '12', 'cy': '10', 'r': '2.5'}]],
  training: [['path', {'d': 'M22 10L12 5 2 10l10 5 10-5z'}], ['path', {'d': 'M6 12v5c3 2 9 2 12 0v-5'}]],
  appraisal: [['path', {'d': 'M3 17l6-6 4 4 8-8'}], ['path', {'d': 'M14 7h7v7'}], ['path', {'d': 'M3 21h18'}]],
  'r360': [['circle', {'cx': '12', 'cy': '12', 'r': '9'}], ['path', {'d': 'M12 3a9 9 0 0 1 0 18M3 12h18'}], ['circle', {'cx': '12', 'cy': '12', 'r': '3'}]],
  'bugs-support': [['path', {'d': 'M8 2l1.9 1.9M16 2l-1.9 1.9M9 7.1V6a3 3 0 1 1 6 0v1.1M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6zM12 20v-9M6.5 13H3M21 13h-3.5M4 21c0-2.1 1.6-3.8 3.6-4M20 21c0-2.1-1.6-3.8-3.6-4'}]],
  okrs: [['circle', {'cx': '12', 'cy': '12', 'r': '9'}], ['circle', {'cx': '12', 'cy': '12', 'r': '5'}], ['circle', {'cx': '12', 'cy': '12', 'r': '1'}]],
  users: [['path', {'d': 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6'}]],
  roles: [['path', {'d': 'M12 2l8 4v6c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z'}], ['path', {'d': 'M9 12l2 2 4-4'}]],
  effort: [['path', {'d': 'M3 4h18v17H3zM3 9h18M8 2v4M16 2v4M7 13h3M7 17h3M14 13h3'}]],
  overview: [['path', {'d': 'M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z'}]],
  'work-items': [['path', {'d': 'M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01'}]],
  roadmap: [['path', {'d': 'M3 17l6-6 4 4 8-8M14 7h7v7'}]],
  culture: [['path', {'d': 'M12 21s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 5.5-7 10-7 10z'}]],
  'team-utilization': [['path', {'d': 'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM23 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8'}]],
  reviews: [['path', {'d': 'M12 2l3 6.5 7 .8-5.2 4.8 1.5 6.9L12 17.5 5.7 21l1.5-6.9L2 9.3l7-.8z'}]],
  integrations: [['path', {'d': 'M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.8 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7'}]],
  rules: [['path', {'d': 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6'}]],
};
