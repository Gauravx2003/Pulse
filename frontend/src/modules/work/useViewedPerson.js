import { computed, onMounted } from 'vue';
import { loadPeople, personState, setPerson } from '@/shell/person.js';
import { session } from '@/shell/session.js';

/**
 * The person a work screen shows: the one picked in the top-bar selector, else the signed-in user. A
 * manager or Admin without a work record of their own starts on the first person they may open
 * (GET /people, the server-side scope), so the selector and the screen agree.
 */
export function useViewedPerson() {
  const selfId = computed(() => session.me?.user?.personId ?? null);

  const id = computed(() => personState.id ?? selfId.value);

  onMounted(async () => {
    if (id.value) {
      return;
    }

    try {
      const rows = await loadPeople();

      if (!personState.id && rows?.length) {
        setPerson(rows[0].id);
      }
    } catch (e) {
      console.error('GET /people failed', e);
    }
  });

  return { id, selfId };
}
