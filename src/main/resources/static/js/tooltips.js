/**
 * Body-level tooltip renderer.
 *
 * Original CSS tooltips (::after pseudo-elements) get clipped when the tooltip
 * extends beyond the button's ancestor stacking context — our panel-header
 * lives above a .tab-content that uses `transform`, which creates a new
 * stacking context and steals the paint order. To escape all of that, render
 * the tooltip as a single fixed-position <div> appended to <body>, positioned
 * dynamically from each button's bounding rect.
 */
(() => {
  // Build the floating tooltip node once.
  const tip = document.createElement('div');
  tip.id = 'mins-tooltip';
  Object.assign(tip.style, {
    position: 'fixed',
    zIndex: '2147483647',
    pointerEvents: 'none',
    opacity: '0',
    transform: 'translateY(2px)',
    transition: 'opacity 0.12s ease, transform 0.12s ease',
    background: '#27272a',
    color: '#f4f4f5',
    border: '1px solid rgba(255,255,255,0.15)',
    borderRadius: '7px',
    padding: '7px 12px',
    font: '500 13px "Segoe UI", system-ui, -apple-system, sans-serif',
    whiteSpace: 'nowrap',
    boxShadow: '0 4px 14px rgba(0,0,0,0.5)',
    letterSpacing: '0.01em',
    maxWidth: 'min(90vw, 420px)',
    whiteSpace: 'normal'
  });
  let appended = false;
  function ensureAttached() {
    if (!appended && document.body) { document.body.appendChild(tip); appended = true; }
  }

  let currentEl = null;
  let repositionHandle = 0;

  function show(el) {
    ensureAttached();
    const label = el.getAttribute('data-tip');
    if (!label) return;
    tip.textContent = label;
    position(el);
    tip.style.opacity = '1';
    tip.style.transform = 'translateY(0)';
  }

  function hide() {
    tip.style.opacity = '0';
    tip.style.transform = 'translateY(2px)';
  }

  function position(el) {
    const rect = el.getBoundingClientRect();
    const dir = el.getAttribute('data-tip-dir') === 'down' ? 'down' : 'up';
    // Measure tooltip after content set.
    tip.style.left = '0px';
    tip.style.top = '0px';
    const tipRect = tip.getBoundingClientRect();

    let x = rect.left + rect.width / 2 - tipRect.width / 2;
    let y = dir === 'down'
        ? rect.bottom + 10
        : rect.top - tipRect.height - 10;

    // Clamp to viewport with an 8px margin.
    const margin = 8;
    const maxX = window.innerWidth - tipRect.width - margin;
    const maxY = window.innerHeight - tipRect.height - margin;
    if (x < margin) x = margin;
    if (x > maxX)   x = Math.max(margin, maxX);
    if (y < margin) y = margin;
    if (y > maxY)   y = Math.max(margin, maxY);

    tip.style.left = x + 'px';
    tip.style.top = y + 'px';
  }

  function findTip(node) {
    return node && node.closest ? node.closest('[data-tip]') : null;
  }

  function setCurrent(el) {
    if (el === currentEl) return;
    currentEl = el;
    if (el) show(el); else hide();
  }

  document.addEventListener('mouseover', (e) => {
    const el = findTip(e.target);
    if (el) setCurrent(el);
  });

  document.addEventListener('mouseout', (e) => {
    const el = findTip(e.target);
    if (!el) return;
    const to = e.relatedTarget;
    if (!to || !el.contains(to)) setCurrent(null);
  });

  // Reposition (throttled via rAF) on scroll / resize so the tooltip stays
  // attached to the button if the layout shifts.
  function scheduleReposition() {
    if (repositionHandle) return;
    repositionHandle = requestAnimationFrame(() => {
      repositionHandle = 0;
      if (currentEl) position(currentEl);
    });
  }
  window.addEventListener('scroll', scheduleReposition, true);
  window.addEventListener('resize', scheduleReposition);

  // Safety nets.
  document.addEventListener('click', () => setCurrent(null));
  window.addEventListener('blur', () => setCurrent(null));
})();
