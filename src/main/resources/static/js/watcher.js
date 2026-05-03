/* Watcher tab — unified UI for URL / File / Folder watchers.
 *
 * The adapter dropdown carries an encoded "kind:mode" value (e.g. "url:nike-ph",
 * "file:hash", "folder:any"). The kind drives which field group is visible
 * AND which REST endpoint receives the create POST. The list view merges
 * watchers from all three endpoints into one chronological feed.
 */
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

  function statusDot(color) {
    return '<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:'
      + color + ';margin-right:6px;vertical-align:middle;"></span>';
  }

  function dotForKind(kind, statusOrEvent) {
    if (kind === 'url') {
      if (statusOrEvent === 'in-stock') return statusDot('#4ade80');
      if (statusOrEvent === 'out-of-stock') return statusDot('#9ca3af');
      if (String(statusOrEvent || '').startsWith('error')) return statusDot('#f87171');
      return statusDot('#fbbf24');
    }
    // File/Folder: green if recently fired, otherwise muted blue.
    return statusDot(statusOrEvent ? '#60a5fa' : '#9ca3af');
  }

  function parseAdapter() {
    var raw = el('watcher-adapter').value || 'url:nike-ph';
    var i = raw.indexOf(':');
    if (i < 0) return { kind: 'url', mode: raw };
    return { kind: raw.substring(0, i), mode: raw.substring(i + 1) };
  }

  function showKind(kind) {
    document.querySelectorAll('.watcher-kind-group').forEach(function (g) {
      g.hidden = (g.getAttribute('data-kind') !== kind);
    });
    var ad = parseAdapter();
    // File-regex pattern only when file mode is regex.
    var fpat = el('watcher-file-pattern-wrap');
    if (fpat) fpat.hidden = !(ad.kind === 'file' && ad.mode === 'regex');
    // Clipboard regex pattern only when clipboard mode is regex.
    var cpat = el('watcher-clipboard-pattern-wrap');
    if (cpat) cpat.hidden = !(ad.kind === 'clipboard' && ad.mode === 'regex');
  }

  // ─── Listing (merged across all three backends) ──────────────────────

  async function fetchUrl() {
    try {
      var r = await fetch('/api/skills/watcher');
      if (!r.ok) return [];
      var d = await r.json();
      return ((d && d.watchers) || []).map(function (w) { return Object.assign({ __kind: 'url' }, w); });
    } catch (e) { return []; }
  }
  async function fetchFile() {
    try {
      var r = await fetch('/api/skills/watch-file');
      if (!r.ok) return [];
      var d = await r.json();
      return (Array.isArray(d) ? d : []).map(function (w) { return Object.assign({ __kind: 'file' }, w); });
    } catch (e) { return []; }
  }
  async function fetchFolder() {
    try {
      var r = await fetch('/api/skills/watch-folder');
      if (!r.ok) return [];
      var d = await r.json();
      return (Array.isArray(d) ? d : []).map(function (w) { return Object.assign({ __kind: 'folder' }, w); });
    } catch (e) { return []; }
  }
  async function fetchDisk() {
    try {
      var r = await fetch('/api/skills/watch-disk');
      if (!r.ok) return [];
      var d = await r.json();
      return (Array.isArray(d) ? d : []).map(function (w) { return Object.assign({ __kind: 'disk' }, w); });
    } catch (e) { return []; }
  }
  async function fetchHttp() {
    try {
      var r = await fetch('/api/skills/watch-http');
      if (!r.ok) return [];
      var d = await r.json();
      return (Array.isArray(d) ? d : []).map(function (w) { return Object.assign({ __kind: 'http' }, w); });
    } catch (e) { return []; }
  }
  async function fetchClipboard() {
    try {
      var r = await fetch('/api/skills/watch-clipboard');
      if (!r.ok) return [];
      var d = await r.json();
      return (Array.isArray(d) ? d : []).map(function (w) { return Object.assign({ __kind: 'clipboard' }, w); });
    } catch (e) { return []; }
  }

  function renderItem(w) {
    var when = w.lastCheckedAt ? new Date(w.lastCheckedAt).toLocaleString() : 'never';
    var dot, summary, target, actions;
    if (w.__kind === 'url') {
      dot = dotForKind('url', w.lastStatus);
      summary = esc(w.adapter) + ' → ' + esc(w.target || '')
        + ' · every ' + esc(String(w.intervalSeconds)) + 's'
        + (w.maxPrice > 0 ? ' · max ≤ ' + esc(String(w.maxPrice)) : '')
        + ' · status: ' + esc(w.lastStatus || 'unknown');
      target = '<a href="' + esc(w.url) + '" target="_blank" rel="noopener">' + esc(w.url) + '</a>';
      actions = '<button class="action-btn watcher-trigger-btn" data-kind="url" data-id="' + esc(w.id) + '">Check now</button>'
        + '<button class="action-btn watcher-delete-btn" data-kind="url" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
    } else if (w.__kind === 'file') {
      dot = dotForKind('file', w.lastNotifiedAt);
      summary = 'file · ' + esc(w.mode || 'mtime')
        + (w.pattern ? ' (' + esc(w.pattern) + ')' : '')
        + ' · every ' + esc(String(w.intervalSeconds)) + 's'
        + ' · last state: ' + esc(w.lastState || '—');
      target = esc(w.path);
      actions = '<button class="action-btn watcher-trigger-btn" data-kind="file" data-id="' + esc(w.id) + '">Check now</button>'
        + '<button class="action-btn watcher-delete-btn" data-kind="file" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
    } else if (w.__kind === 'folder') {
      dot = dotForKind('folder', w.lastEvent);
      summary = 'folder · ' + esc(w.mode || 'any')
        + (w.filter ? ' · filter ' + esc(w.filter) : '')
        + (w.recursive ? ' · recursive' : '')
        + ' · last event: ' + esc(w.lastEvent || '—');
      target = esc(w.path);
      actions = '<button class="action-btn watcher-delete-btn" data-kind="folder" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
    } else if (w.__kind === 'disk') {
      var diskColor = w.lastState === 'low' ? '#f87171' : (w.lastState === 'ok' ? '#4ade80' : '#fbbf24');
      dot = statusDot(diskColor);
      summary = 'disk · threshold ' + esc(String(w.freeBelowGb)) + ' GB'
        + ' · every ' + esc(String(w.intervalSeconds)) + 's'
        + ' · ' + (w.lastFreeGb ? w.lastFreeGb.toFixed(1) + ' / ' + (w.lastTotalGb || 0).toFixed(1) + ' GB' : '—')
        + ' · state: ' + esc(w.lastState || 'unknown');
      target = esc(w.path);
      actions = '<button class="action-btn watcher-trigger-btn" data-kind="disk" data-id="' + esc(w.id) + '">Check now</button>'
        + '<button class="action-btn watcher-delete-btn" data-kind="disk" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
    } else if (w.__kind === 'http') {
      var httpColor = w.lastState === 'down' ? '#f87171' : (w.lastState === 'up' ? '#4ade80'
        : w.lastState === 'slow' ? '#fb923c' : '#fbbf24');
      dot = statusDot(httpColor);
      summary = 'http · every ' + esc(String(w.intervalSeconds)) + 's'
        + (w.slowAboveMs > 0 ? ' · slow > ' + esc(String(w.slowAboveMs)) + 'ms' : '')
        + (w.lastStatusCode ? ' · last HTTP ' + esc(String(w.lastStatusCode)) + ' in ' + esc(String(w.lastLatencyMs)) + 'ms' : '')
        + ' · state: ' + esc(w.lastState || 'unknown');
      target = '<a href="' + esc(w.url) + '" target="_blank" rel="noopener">' + esc(w.url) + '</a>';
      actions = '<button class="action-btn watcher-trigger-btn" data-kind="http" data-id="' + esc(w.id) + '">Check now</button>'
        + '<button class="action-btn watcher-delete-btn" data-kind="http" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
    } else { // clipboard
      dot = statusDot(w.lastMatchedAt ? '#60a5fa' : '#9ca3af');
      summary = 'clipboard · ' + (w.pattern ? 'regex /' + esc(w.pattern) + '/' : 'any change')
        + ' · every ' + esc(String(w.intervalSeconds)) + 's'
        + (w.lastSnippet ? ' · last: ' + esc(w.lastSnippet) : '');
      target = '';
      actions = '<button class="action-btn watcher-delete-btn" data-kind="clipboard" data-id="' + esc(w.id) + '" style="color:#f87171;border-color:rgba(248,113,113,0.3)">Delete</button>';
    }
    return '<div class="watcher-item" data-id="' + esc(w.id) + '">'
      + '<div class="watcher-item-head">'
      + '<div class="watcher-item-label">' + dot + esc(w.label || '(unnamed)') + '</div>'
      + '<div class="watcher-item-actions">' + actions + '</div></div>'
      + '<div class="watcher-item-meta">' + summary
      + ' · email: ' + esc(w.notifyEmail || '(none)')
      + (w.notifyWebhook ? ' · webhook ✓' : '')
      + ' · last: ' + esc(when)
      + '</div>'
      + '<div class="watcher-item-url">' + target + '</div>'
      + '</div>';
  }

  async function loadList() {
    var listEl = el('watcher-list');
    if (!listEl) return;
    try {
      var batches = await Promise.all([
        fetchUrl(), fetchFile(), fetchFolder(),
        fetchDisk(), fetchHttp(), fetchClipboard()
      ]);
      var all = [].concat.apply([], batches);
      if (!all.length) {
        listEl.innerHTML = '<div class="tab-empty">No watchers yet. Create one above.</div>';
        return;
      }
      // Newest first by createdAt.
      all.sort(function (a, b) { return String(b.createdAt || '').localeCompare(String(a.createdAt || '')); });
      listEl.innerHTML = all.map(renderItem).join('');
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
        var kind = btn.getAttribute('data-kind');
        var id = btn.getAttribute('data-id');
        var endpoint;
        if (kind === 'url')   endpoint = '/api/skills/watcher/' + encodeURIComponent(id) + '/trigger';
        else if (kind === 'file') endpoint = '/api/skills/watch-file/' + encodeURIComponent(id) + '/check';
        else if (kind === 'disk') endpoint = '/api/skills/watch-disk/' + encodeURIComponent(id) + '/check';
        else if (kind === 'http') endpoint = '/api/skills/watch-http/' + encodeURIComponent(id) + '/check';
        else { setMsg('Check not supported for this kind', true); return; }
        btn.disabled = true; btn.textContent = 'Checking...';
        try {
          var res = await fetch(endpoint, { method: 'POST' });
          var data = await res.json();
          if (!res.ok) throw new Error(data.error || 'check failed');
          setMsg('Checked: ' + (data.lastStatus || data.status || 'ok'), false);
          loadList();
        } catch (e) {
          setMsg('Failed: ' + e.message, true);
          btn.disabled = false; btn.textContent = 'Check now';
        }
      });
    });
    document.querySelectorAll('.watcher-delete-btn').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', async function () {
        var kind = btn.getAttribute('data-kind');
        var id = btn.getAttribute('data-id');
        var base = kind === 'url' ? '/api/skills/watcher'
          : kind === 'file' ? '/api/skills/watch-file'
          : kind === 'folder' ? '/api/skills/watch-folder'
          : kind === 'disk' ? '/api/skills/watch-disk'
          : kind === 'http' ? '/api/skills/watch-http'
          : '/api/skills/watch-clipboard';
        try {
          var res = await fetch(base + '/' + encodeURIComponent(id), { method: 'DELETE' });
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

  // ─── Create ─────────────────────────────────────────────────────────

  async function createWatcher() {
    var ad = parseAdapter();
    var label = (el('watcher-label').value || '').trim();
    var email = (el('watcher-email').value || '').trim();
    var webhook = (el('watcher-webhook').value || '').trim();

    if (ad.kind === 'url') {
      var url = (el('watcher-url').value || '').trim();
      var target = (el('watcher-target').value || '').trim();
      var interval = parseInt(el('watcher-interval').value || '900', 10);
      var maxPrice = parseFloat(el('watcher-maxprice').value || '0') || 0;
      if (!url || !target) { setMsg('URL and target are required', true); return; }
      return postJson('/api/skills/watcher', {
        label: label, url: url, adapter: ad.mode, target: target,
        notifyEmail: email, notifyWebhook: webhook,
        intervalSeconds: interval, maxPrice: maxPrice
      });
    }
    if (ad.kind === 'file') {
      var path = (el('watcher-file-path').value || '').trim();
      var pattern = (el('watcher-file-pattern').value || '').trim();
      var fInterval = parseInt(el('watcher-file-interval').value || '60', 10);
      if (!path) { setMsg('File path is required', true); return; }
      if (ad.mode === 'regex' && !pattern) { setMsg('Regex pattern is required for regex mode', true); return; }
      return postJson('/api/skills/watch-file', {
        label: label, path: path, mode: ad.mode, pattern: pattern,
        notifyEmail: email, notifyWebhook: webhook,
        intervalSeconds: fInterval
      });
    }
    if (ad.kind === 'folder') {
      var fPath = (el('watcher-folder-path').value || '').trim();
      var filter = (el('watcher-folder-filter').value || '').trim();
      var recursive = !!el('watcher-folder-recursive').checked;
      if (!fPath) { setMsg('Folder path is required', true); return; }
      return postJson('/api/skills/watch-folder', {
        label: label, path: fPath, mode: ad.mode, filter: filter, recursive: recursive,
        notifyEmail: email, notifyWebhook: webhook
      });
    }
    if (ad.kind === 'disk') {
      var dPath = (el('watcher-disk-path').value || '').trim();
      var threshold = parseFloat(el('watcher-disk-threshold').value || '10') || 10;
      var dInterval = parseInt(el('watcher-disk-interval').value || '300', 10);
      if (!dPath) { setMsg('Volume path is required', true); return; }
      return postJson('/api/skills/watch-disk', {
        label: label, path: dPath, freeBelowGb: threshold,
        notifyEmail: email, notifyWebhook: webhook,
        intervalSeconds: dInterval
      });
    }
    if (ad.kind === 'http') {
      var hUrl = (el('watcher-http-url').value || '').trim();
      var slow = parseInt(el('watcher-http-slow').value || '0', 10) || 0;
      var hInterval = parseInt(el('watcher-http-interval').value || '300', 10);
      if (!hUrl) { setMsg('URL is required', true); return; }
      return postJson('/api/skills/watch-http', {
        label: label, url: hUrl, slowAboveMs: slow,
        notifyEmail: email, notifyWebhook: webhook,
        intervalSeconds: hInterval
      });
    }
    if (ad.kind === 'clipboard') {
      var pat = (el('watcher-clipboard-pattern').value || '').trim();
      var cInterval = parseInt(el('watcher-clipboard-interval').value || '5', 10);
      if (ad.mode === 'regex' && !pat) {
        setMsg('Regex pattern is required for clipboard regex mode', true); return;
      }
      return postJson('/api/skills/watch-clipboard', {
        label: label, pattern: ad.mode === 'regex' ? pat : '',
        notifyEmail: email, notifyWebhook: webhook,
        intervalSeconds: cInterval
      });
    }
  }

  async function postJson(url, body) {
    try {
      var res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      var data = await res.json();
      if (!res.ok) throw new Error(data.error || ('POST ' + url + ' failed'));
      setMsg('Created watcher ' + (data.id || ''), false);
      // Reset only the fields owned by the active kind so a follow-up create
      // for the same kind doesn't lose context (label/email/webhook stay).
      ['watcher-url','watcher-target','watcher-maxprice',
       'watcher-file-path','watcher-file-pattern',
       'watcher-folder-path','watcher-folder-filter',
       'watcher-disk-path','watcher-http-url',
       'watcher-clipboard-pattern']
        .forEach(function (id) { var n = el(id); if (n) n.value = ''; });
      var rec = el('watcher-folder-recursive'); if (rec) rec.checked = false;
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
    var adapterSel = el('watcher-adapter');
    if (adapterSel && !adapterSel.__wired) {
      adapterSel.__wired = true;
      adapterSel.addEventListener('change', function () { showKind(parseAdapter().kind); });
    }
    showKind(parseAdapter().kind);
    loadList();
  };
})();
