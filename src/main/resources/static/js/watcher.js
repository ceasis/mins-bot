/* Watcher tab — create, list, trigger, delete watchers via /api/skills/watcher. */
(function () {
  'use strict';

  function el(id) { return document.getElementById(id); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c];
    });
  }

  function setMsg(text, isError) {
    var m = el('watcher-status-msg');
    if (!m) return;
    m.textContent = text || '';
    m.style.color = isError ? '#f87171' : '#4ade80';
    if (text) {
      clearTimeout(setMsg._t);
      setMsg._t = setTimeout(function () { m.textContent = ''; }, 4000);
    }
  }

  function statusDot(status) {
    var color;
    if (status === 'in-stock') color = '#4ade80';
    else if (status === 'out-of-stock') color = '#9ca3af';
    else if (status === 'error') color = '#f87171';
    else color = '#fbbf24'; // unknown
    return '<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:' + color + ';margin-right:6px;vertical-align:middle;"></span>';
  }

  async function loadList() {
    var listEl = el('watcher-list');
    if (!listEl) return;
    try {
      var res = await fetch('/api/skills/watcher');
      if (res.status === 403) {
        listEl.innerHTML = '<div class="tab-empty">Watcher skill is disabled. Set <code>app.skills.watcher.enabled=true</code> in application.properties and restart.</div>';
        return;
      }
      var data = await res.json();
      var watchers = (data && data.watchers) || [];
      if (!watchers.length) {
        listEl.innerHTML = '<div class="tab-empty">No watchers yet. Create one above.</div>';
        return;
      }
      listEl.innerHTML = watchers.map(function (w) {
        var lastChecked = w.lastCheckedAt ? new Date(w.lastCheckedAt).toLocaleString() : 'never';
        return '<div class="watcher-item" data-id="' + esc(w.id) + '">'
          + '<div class="watcher-item-head">'
          + '<div class="watcher-item-label">' + statusDot(w.lastStatus) + esc(w.label || '(unnamed)') + '</div>'
          + '<div class="watcher-item-actions">'
          + '<button class="action-btn watcher-trigger-btn" data-id="' + esc(w.id) + '">Check now</button>'
          + '<button class="action-btn watcher-delete-btn" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>'
          + '</div></div>'
          + '<div class="watcher-item-meta">'
          + '<span>' + esc(w.adapter) + ' → ' + esc(w.target || '') + '</span> · '
          + '<span>every ' + esc(String(w.intervalSeconds)) + 's</span> · '
          + (w.maxPrice > 0 ? '<span>max ≤ ' + esc(String(w.maxPrice)) + '</span> · ' : '')
          + '<span>email: ' + esc(w.notifyEmail || '(none)') + '</span> · '
          + (w.notifyWebhook ? '<span>webhook ✓</span> · ' : '')
          + '<span>status: ' + esc(w.lastStatus) + '</span> · '
          + '<span>last: ' + esc(lastChecked) + '</span>'
          + '</div>'
          + '<div class="watcher-item-url"><a href="' + esc(w.url) + '" target="_blank" rel="noopener">' + esc(w.url) + '</a></div>'
          + '</div>';
      }).join('');
      wireItemActions();
    } catch (e) {
      listEl.innerHTML = '<div class="tab-empty">Failed to load watchers: ' + esc(e.message) + '</div>';
    }
  }

  function wireItemActions() {
    document.querySelectorAll('.watcher-trigger-btn').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', async function () {
        var id = btn.getAttribute('data-id');
        btn.disabled = true;
        btn.textContent = 'Checking...';
        try {
          var res = await fetch('/api/skills/watcher/' + encodeURIComponent(id) + '/trigger', { method: 'POST' });
          var data = await res.json();
          if (!res.ok) throw new Error(data.error || 'trigger failed');
          setMsg('Checked: status = ' + data.lastStatus, false);
          loadList();
        } catch (e) {
          setMsg('Failed: ' + e.message, true);
          btn.disabled = false;
          btn.textContent = 'Check now';
        }
      });
    });
    document.querySelectorAll('.watcher-delete-btn').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', async function () {
        var id = btn.getAttribute('data-id');
        if (!confirm('Delete this watcher?')) return;
        try {
          var res = await fetch('/api/skills/watcher/' + encodeURIComponent(id), { method: 'DELETE' });
          if (!res.ok) {
            var data = await res.json();
            throw new Error(data.error || 'delete failed');
          }
          setMsg('Deleted.', false);
          loadList();
        } catch (e) {
          setMsg('Failed: ' + e.message, true);
        }
      });
    });
  }

  async function createWatcher() {
    var label = (el('watcher-label').value || '').trim();
    var url = (el('watcher-url').value || '').trim();
    var adapter = el('watcher-adapter').value;
    var target = (el('watcher-target').value || '').trim();
    var email = (el('watcher-email').value || '').trim();
    var interval = parseInt(el('watcher-interval').value || '900', 10);
    var maxPrice = parseFloat(el('watcher-maxprice').value || '0') || 0;
    var webhook = (el('watcher-webhook').value || '').trim();
    if (!url || !adapter || !target) {
      setMsg('url, adapter, and target are required', true);
      return;
    }
    try {
      var res = await fetch('/api/skills/watcher', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          label: label, url: url, adapter: adapter, target: target,
          notifyEmail: email, notifyWebhook: webhook,
          intervalSeconds: interval, maxPrice: maxPrice
        })
      });
      var data = await res.json();
      if (!res.ok) throw new Error(data.error || 'create failed');
      setMsg('Created watcher ' + data.id, false);
      el('watcher-label').value = '';
      el('watcher-url').value = '';
      el('watcher-target').value = '';
      el('watcher-maxprice').value = '';
      el('watcher-webhook').value = '';
      loadList();
    } catch (e) {
      setMsg('Failed: ' + e.message, true);
    }
  }

  window.MinsBotWatcherInit = function () {
    var createBtn = el('watcher-create-btn');
    if (createBtn && !createBtn.__wired) {
      createBtn.__wired = true;
      createBtn.addEventListener('click', createWatcher);
    }
    var refreshBtn = el('watcher-refresh-btn');
    if (refreshBtn && !refreshBtn.__wired) {
      refreshBtn.__wired = true;
      refreshBtn.addEventListener('click', loadList);
    }
    loadList();
  };
})();
