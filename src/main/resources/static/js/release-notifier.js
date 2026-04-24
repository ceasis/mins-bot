// Release notifier — polls /api/release/status on load.
// Shows a compact banner when a new version is available or unreported crashes exist.
(function () {
  'use strict';

  function el(tag, props, ...kids) {
    const e = document.createElement(tag);
    if (props) Object.assign(e, props);
    for (const k of kids) e.append(k);
    return e;
  }

  function dismiss(key) {
    try { localStorage.setItem('release-notifier.dismissed.' + key, '1'); } catch (_) {}
  }
  function dismissed(key) {
    try { return localStorage.getItem('release-notifier.dismissed.' + key) === '1'; }
    catch (_) { return false; }
  }

  function showBanner({ text, actionLabel, onAction, dismissKey }) {
    if (dismissKey && dismissed(dismissKey)) return;
    const bar = el('div');
    bar.className = 'release-banner';
    bar.style.cssText = [
      'position:fixed','left:8px','right:8px','bottom:8px','z-index:99999',
      'background:#1e1e32','color:#e2e2f0','border:1px solid #3d3a7a',
      'border-radius:8px','padding:8px 12px','font-size:12px',
      'display:flex','gap:8px','align-items:center',
      'box-shadow:0 4px 18px rgba(0,0,0,0.4)'
    ].join(';');
    const label = el('span'); label.textContent = text; label.style.flex = '1';
    const act = el('button'); act.textContent = actionLabel;
    act.style.cssText = 'background:#7c6af7;color:#fff;border:0;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px';
    act.addEventListener('click', onAction);
    const close = el('button'); close.textContent = '×';
    close.style.cssText = 'background:transparent;color:#7070a0;border:0;font-size:16px;cursor:pointer;padding:0 4px';
    close.addEventListener('click', () => { if (dismissKey) dismiss(dismissKey); bar.remove(); });
    bar.append(label, act, close);
    document.body.appendChild(bar);
  }

  async function check() {
    let data;
    try {
      const r = await fetch('/api/release/status');
      if (!r.ok) return;
      data = await r.json();
    } catch (_) { return; }

    if (data.update && data.update.version) {
      const v = data.update.version;
      showBanner({
        text: 'Update available: Mins Bot ' + v + (data.update.notes ? ' — ' + data.update.notes : ''),
        actionLabel: 'Download',
        onAction: () => {
          if (data.update.url) window.open(data.update.url, '_blank');
          dismiss('update:' + v);
        },
        dismissKey: 'update:' + v
      });
    }

    if (data.crashCount && data.crashCount > 0) {
      showBanner({
        text: 'Mins Bot recovered from a crash (' + data.crashCount + ' report' +
              (data.crashCount === 1 ? '' : 's') + '). Email logs to support@mins.io?',
        actionLabel: 'Show folder',
        onAction: () => {
          fetch('/api/open-path', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: data.crashDir })
          }).catch(() => {});
        },
        dismissKey: 'crashes:' + data.crashCount
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => setTimeout(check, 2000));
  } else {
    setTimeout(check, 2000);
  }
})();
