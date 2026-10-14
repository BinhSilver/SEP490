(function (root, factory) {
  'use strict';
  const api = factory(root.document);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ShadowingEmptyExercises = api;
}(typeof window !== 'undefined' ? window : globalThis, function (document) {
  'use strict';
  const dialog = document?.getElementById('practice-empty-dialog');
  dialog?.querySelector('[data-empty-dismiss]')?.addEventListener('click', () => dialog.close());
  return {
    hasQuestions: function (availability) {
      if (Array.isArray(availability?.questions)) return availability.questions.length > 0;
      return Object.values(availability?.counts || {}).some(count => Number(count) > 0);
    },
    show: function () {
      if (dialog && !dialog.open) dialog.showModal();
    }
  };
}));
