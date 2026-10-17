/* Apply the shared jlas-theme contract before page styles are painted. */
(function () {
  var theme;
  try { theme = localStorage.getItem('jlas-theme'); } catch (e) { /* Storage may be disabled. */ }
  if (theme !== 'dark' && theme !== 'light') {
    theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  var root = document.documentElement;
  root.setAttribute('data-theme', theme);
  root.classList.remove('dark', 'light');
  root.classList.add(theme);
}());
