/* Compact remaining-quota meter. Values and billing rules always come from the caller's API data. */
(function (root) {
  'use strict';
  let nextId = 0;

  function remaining(data) {
    if (!data || data.unlimited) return null;
    const numeric = value => (typeof value === 'number' || (typeof value === 'string' && value.trim() !== '')) && Number.isFinite(Number(value));
    if (!numeric(data.limit) || !numeric(data.used)) return null;
    const limit = Number(data.limit), used = Number(data.used);
    if (!Number.isFinite(limit) || limit <= 0 || !Number.isFinite(used) || used < 0) return null;
    return Math.max(0, Math.min(1, (limit - used) / limit)) * 100;
  }

  function mount(element, options = {}) {
    if (!element) return null;
    if (element._quotaMeter) return element._quotaMeter;
    const doc = element.ownerDocument;
    const make = (tag, className) => { const el = doc.createElement(tag); el.className = className; return el; };
    element.classList.add('jl-quota');
    element.hidden = true;
    const ring = make('span', 'jl-quota__ring');
    ring.setAttribute('role', 'meter');
    ring.setAttribute('aria-label', options.label || 'Hạn mức còn lại');
    ring.setAttribute('aria-valuemin', '0');
    ring.setAttribute('aria-valuemax', '100');
    const svg = doc.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 48 48');
    svg.setAttribute('aria-hidden', 'true');
    for (const className of ['jl-quota__track', 'jl-quota__fill']) {
      const circle = doc.createElementNS(svg.namespaceURI, 'circle');
      Object.entries({ cx: 24, cy: 24, r: 20, pathLength: 100, class: className }).forEach(([key, value]) => circle.setAttribute(key, value));
      svg.appendChild(circle);
    }
    const value = make('span', 'jl-quota__value');
    value.setAttribute('aria-hidden', 'true');
    ring.append(svg, value);
    const caption = make('span', 'jl-quota__label');
    const toggle = make('button', 'jl-quota__toggle');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    const detail = make('span', 'jl-quota__detail');
    detail.id = (element.id || 'jl-quota-' + ++nextId) + '-detail';
    detail.hidden = true;
    toggle.setAttribute('aria-controls', detail.id);
    const description = make('span', 'jl-quota__description');
    const note = make('span', 'jl-quota__note');
    detail.append(description, note);
    element.replaceChildren(ring, caption, toggle, detail);
    let pinned = false, ready = false;
    function show() { detail.hidden = false; toggle.setAttribute('aria-expanded', 'true'); }
    function close() { pinned = false; detail.hidden = true; toggle.setAttribute('aria-expanded', 'false'); }
    toggle.addEventListener('pointerenter', event => { if (event.pointerType !== 'touch') show(); });
    toggle.addEventListener('pointerleave', () => { if (!pinned) close(); });
    toggle.addEventListener('focus', show);
    toggle.addEventListener('blur', () => { if (!pinned) close(); });
    toggle.addEventListener('click', () => { if (pinned) close(); else { pinned = true; show(); } });
    toggle.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !detail.hidden) { event.preventDefault(); event.stopPropagation(); close(); }
    });
    doc.addEventListener('pointerdown', event => { if (!element.contains(event.target)) close(); });
    const meter = {
      close,
      update(data) {
        close();
        element.hidden = !data;
        if (!data) return;
        const percent = remaining(data);
        element.dataset.state = data.unlimited ? 'unlimited' : percent == null ? 'unknown' : percent === 0 ? 'empty' : 'available';
        element.style.setProperty('--jl-quota-value', percent == null ? 0 : percent);
        const display = percent == null ? '—' : percent > 0 && percent < 1 ? '<1%' : Math.round(percent) + '%';
        value.textContent = display;
        caption.textContent = data.unlimited ? 'Không giới hạn' : options.caption || 'Còn lại';
        description.textContent = data.description || '';
        note.textContent = data.note || '';
        note.hidden = !data.note;
        toggle.setAttribute('aria-label', [options.label || 'Hạn mức', data.description, data.note].filter(Boolean).join('. '));
        if (percent == null) {
          ring.removeAttribute('role');
          ring.removeAttribute('aria-valuemin');
          ring.removeAttribute('aria-valuemax');
          ring.removeAttribute('aria-valuenow');
          ring.removeAttribute('aria-valuetext');
        } else {
          ring.setAttribute('role', 'meter');
          ring.setAttribute('aria-valuemin', '0');
          ring.setAttribute('aria-valuemax', '100');
          ring.setAttribute('aria-valuenow', String(percent));
          ring.setAttribute('aria-valuetext', display + ' còn lại. ' + (data.description || ''));
        }
        if (!ready) { ready = true; root.requestAnimationFrame(() => element.classList.add('is-ready')); }
      }
    };
    element._quotaMeter = meter;
    return meter;
  }
  root.JlasQuotaMeter = { mount, remaining };
  if (typeof module === 'object' && module.exports) module.exports = root.JlasQuotaMeter;
})(typeof window !== 'undefined' ? window : globalThis);
