/* Offline-mode toggle wired to /api/offline-mode/* endpoints. Shows a shield
   icon in the title bar; clicking toggles state and updates visuals. */
(function () {
  'use strict';

  const btn = document.getElementById('title-bar-offline');
  if (!btn) return;

  function apply(offline) {
    if (offline) {
      btn.classList.add('offline-on');
      btn.setAttribute('data-tip', 'Offline mode ON — click to re-enable cloud APIs');
      document.body.classList.add('offline-mode');
    } else {
      btn.classList.remove('offline-on');
      btn.setAttribute('data-tip', 'Offline mode — blocks all cloud APIs');
      document.body.classList.remove('offline-mode');
    }
  }

  async function fetchStatus() {
    try {
      const res = await fetch('/api/offline-mode/status');
      if (!res.ok) return;
      const data = await res.json();
      apply(!!data.offline);
    } catch (e) { /* ignore */ }
  }

  async function toggle() {
    try {
      const res = await fetch('/api/offline-mode/toggle', { method: 'POST' });
      if (!res.ok) return;
      const data = await res.json();
      apply(!!data.offline);
    } catch (e) { /* ignore */ }
  }

  btn.addEventListener('click', toggle);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', fetchStatus);
  } else {
    fetchStatus();
  }
})();
