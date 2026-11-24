import { setPerson } from '@/shell/person.js';

/**
 * Where an item's button goes (spec insight/action-center "Do today": "one action opening the right
 * screen, tab and person"). Items and next steps carry `target: { module, tab?, personId?, id? }`; the
 * module key is the API module, mapped here to the screen's path. The person becomes the shared person
 * of the person pages; the tab and id go in the query.
 */
const PATHS = {
  'work-items': '/work-items',
  work: '/work-items',
  utilization: '/team-utilization',
  effort: '/effort',
  bugs: '/bugs-support',
  pull_requests: '/pull-requests',
  okrs: '/okrs',
  roadmap: '/roadmap',
  progress: '/progress',
  visits: '/visits',
  training: '/training',
  reviews: '/reviews',
  attendance: '/attendance',
  appraisal: '/appraisal',
  integrations: '/integrations',
  imports: '/imports',
  actions: '/actions',
};

/** The route location of a target, or null for a module without a screen. */
export function targetLocation(target) {
  const path = PATHS[target?.module];

  if (!path) {
    return null;
  }

  const query = {};

  if (target.tab) {
    query.tab = target.tab;
  }

  if (target.id) {
    query.id = target.id;
  }

  return { path, query };
}

/** Opens a target: sets the shared person when it names one, then navigates. */
export function openTarget(router, target) {
  const location = targetLocation(target);

  if (!location) {
    return;
  }

  if (target.personId) {
    setPerson(target.personId);
  }

  router.push(location);
}
