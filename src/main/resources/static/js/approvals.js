/* Approval Gate — subscribes to /api/approvals/stream and surfaces a modal
   with 4 grant buttons when a pending approval arrives. Also wires the
   title-bar Bypass toggle. In-app modal per the project's no-JS-dialogs rule. */
(function () {
  'use strict';

  var bypassOn = false;
  var activeRequestId = null;
  var lightningBtn;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c];
    });
  }

  function renderBypassIndicator() {
    if (!lightningBtn) return;
    lightningBtn.classList.toggle('bypass-active', bypassOn);
  }

  function ensureModalRoot() {
    var slot = document.getElementById('approval-modal-slot');
    if (slot) return slot;
    slot = document.createElement('div');
    slot.id = 'approval-modal-slot';
    document.body.appendChild(slot);
    return slot;
  }

  function showModal(req) {
    if (activeRequestId === req.requestId) return;
    activeRequestId = req.requestId;
    var slot = ensureModalRoot();
    var riskLabel = (req.riskLevel || 'DESTRUCTIVE').toLowerCase().replace('_', ' ');
    slot.innerHTML = ''
      + '<div class="modal-backdrop" id="appr-bd"><div class="modal appr-modal">'
      + '  <div class="appr-risk appr-risk-' + esc(req.riskLevel) + '">' + esc(riskLabel) + '</div>'
      + '  <h3>Approve: <code>' + esc(req.toolName) + '</code></h3>'
      + '  <div class="modal-sub">' + esc(req.summary || '(no summary)') + '</div>'
      + '  <div class="appr-actions">'
      + '    <button class="btn appr-btn appr-once" data-grant="ALLOW_ONCE">Allow One Time</button>'
      + '    <button class="btn appr-btn appr-today" data-grant="ALLOW_TODAY">Allow Only Today</button>'
      + '    <button class="btn appr-btn appr-always" data-grant="ALLOW_ALWAYS">Always Allow</button>'
      + '    <button class="btn appr-btn appr-deny"  data-grant="DENY_ONCE">Don\'t Allow</button>'
      + '  </div>'
      + '  <div class="appr-foot">Auto-denies in 60s if left open. Toggle the ⚡ Bypass mode in the title bar to auto-approve future destructive calls this session.</div>'
      + '</div></div>';

    slot.querySelectorAll('.appr-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var grant = btn.getAttribute('data-grant');
        decide(req.requestId, grant, btn);
      });
    });
  }

  function closeModal() {
    activeRequestId = null;
    var slot = document.getElementById('approval-modal-slot');
    if (slot) slot.innerHTML = '';
  }

  async function decide(requestId, grant, btn) {
    if (btn) { btn.disabled = true; btn.textContent = '…'; }
    try {
      await fetch('/api/approvals/' + encodeURIComponent(requestId), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ grant: grant })
      });
    } catch (e) { /* ignore */ }
    closeModal();
  }

  async function toggleBypass() {
    bypassOn = !bypassOn;
    renderBypassIndicator();
    try {
      await fetch('/api/approvals/bypass', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ on: bypassOn })
      });
    } catch (e) {
      bypassOn = !bypassOn;
      renderBypassIndicator();
    }
  }

  function subscribe() {
    var es = new EventSource('/api/approvals/stream');
    es.addEventListener('init', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        bypassOn = !!d.bypass;
        renderBypassIndicator();
        if (d.pending && d.pending.length) showModal(d.pending[0]);
      } catch (e) {}
    });
    es.addEventListener('pending-set', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        var pending = d.pending || [];
        if (!pending.length) {
          // Server says nothing pending — close any modal we have open
          closeModal();
          return;
        }
        // Show the oldest pending request
        showModal(pending[0]);
      } catch (e) {}
    });
    es.addEventListener('bypass', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        bypassOn = !!d.on;
        renderBypassIndicator();
      } catch (e) {}
    });
    es.onerror = function () {
      // Auto-reconnect — EventSource does this natively, no-op.
    };
  }

  function init() {
    lightningBtn = document.getElementById('title-bar-bypass');
    if (lightningBtn && !lightningBtn.__wired) {
      lightningBtn.__wired = true;
      lightningBtn.addEventListener('click', toggleBypass);
    }
    subscribe();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
