import { reactive, ref } from 'vue';
import { api } from '../api/client.js';
import { onViewReset, readView, writeView } from './viewState.js';

/**
 * The person shown on person pages (reference section 3: "a person selector on person pages
 * (managers and Admin)"). Null means the signed-in user themself. Reset at sign-in.
 */
const KEY = 'person';

export const personState = reactive({ id: readView(KEY) });

onViewReset(() => {
  personState.id = null;
  people.value = null;
  loading = null;
});

export function setPerson(id) {
  personState.id = id || null;
  writeView(KEY, personState.id);
}

/** GET /people: the people the user may open, from the server-side policy scope. */
export const people = ref(null);
let loading = null;

export function loadPeople() {
  if (!loading) {
    loading = api
      .get('/people')
      .then((rows) => {
        people.value = rows;
        return rows;
      })
      .catch((e) => {
        loading = null;
        throw e;
      });
  }

  return loading;
}
