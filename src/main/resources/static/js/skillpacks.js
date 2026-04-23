/* Skill Packs tab — fetches /api/skill-packs and renders compatible skills as
   cards with OS / prereq / install-kind badges. Install button streams SSE
   progress inline on the card (same pattern as the Piper voice installer). */
(function () {
  'use strict';

  var listEl, titleEl, subEl, rescanBtn, importBtn, sidebarEl, filtersEl, searchEl;
  var data = null;              // { currentOs, skills: [...], count }
  var activeInstalls = new Map(); // name -> EventSource

  // Filter state
  var filterKind = 'all';  // all | installable | installed | not-ready | incompatible
  var filterQuery = '';

  function el(id) { return document.getElementById(id); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c];
    });
  }

  function toast(msg, kind) {
    var t = document.createElement('div');
    t.className = 'toast' + (kind ? ' ' + kind : '');
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 3500);
  }

  async function load() {
    if (rescanBtn) { rescanBtn.disabled = true; rescanBtn.textContent = 'Loading…'; }
    listEl.innerHTML = '<div class="skp-loading">Loading…</div>';
    try {
      var r = await fetch('/api/skill-packs');
      data = await r.json();
      renderAll();
    } catch (e) {
      listEl.innerHTML = '<div class="skp-loading" style="color:#f87171;">Failed: ' + esc(e.message) + '</div>';
    } finally {
      if (rescanBtn) { rescanBtn.disabled = false; rescanBtn.textContent = 'Rescan'; }
    }
  }

  async function rescan() {
    try {
      await fetch('/api/skill-packs/rescan', { method: 'POST' });
      toast('Skill packs rescanned', 'ok');
    } catch (e) { /* ignore */ }
    await load();
  }

  function renderAll() {
    var skills = (data && data.skills) || [];
    var os = (data && data.currentOs) || 'unknown';
    var counts = categorize(skills);

    titleEl.textContent = skills.length ? (skills.length + ' skill pack' + (skills.length === 1 ? '' : 's') + ' discovered') : 'No skill packs found';
    subEl.textContent = counts.installable + ' ready · ' + counts.notReady + ' need prereqs · '
        + counts.incompatible + ' incompatible (' + os + ')';

    renderSidebar(counts);
    renderFilters(counts);
    renderList(skills);
  }

  function categorize(skills) {
    var c = { all: skills.length, installable: 0, installed: 0, notReady: 0, incompatible: 0 };
    skills.forEach(function (s) {
      if (s.osIncompatible) c.incompatible++;
      else if (s.prereqOk) c.installable++;
      else c.notReady++;
    });
    return c;
  }

  function renderSidebar(c) {
    var items = [
      { id: 'all',          label: 'All skills',       count: c.all },
      { id: 'installable',  label: 'Ready to use',     count: c.installable },
      { id: 'not-ready',    label: 'Needs prereqs',    count: c.notReady },
      { id: 'incompatible', label: 'Incompatible OS',  count: c.incompatible }
    ];
    sidebarEl.innerHTML = items.map(function (it) {
      return '<button type="button" class="skp-sb-item' + (filterKind === it.id ? ' active' : '')
        + '" data-kind="' + esc(it.id) + '">'
        + '<span>' + esc(it.label) + '</span>'
        + '<span class="count">' + it.count + '</span>'
        + '</button>';
    }).join('');
    sidebarEl.querySelectorAll('.skp-sb-item').forEach(function (btn) {
      btn.addEventListener('click', function () {
        filterKind = btn.getAttribute('data-kind');
        renderSidebar(c);
        renderList((data && data.skills) || []);
      });
    });
  }

  function renderFilters(c) {
    // One-liner quick toggles along the top — currently mirror the sidebar for discoverability.
    var chips = [
      { id: 'all',          label: 'All',            count: c.all },
      { id: 'installable',  label: 'Ready',          count: c.installable },
      { id: 'not-ready',    label: 'Prereqs missing', count: c.notReady }
    ];
    filtersEl.innerHTML = '<span class="f-label">Quick filter</span>'
      + chips.map(function (it) {
          if (it.id !== 'all' && it.count === 0) return '';
          return '<button type="button" class="skp-chip' + (filterKind === it.id ? ' active' : '')
            + '" data-kind="' + it.id + '">' + esc(it.label)
            + '<span class="count">' + it.count + '</span></button>';
        }).join('');
    filtersEl.style.display = 'flex';
    filtersEl.querySelectorAll('.skp-chip').forEach(function (btn) {
      btn.addEventListener('click', function () {
        filterKind = btn.getAttribute('data-kind');
        renderAll();
      });
    });
  }

  function renderList(skills) {
    if (!skills.length) {
      listEl.innerHTML = '<div class="skp-empty">No skill packs in <code>~/mins_bot_data/skill_packs/</code>. '
        + 'Drop any skill folder (with a SKILL.md) there and click Rescan.</div>';
      return;
    }
    var filtered = skills.filter(matchesFilters);
    if (!filtered.length) {
      listEl.innerHTML = '<div class="skp-empty">No skill packs match. Try clearing filters or the search.</div>';
      return;
    }
    listEl.innerHTML = filtered.map(renderCard).join('');
    wireCards();
  }

  function matchesFilters(s) {
    if (filterKind === 'installable' && (!s.prereqOk || s.osIncompatible)) return false;
    if (filterKind === 'not-ready'    && (s.prereqOk || s.osIncompatible))  return false;
    if (filterKind === 'incompatible' && !s.osIncompatible)                 return false;
    if (filterQuery) {
      var hay = ((s.name || '') + ' ' + (s.description || '') + ' '
                 + (s.requiresBins || []).join(' ') + ' ' + (s.primaryEnv || '')).toLowerCase();
      if (hay.indexOf(filterQuery) < 0) return false;
    }
    return true;
  }

  function renderCard(s) {
    var cardClass = s.osIncompatible ? 'incompatible' : (s.prereqOk ? 'ready' : 'not-ready');
    var emoji = s.emoji ? '<span class="emoji">' + esc(s.emoji) + '</span>' : '';
    var badges = buildBadges(s);

    var actions = '';
    if (s.osIncompatible) {
      actions = '<span style="color:rgba(255,255,255,0.5);font-size:12px;">OS: '
        + esc((s.os || []).join(', ') || 'unknown') + '</span>';
    } else if (s.prereqOk) {
      actions = '<button class="btn ghost" data-view="' + esc(s.name) + '">View instructions</button>';
      if (s.homepage) {
        actions += '<a class="btn ghost" href="' + esc(s.homepage) + '" target="_blank" rel="noopener noreferrer">Homepage</a>';
      }
    } else {
      if (s.installable) {
        actions = '<button class="btn" data-install="' + esc(s.name) + '">Install prereqs</button>';
      } else {
        actions = '<span style="color:rgba(251,191,36,0.9);font-size:12px;">Manual install required</span>';
      }
      if (s.homepage) {
        actions += '<a class="btn ghost" href="' + esc(s.homepage) + '" target="_blank" rel="noopener noreferrer">Homepage</a>';
      }
    }

    return '<div class="skp-card ' + cardClass + '" id="skp-' + cardId(s.name) + '" data-name="' + esc(s.name) + '">'
      + '<div class="row1"><div class="name">' + emoji + ' ' + esc(s.name) + '</div></div>'
      + '<div class="desc">' + esc(s.description || '') + '</div>'
      + '<div class="skp-badges">' + badges + '</div>'
      + '<div class="skp-progress-slot"></div>'
      + '<div class="skp-actions-row">' + actions + '</div>'
      + '</div>';
  }

  function buildBadges(s) {
    var out = [];
    if (s.osIncompatible) {
      out.push('<span class="skp-badge err">OS: ' + esc((s.os || []).join(', ') || 'unknown') + '</span>');
    } else if (s.os && s.os.length) {
      out.push('<span class="skp-badge neutral">' + esc(s.os.join(', ')) + '</span>');
    } else {
      out.push('<span class="skp-badge neutral">cross-platform</span>');
    }
    if ((s.requiresBins || []).length) {
      var missing = s.missingBins || [];
      if (missing.length === 0) {
        out.push('<span class="skp-badge ok">✓ ' + esc((s.requiresBins || []).join(', ')) + '</span>');
      } else {
        out.push('<span class="skp-badge warn">needs: ' + esc(missing.join(', ')) + '</span>');
      }
    }
    if ((s.requiresEnv || []).length) {
      var missingEnv = s.missingEnv || [];
      if (missingEnv.length === 0) {
        out.push('<span class="skp-badge ok">$' + esc((s.requiresEnv || []).join(', $')) + '</span>');
      } else {
        out.push('<span class="skp-badge warn">env: ' + esc(missingEnv.map(function (e) { return '$' + e; }).join(', ')) + '</span>');
      }
    }
    if (s.installable && !s.prereqOk) {
      out.push('<span class="skp-badge neutral">install via ' + esc((s.installKinds || []).join(', ')) + '</span>');
    }
    return out.join('');
  }

  function cardId(name) { return String(name || '').replace(/[^a-z0-9]/gi, '-'); }

  function wireCards() {
    listEl.querySelectorAll('[data-install]').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', function () { startInstall(btn.getAttribute('data-install')); });
    });
    listEl.querySelectorAll('[data-view]').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', function () { openDetail(btn.getAttribute('data-view')); });
    });
  }

  function startInstall(name) {
    if (activeInstalls.has(name)) return;
    var card = el('skp-' + cardId(name));
    if (!card) return;

    var slot = card.querySelector('.skp-progress-slot');
    slot.innerHTML = '<div class="progress"><div class="progress-fill"></div></div>'
      + '<div class="progress-text">Starting…</div>';
    var fill = slot.querySelector('.progress-fill');
    var text = slot.querySelector('.progress-text');
    var actions = card.querySelector('.skp-actions-row');
    actions.innerHTML = '<button class="btn ghost" id="cancel-' + cardId(name) + '">Cancel</button>';

    var es = new EventSource('/api/skill-packs/' + encodeURIComponent(name) + '/install');
    activeInstalls.set(name, es);

    el('cancel-' + cardId(name)).addEventListener('click', function () {
      es.close();
      activeInstalls.delete(name);
      slot.innerHTML = '';
      toast('Cancelled', 'ok');
      load();
    });

    var logBuf = '';

    es.addEventListener('phase', function (ev) {
      try { var d = JSON.parse(ev.data); text.textContent = d.message || d.phase || '…'; } catch (e) {}
    });
    es.addEventListener('log', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        logBuf += (d.message || '') + '\n';
        // Trim to last ~2 KB so the card doesn't explode
        if (logBuf.length > 2000) logBuf = logBuf.slice(-2000);
        text.textContent = logBuf;
        // Nudge the progress bar ahead a little for each log line to show life.
        var curr = parseFloat(fill.style.width) || 0;
        fill.style.width = Math.min(90, curr + 3) + '%';
      } catch (e) {}
    });
    es.addEventListener('done', function () {
      es.close();
      activeInstalls.delete(name);
      fill.style.width = '100%';
      toast('Installed: ' + name, 'ok');
      setTimeout(load, 500);
    });
    es.addEventListener('error', function (ev) {
      es.close();
      activeInstalls.delete(name);
      var msg = 'Install failed.';
      if (ev && ev.data) { try { var d = JSON.parse(ev.data); msg = d.error || msg; } catch (e) {} }
      text.textContent = msg;
      toast(msg, 'err');
      // Re-render this card's actions
      setTimeout(load, 1200);
    });
  }

  async function openDetail(name) {
    var slot = el('skp-modal-slot');
    if (!slot) return;
    slot.innerHTML = '<div class="modal-backdrop" id="skp-modal-bd"><div class="modal">'
      + '<h3>' + esc(name) + '</h3>'
      + '<div class="modal-sub">Loading instructions…</div></div></div>';
    try {
      var r = await fetch('/api/skill-packs/' + encodeURIComponent(name));
      var d = await r.json();
      var body = (d.body || '').replace(/</g, '&lt;');
      slot.innerHTML = '<div class="modal-backdrop" id="skp-modal-bd"><div class="modal" style="max-width:720px;">'
        + '<h3>' + esc(d.emoji ? d.emoji + ' ' : '') + esc(d.name) + '</h3>'
        + '<div class="modal-sub">' + esc(d.description || '') + '</div>'
        + '<pre style="white-space:pre-wrap;font-size:12px;line-height:1.5;margin-top:14px;max-height:60vh;overflow:auto;background:var(--bg,rgba(0,0,0,0.25));padding:12px;border-radius:8px;border:1px solid rgba(255,255,255,0.08);">'
        + body + '</pre>'
        + '<div class="modal-actions"><button class="btn" id="skp-modal-close">Close</button></div>'
        + '</div></div>';
      var close = function () { slot.innerHTML = ''; };
      el('skp-modal-close').addEventListener('click', close);
      el('skp-modal-bd').addEventListener('click', function (e) { if (e.target.id === 'skp-modal-bd') close(); });
    } catch (e) {
      slot.innerHTML = '<div class="modal-backdrop"><div class="modal">Failed: ' + esc(e.message) + '</div></div>';
    }
  }

  function openImportModal() {
    var slot = el('skp-modal-slot');
    if (!slot) return;
    slot.innerHTML = '<div class="modal-backdrop" id="skp-import-bd"><div class="modal" style="max-width:540px;">'
      + '<h3>Import a skill pack</h3>'
      + '<div class="modal-sub">Paste a URL to a raw <code>skill.md</code>, a <code>.zip</code> archive, '
      + 'or a github.com repo / tree URL. The skill installs into <code>~/mins_bot_data/skill_packs/</code>.</div>'
      + '<input type="text" id="skp-import-url" class="tab-input" '
      + 'placeholder="https://github.com/user/repo/tree/main/skills/my-skill" '
      + 'style="margin-top:14px;width:100%;padding:8px 10px;background:rgba(255,255,255,0.04);'
      + 'border:1px solid rgba(255,255,255,0.1);border-radius:8px;color:#f4f4f5;font:inherit;">'
      + '<div id="skp-import-status" class="modal-sub" style="margin-top:10px;min-height:18px;"></div>'
      + '<div class="modal-actions" style="display:flex;gap:8px;justify-content:flex-end;margin-top:18px;">'
      + '<button class="btn ghost" id="skp-import-cancel">Cancel</button>'
      + '<button class="btn" id="skp-import-go">Import</button>'
      + '</div>'
      + '</div></div>';

    var close = function () { slot.innerHTML = ''; };
    var urlEl = el('skp-import-url');
    var statusEl = el('skp-import-status');
    var goBtn = el('skp-import-go');
    var cancelBtn = el('skp-import-cancel');

    cancelBtn.addEventListener('click', close);
    el('skp-import-bd').addEventListener('click', function (e) { if (e.target.id === 'skp-import-bd') close(); });
    urlEl.focus();
    urlEl.addEventListener('keydown', function (e) { if (e.key === 'Enter') goBtn.click(); });

    goBtn.addEventListener('click', async function () {
      var url = (urlEl.value || '').trim();
      if (!url) { statusEl.textContent = 'Enter a URL first.'; return; }
      goBtn.disabled = true;
      cancelBtn.disabled = true;
      statusEl.textContent = 'Importing… this can take up to 60s for large zips.';
      statusEl.style.color = 'rgba(255,255,255,0.6)';
      try {
        var r = await fetch('/api/skill-packs/import', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: url })
        });
        var d = await r.json();
        if (r.ok && d.ok) {
          statusEl.textContent = 'Installed "' + d.name + '" → ' + d.path;
          statusEl.style.color = '#86efac';
          toast('Imported: ' + d.name, 'ok');
          setTimeout(function () { close(); load(); }, 900);
        } else {
          statusEl.textContent = d.error || 'Import failed.';
          statusEl.style.color = '#f87171';
          goBtn.disabled = false;
          cancelBtn.disabled = false;
        }
      } catch (e) {
        statusEl.textContent = 'Network error: ' + e.message;
        statusEl.style.color = '#f87171';
        goBtn.disabled = false;
        cancelBtn.disabled = false;
      }
    });
  }

  window.MinsBotSkillPacksInit = function () {
    listEl = el('skp-list');
    titleEl = el('skp-overall-title');
    subEl = el('skp-overall-sub');
    rescanBtn = el('skp-rescan');
    importBtn = el('skp-import');
    sidebarEl = el('skp-sidebar');
    filtersEl = el('skp-filters');
    searchEl = el('skp-search');
    if (!listEl || !titleEl) return;

    if (rescanBtn && !rescanBtn.__wired) {
      rescanBtn.__wired = true;
      rescanBtn.addEventListener('click', rescan);
    }
    if (importBtn && !importBtn.__wired) {
      importBtn.__wired = true;
      importBtn.addEventListener('click', openImportModal);
    }
    if (searchEl && !searchEl.__wired) {
      searchEl.__wired = true;
      searchEl.addEventListener('input', function () {
        filterQuery = searchEl.value.trim().toLowerCase();
        if (data) renderList(data.skills || []);
      });
    }
    load();
  };
})();
