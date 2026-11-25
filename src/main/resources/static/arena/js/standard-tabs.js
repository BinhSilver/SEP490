/* The existing tab handlers remain in charge; this only positions the shared sliding selection surface. */
(function () {
  'use strict';
  const mounted = new WeakSet();
  function mount(root) {
    if (mounted.has(root)) return;
    mounted.add(root);
    const kit = root.classList.contains('jl-tabs');
    if (!kit) root.classList.add('ar-standard-tabs');
    let frame;
    function update() {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const active = root.querySelector('button[aria-selected="true"]');
        if (!active || !active.offsetWidth) return;
        if (kit) {
          root.style.setProperty('--jl-tab-x', active.offsetLeft + 'px');
          root.style.setProperty('--jl-tab-w', active.offsetWidth + 'px');
          root.classList.add('is-ready');
          return;
        }
        const box = active.getBoundingClientRect(), parent = root.getBoundingClientRect();
        root.style.setProperty('--ar-tab-x', box.left - parent.left + root.scrollLeft + 'px');
        root.style.setProperty('--ar-tab-width', box.width + 'px');
        root.style.setProperty('--ar-tab-top', box.top - parent.top + 'px');
        root.style.setProperty('--ar-tab-height', box.height + 'px');
        root.dataset.tabsReady = 'true';
      });
    }
    new MutationObserver(update).observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-selected'] });
    new ResizeObserver(update).observe(root);
    document.fonts?.ready.then(update);
    update();
  }
  function scan() { document.querySelectorAll('.arena-standard :is(.ar-seg, .jl-tabs)').forEach(mount); }
  new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
  scan();
})();
