/* Diagnostics tab — fetches /api/diagnostics and renders categorized checks
   with sidebar nav, quick filter chips, and text search. Re-runs on tab open. */
(function () {
  'use strict';

  var listEl, titleEl, subEl, refreshBtn, sidebarEl, filtersEl, searchEl;
  var lastData = null;

  // Filter state
  var filterCategory = 'all';        // all | runtime | local-ai | hardware | network | api-keys
  var filterStatus = 'all';          // all | ok | info | warn | error
  var filterQuery = '';              // lowercased free-text

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c];
    });
  }

  async function load() {
    if (refreshBtn) { refreshBtn.disabled = true; refreshBtn.textContent = 'Running…'; }
    listEl.innerHTML = '<div class="diag-loading">Running checks…</div>';
    try {
      var r = await fetch('/api/diagnostics');
      lastData = await r.json();
      renderAll(lastData);
    } catch (e) {
      listEl.innerHTML = '<div class="diag-loading" style="color:#f87171;">Failed to run diagnostics: ' + esc(e.message) + '</div>';
    } finally {
      if (refreshBtn) { refreshBtn.disabled = false; refreshBtn.textContent = 'Re-run'; }
    }
  }

  function renderAll(data) {
    var summary = (data && data.summary) || {};
    var overall = summary.overall || 'ok';
    var ok = summary.ok || 0, info = summary.info || 0, warn = summary.warn || 0, err = summary.error || 0;
    var total = summary.total || 0;

    var titleMap = { ok: 'All systems go', warn: 'Mostly healthy, with warnings', error: 'Issues need attention' };
    titleEl.textContent = titleMap[overall] || 'Health check complete';
    var parts = [];
    if (ok)   parts.push(ok   + ' OK');
    if (info) parts.push(info + ' info');
    if (warn) parts.push(warn + ' warning' + (warn === 1 ? '' : 's'));
    if (err)  parts.push(err  + ' error'   + (err  === 1 ? '' : 's'));
    parts.push(total + ' checks total');
    subEl.textContent = parts.join(' · ');

    renderSidebar(data);
    renderFilters(data);
    renderList(data);
  }

  // ─── Sidebar (categories + All) ────────────────────────────────

  function renderSidebar(data) {
    var cats = (data && data.categories) || [];
    var checks = (data && data.checks) || [];
    // "All" row comes from totals
    var allOk = 0, allInfo = 0, allWarn = 0, allErr = 0;
    cats.forEach(function (c) {
      allOk += c.ok; allInfo += c.info; allWarn += c.warn; allErr += c.error;
    });

    var items = [];
    items.push({ id: 'all', label: 'All checks', total: checks.length, warn: allWarn, error: allErr });
    cats.forEach(function (c) {
      items.push({ id: c.id, label: c.label, total: c.total, warn: c.warn, error: c.error });
    });

    sidebarEl.innerHTML = items.map(function (it) {
      var dots = '';
      if (it.error) dots += '<span class="cd err"></span>';
      if (it.warn)  dots += '<span class="cd warn"></span>';
      var countHtml = '<span class="count">' + dots + it.total + '</span>';
      return '<button type="button" class="diag-sb-item' + (filterCategory === it.id ? ' active' : '')
        + '" data-cat="' + esc(it.id) + '">'
        + '<span>' + esc(it.label) + '</span>'
        + countHtml
        + '</button>';
    }).join('');

    sidebarEl.querySelectorAll('.diag-sb-item').forEach(function (btn) {
      btn.addEventListener('click', function () {
        filterCategory = btn.getAttribute('data-cat');
        renderSidebar(lastData);
        renderList(lastData);
      });
    });
  }

  // ─── Filter chips (status) ─────────────────────────────────────

  function renderFilters(data) {
    var s = (data && data.summary) || {};
    var items = [
      { id: 'all',   label: 'All',       count: s.total || 0 },
      { id: 'error', label: 'Errors',    count: s.error || 0 },
      { id: 'warn',  label: 'Warnings',  count: s.warn || 0 },
      { id: 'info',  label: 'Info',      count: s.info || 0 },
      { id: 'ok',    label: 'Healthy',   count: s.ok || 0 }
    ];
    var html = '<span class="f-label">Status</span>';
    items.forEach(function (it) {
      // Skip empty status filters (except All) to avoid clutter
      if (it.id !== 'all' && it.count === 0) return;
      html += '<button type="button" class="diag-chip' + (filterStatus === it.id ? ' active' : '')
        + '" data-status="' + it.id + '">' + esc(it.label)
        + '<span class="count">' + it.count + '</span></button>';
    });
    filtersEl.innerHTML = html;
    filtersEl.style.display = 'flex';

    filtersEl.querySelectorAll('.diag-chip').forEach(function (btn) {
      btn.addEventListener('click', function () {
        filterStatus = btn.getAttribute('data-status');
        renderFilters(lastData);
        renderList(lastData);
      });
    });
  }

  // ─── Check list ────────────────────────────────────────────────

  function renderList(data) {
    var checks = (data && data.checks) || [];
    if (!checks.length) {
      listEl.innerHTML = '<div class="diag-loading">No checks returned.</div>';
      return;
    }

    var filtered = checks.filter(matchesFilters);
    if (!filtered.length) {
      listEl.innerHTML = '<div class="diag-empty">No checks match — try clearing a filter or the search.</div>';
      return;
    }

    listEl.innerHTML = filtered.map(function (c) {
      var actionBtn = c.actionEndpoint
        ? '<button type="button" class="fix-btn" data-action="' + esc(c.actionEndpoint) + '" data-id="' + esc(c.id) + '">Fix</button>'
        : '';
      return '<div class="diag-check status-' + esc(c.status) + '" data-id="' + esc(c.id) + '">'
        + '<span class="dot" aria-hidden="true"></span>'
        + '<div class="body">'
        +   '<div class="label">' + esc(c.label) + '</div>'
        +   '<div class="msg">' + esc(c.message || '') + '</div>'
        + '</div>'
        + actionBtn
        + '</div>';
    }).join('');

    listEl.querySelectorAll('.fix-btn[data-action]').forEach(function (btn) {
      btn.addEventListener('click', function () { runFix(btn); });
    });
  }

  function matchesFilters(c) {
    if (filterCategory !== 'all' && c.category !== filterCategory) return false;
    if (filterStatus !== 'all' && c.status !== filterStatus) return false;
    if (filterQuery) {
      var hay = ((c.label || '') + ' ' + (c.message || '') + ' ' + (c.id || '') + ' ' + (c.category || ''))
        .toLowerCase();
      if (hay.indexOf(filterQuery) < 0) return false;
    }
    return true;
  }

  // ─── Actions ───────────────────────────────────────────────────

  async function runFix(btn) {
    var endpoint = btn.getAttribute('data-action');
    btn.disabled = true;
    btn.textContent = 'Running…';
    try {
      var r = await fetch(endpoint, { method: 'POST' });
      await r.json().catch(function () { return {}; });
      // Give the subsystem a moment to settle (Ollama daemon, etc.), then re-run.
      setTimeout(load, 1500);
    } catch (e) {
      btn.textContent = 'Failed';
      setTimeout(function () { btn.disabled = false; btn.textContent = 'Fix'; }, 1500);
    }
  }

  // ─── Init ──────────────────────────────────────────────────────

  window.MinsBotDiagnosticsInit = function () {
    listEl = document.getElementById('diag-list');
    titleEl = document.getElementById('diag-overall-title');
    subEl = document.getElementById('diag-overall-sub');
    refreshBtn = document.getElementById('diag-refresh');
    sidebarEl = document.getElementById('diag-sidebar');
    filtersEl = document.getElementById('diag-filters');
    searchEl = document.getElementById('diag-search');
    if (!listEl || !titleEl) return;

    if (refreshBtn && !refreshBtn.__wired) {
      refreshBtn.__wired = true;
      refreshBtn.addEventListener('click', load);
    }
    if (searchEl && !searchEl.__wired) {
      searchEl.__wired = true;
      searchEl.addEventListener('input', function () {
        filterQuery = searchEl.value.trim().toLowerCase();
        if (lastData) renderList(lastData);
      });
    }
    // Re-run each time the tab opens so the user gets fresh state.
    load();
  };
})();
