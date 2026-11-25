/**
 * Setup screen layout. The card filter is drawn by the shared dropdown (data-jl-select,
 * js/components/select.js), which keeps the <select> as the value used to start a session.
 */
export function bindSetupUi() {
  const form = document.getElementById('setup-form');
  const heading = document.querySelector('.sr-setup__heading');
  if (!form || !heading) return;

  form.prepend(heading);
}
