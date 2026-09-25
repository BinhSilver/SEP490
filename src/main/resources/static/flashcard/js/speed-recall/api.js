/** Reuse the existing Flashcard auth/envelope client. */
export function createApi(setId) {
  const base = '/api/flashcards/' + encodeURIComponent(setId) + '/speed-recall';
  return {
    options: () => window.FlashApi.get(base + '/options'),
    history: page => window.FlashApi.get(base + '/history?page=' + page + '&size=20'),
    state: id => window.FlashApi.get(base + '/sessions/' + encodeURIComponent(id)),
    create: body => window.FlashApi.post(base + '/sessions', body),
    action: (id, action, body = {}) => window.FlashApi.post(base + '/sessions/' + encodeURIComponent(id) + '/' + action, body)
  };
}
