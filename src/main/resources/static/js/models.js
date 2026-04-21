/* Local Models page — curated Ollama + ComfyUI catalog with install/remove,
   live pull progress, GPU-aware badges, and quick filters.
   Resumes previously interrupted Ollama installs (Ollama's /api/pull is idempotent —
   pulling a partial model picks up from where it stopped). */
(function () {
  'use strict';

  var installed = new Map(); // tag (normalized) -> { sizeBytes, rawTag }
  var activePulls = new Map(); // tag -> EventSource
  var defaultModelTag = null; // e.g. "llama3.2:3b"
  var gpuInfo = { available: false, vendor: 'none', vramGb: 0, name: '' };
  var comfyUiRunning = false;
  var catalogCache = null;

  // Filter state. Only one "kind" chip active at a time; gpu/installed chips toggleable.
  var filterKind = 'all';         // all | llm | vision | code | embed | image
  var filterInstalled = false;
  var filterFitsGpu = false;
  var filterNoGpu = false;

  function el(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]; }); }

  function fmtBytes(n) {
    if (!n || n <= 0) return '';
    if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(0) + ' MB';
    return (n / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  function normalizeTag(t) {
    if (!t) return '';
    return t.indexOf(':') === -1 ? t + ':latest' : t;
  }

  function toast(msg, kind) {
    var t = document.createElement('div');
    t.className = 'toast' + (kind ? ' ' + kind : '');
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 3500);
  }

  async function loadAll() {
    var [setup, tagsResp, catalog] = await Promise.all([
      fetch('/api/setup/status').then(function (r) { return r.json(); }).catch(function () { return {}; }),
      fetch('/api/setup/installed-models').then(function (r) { return r.json(); }).catch(function () { return {}; }),
      fetch('/api/setup/catalog').then(function (r) { return r.json(); }).catch(function () { return {}; })
    ]);

    installed.clear();
    var models = (tagsResp && tagsResp.models) ? tagsResp.models : [];
    if (Array.isArray(models)) {
      models.forEach(function (m) {
        installed.set(normalizeTag(m.name), { sizeBytes: m.size, rawTag: m.name });
      });
    }
    defaultModelTag = setup.defaultModel || null;
    gpuInfo = setup.gpu || { available: false, vendor: 'none', vramGb: 0, name: '' };
    comfyUiRunning = !!setup.comfyUiRunning;
    catalogCache = catalog || { categories: [], comingSoon: [] };

    renderSummary(setup, models);
    renderBanner(setup);
    renderComfyUiBanner();
    renderFilters();
    renderCatalog(catalogCache, models);
    renderComingSoon(catalogCache);
    applyFilters();
  }

  function renderSummary(setup, models) {
    var totalBytes = (models || []).reduce(function (a, m) { return a + (m.size || 0); }, 0);
    var gpuVal = gpuInfo.available
      ? (gpuInfo.name || 'NVIDIA GPU')
      : 'None detected';
    var gpuSub = gpuInfo.available
      ? gpuInfo.vramGb + ' GB VRAM'
      : 'CPU-only mode';
    var rows = [
      { label: 'Ollama',
        value: setup.ollamaRunning ? 'Running' : (setup.ollamaInstalled ? 'Installed' : 'Not installed'),
        sub: setup.ollamaRunning ? 'http://localhost:11434' : (setup.ollamaInstalled ? 'stopped — start it' : 'click Install below'),
        dot: setup.ollamaRunning ? 'ok' : (setup.ollamaInstalled ? 'warn' : 'err') },
      { label: 'ComfyUI',
        value: comfyUiRunning ? 'Running' : 'Not running',
        sub: comfyUiRunning ? 'http://localhost:8188' : 'needed for image generation',
        dot: comfyUiRunning ? 'ok' : 'warn' },
      { label: 'GPU',
        value: gpuVal,
        sub: gpuSub,
        dot: gpuInfo.available ? 'ok' : 'warn' },
      { label: 'Installed models', value: String((models || []).length), sub: totalBytes ? fmtBytes(totalBytes) + ' on disk' : 'none yet', dot: (models || []).length > 0 ? 'ok' : 'warn' },
      { label: 'Default model',
        value: setup.defaultModel || '—',
        sub: setup.defaultModelReady ? 'ready' : 'click to download',
        dot: setup.defaultModelReady ? 'ok' : 'warn',
        action: (!setup.defaultModelReady && setup.ollamaRunning && setup.defaultModel) ? 'install-default' : null }
    ];
    el('summary').innerHTML = rows.map(function (r) {
      var clickAttrs = r.action ? ' data-action="' + r.action + '" style="cursor:pointer;"' : '';
      return '<div class="sum-card"' + clickAttrs + '>'
        + '<div class="lbl">' + esc(r.label) + '</div>'
        + '<div class="val"><span class="dot ' + r.dot + '"></span>' + esc(r.value) + '</div>'
        + '<div class="sub">' + esc(r.sub) + '</div>'
        + '</div>';
    }).join('');
    var defCard = el('summary').querySelector('[data-action="install-default"]');
    if (defCard) {
      defCard.addEventListener('click', function () {
        if (!defaultModelTag) return;
        var target = document.getElementById('card-' + cardId(defaultModelTag));
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        startPull(defaultModelTag);
      });
    }
  }

  function renderBanner(setup) {
    var slot = el('ollama-banner-slot');
    if (setup.ollamaRunning) { slot.innerHTML = ''; return; }
    if (!setup.ollamaInstalled) {
      slot.innerHTML = '<div class="ollama-banner err">'
        + '<div class="msg"><strong>Ollama is not installed.</strong> Install it to download and run local models. '
        + 'This uses <code>winget</code> on Windows and takes about a minute.</div>'
        + '<button class="btn" id="install-ollama-btn">Install Ollama</button>'
        + '</div>';
      el('install-ollama-btn').addEventListener('click', installOllama);
    } else {
      slot.innerHTML = '<div class="ollama-banner">'
        + '<div class="msg"><strong>Ollama is installed but not running.</strong> '
        + 'Start it from a terminal: <code>ollama serve</code>, then <a href="#" id="refresh-link">refresh</a>.</div>'
        + '</div>';
      el('refresh-link').addEventListener('click', function (e) { e.preventDefault(); loadAll(); });
    }
  }

  function renderComfyUiBanner() {
    var slot = el('comfyui-banner-slot');
    if (!slot) return;
    // Only show if user has image-gen models in the catalog AND comfy isn't running
    var hasImageGen = catalogHasKind('image');
    if (!hasImageGen || comfyUiRunning) { slot.innerHTML = ''; return; }
    slot.innerHTML = '<div class="ollama-banner">'
      + '<div class="msg"><strong>ComfyUI is not running.</strong> '
      + 'Image-generation models need <a href="https://github.com/comfyanonymous/ComfyUI" target="_blank" rel="noopener">ComfyUI</a> '
      + 'on <code>localhost:8188</code>. Click any image model below for setup instructions.</div>'
      + '<a href="#" class="btn" id="comfy-refresh-link">Refresh</a>'
      + '</div>';
    el('comfy-refresh-link').addEventListener('click', function (e) { e.preventDefault(); loadAll(); });
  }

  function catalogHasKind(kind) {
    if (!catalogCache || !catalogCache.categories) return false;
    return catalogCache.categories.some(function (c) { return c.kind === kind; });
  }

  async function installOllama() {
    var btn = el('install-ollama-btn');
    if (!btn) return;
    btn.disabled = true; btn.textContent = 'Installing…';
    try {
      var r = await fetch('/api/setup/install-ollama', { method: 'POST' });
      var data = await r.json();
      if (!r.ok) { toast(data.error || 'Install failed', 'err'); return; }
      toast('Ollama installed', 'ok');
    } catch (e) {
      toast('Install failed: ' + e.message, 'err');
    } finally {
      btn.disabled = false; btn.textContent = 'Install Ollama';
      await loadAll();
    }
  }

  // ═══ Filters ═══════════════════════════════════════════════════

  function renderFilters() {
    var kinds = [
      { id: 'all',    label: 'All' },
      { id: 'llm',    label: 'LLM' },
      { id: 'vision', label: 'Vision' },
      { id: 'code',   label: 'Code' },
      { id: 'image',  label: 'Image' },
      { id: 'embed',  label: 'Embed' }
    ];
    var gpuChipLabel = gpuInfo.available
      ? 'Fits my GPU (' + gpuInfo.vramGb + ' GB)'
      : 'No GPU needed';
    var html = '<span class="filter-label">Type</span>';
    kinds.forEach(function (k) {
      var count = countByKind(k.id);
      if (k.id !== 'all' && count === 0) return;
      html += '<button class="chip kind' + (filterKind === k.id ? ' active' : '')
        + '" data-kind="' + k.id + '">' + esc(k.label)
        + '<span class="count">' + count + '</span></button>';
    });
    html += '<span class="filter-label" style="margin-left:8px;">Show</span>';
    html += '<button class="chip flag' + (filterInstalled ? ' active' : '')
      + '" data-flag="installed">Installed</button>';
    if (gpuInfo.available) {
      html += '<button class="chip flag' + (filterFitsGpu ? ' active' : '')
        + '" data-flag="fitsGpu">' + esc(gpuChipLabel) + '</button>';
    }
    html += '<button class="chip flag' + (filterNoGpu ? ' active' : '')
      + '" data-flag="noGpu">No GPU needed</button>';

    var f = el('filters');
    f.innerHTML = html;
    f.style.display = 'flex';
    f.querySelectorAll('.chip.kind').forEach(function (btn) {
      btn.addEventListener('click', function () {
        filterKind = btn.getAttribute('data-kind');
        renderFilters();
        applyFilters();
      });
    });
    f.querySelectorAll('.chip.flag').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var flag = btn.getAttribute('data-flag');
        if (flag === 'installed') filterInstalled = !filterInstalled;
        else if (flag === 'fitsGpu') filterFitsGpu = !filterFitsGpu;
        else if (flag === 'noGpu') filterNoGpu = !filterNoGpu;
        renderFilters();
        applyFilters();
      });
    });
  }

  function countByKind(kind) {
    if (!catalogCache || !catalogCache.categories) return 0;
    if (kind === 'all') {
      return catalogCache.categories.reduce(function (a, c) { return a + (c.models || []).length; }, 0);
    }
    return catalogCache.categories
      .filter(function (c) { return c.kind === kind; })
      .reduce(function (a, c) { return a + (c.models || []).length; }, 0);
  }

  function cardMatchesFilters(card) {
    var kind = card.getAttribute('data-kind') || '';
    var backend = card.getAttribute('data-backend') || '';
    var requiresGpu = card.getAttribute('data-requires-gpu') === 'true';
    var minVram = parseInt(card.getAttribute('data-min-vram') || '0', 10);
    var isInst = card.getAttribute('data-installed') === 'true';

    if (filterKind !== 'all' && kind && kind !== filterKind) return false;
    if (filterInstalled && !isInst) return false;
    if (filterNoGpu && requiresGpu) return false;
    if (filterFitsGpu) {
      if (!gpuInfo.available) return false;
      if (requiresGpu && minVram > gpuInfo.vramGb) return false;
    }
    return true;
  }

  function applyFilters() {
    var shown = 0;
    document.querySelectorAll('section.cat').forEach(function (section) {
      var cards = section.querySelectorAll('.model');
      var visibleHere = 0;
      cards.forEach(function (card) {
        var ok = cardMatchesFilters(card);
        card.style.display = ok ? '' : 'none';
        if (ok) { visibleHere++; shown++; }
      });
      section.style.display = visibleHere === 0 ? 'none' : '';
    });
    // Empty state
    var existing = document.getElementById('empty-state');
    if (shown === 0) {
      if (!existing) {
        var div = document.createElement('div');
        div.id = 'empty-state';
        div.className = 'empty-state';
        div.innerHTML = 'No models match these filters.'
          + '<div class="em-hint">Try clearing a filter or switching to <strong>All</strong>.</div>';
        el('catalog').appendChild(div);
      }
    } else if (existing) {
      existing.remove();
    }
  }

  // ═══ Catalog rendering ═══════════════════════════════════════════

  function renderCatalog(catalog, installedList) {
    var cats = (catalog && catalog.categories) || [];
    if (!cats.length) { el('catalog').innerHTML = '<div class="loading">Catalog unavailable.</div>'; return; }

    var catalogTags = new Set();
    cats.forEach(function (cat) {
      cat.models.forEach(function (m) { catalogTags.add(normalizeTag(m.tag)); });
    });

    var installedHtml = '';
    if (installedList && installedList.length) {
      var cards = installedList.map(function (om) {
        var norm = normalizeTag(om.name);
        var inCatalog = catalogTags.has(norm);
        var sizeLabel = om.size ? fmtBytes(om.size) : '';
        if (inCatalog) {
          return '<div class="model installed installed-chip" data-kind="llm" data-backend="ollama" '
            + 'data-requires-gpu="false" data-min-vram="0" data-installed="true" '
            + 'data-jump="' + esc(om.name) + '">'
            + '<div class="row1"><div class="name">' + esc(om.name) + '</div><div class="size">' + esc(sizeLabel) + '</div></div>'
            + '<div class="desc" style="min-height:0;">In catalog below. Click to jump →</div>'
            + '</div>';
        }
        return '<div class="model installed" data-kind="llm" data-backend="ollama" '
          + 'data-requires-gpu="false" data-min-vram="0" data-installed="true" '
          + 'id="card-' + cardId(om.name) + '">'
          + '<div class="row1"><div class="name">' + esc(om.name) + '</div><div class="size">' + esc(sizeLabel) + '</div></div>'
          + '<div class="tag">' + esc(om.name) + '</div>'
          + '<div class="desc">Installed directly via Ollama — not in our curated catalog.</div>'
          + '<div class="progress-slot"></div>'
          + '<div class="model-actions">'
          + '<span class="badge-installed">● Installed</span>'
          + '<button class="btn danger" data-remove="' + esc(om.name) + '">Remove</button>'
          + '</div></div>';
      }).join('');
      installedHtml = '<section class="cat">'
        + '<h2>Installed on this machine</h2>'
        + '<div class="cat-desc">Everything Ollama currently has on disk. Remove to free space.</div>'
        + '<div class="grid">' + cards + '</div>'
        + '</section>';
    }

    var catHtml = cats.map(function (cat) {
      var cards = cat.models.map(function (m) { return renderModelCard(m, cat.kind || 'llm'); }).join('');
      return '<section class="cat" data-cat-kind="' + esc(cat.kind || '') + '">'
        + '<h2>' + esc(cat.label) + '</h2>'
        + '<div class="cat-desc">' + esc(cat.description) + '</div>'
        + '<div class="grid">' + cards + '</div>'
        + '</section>';
    }).join('');

    el('catalog').classList.remove('loading');
    el('catalog').innerHTML = installedHtml + catHtml;
    wireCardActions(null);
    document.querySelectorAll('.installed-chip[data-jump]').forEach(function (chip) {
      chip.style.cursor = 'pointer';
      chip.addEventListener('click', function () {
        var tag = chip.getAttribute('data-jump');
        var target = document.getElementById('card-' + cardId(tag));
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
    });
  }

  function gpuBadge(m) {
    var requiresGpu = !!m.requiresGpu;
    var minVram = m.minVramGb || 0;
    var minRam = m.minRamGb || 0;
    if (!requiresGpu) {
      return '<span class="hw-badge cpu-ok" title="Runs on CPU. GPU optional for speed.">CPU OK'
        + (minRam ? ' · ' + minRam + ' GB RAM' : '') + '</span>';
    }
    // Requires GPU
    if (!gpuInfo.available) {
      return '<span class="hw-badge gpu-no" title="No NVIDIA GPU detected. This model needs one.">No GPU detected · needs ' + minVram + ' GB VRAM</span>';
    }
    if (minVram > gpuInfo.vramGb) {
      return '<span class="hw-badge gpu-no" title="Your GPU has ' + gpuInfo.vramGb + ' GB VRAM — not enough.">Needs ' + minVram + ' GB VRAM · you have ' + gpuInfo.vramGb + ' GB</span>';
    }
    if (minVram === gpuInfo.vramGb) {
      return '<span class="hw-badge gpu-tight" title="Will fit but tight — expect slower gen with model offload.">Fits your ' + gpuInfo.vramGb + ' GB · tight</span>';
    }
    return '<span class="hw-badge gpu-ok" title="Your GPU has headroom for this model.">Fits your ' + gpuInfo.vramGb + ' GB GPU</span>';
  }

  function renderModelCard(m, kind) {
    var isInstalled = installed.has(normalizeTag(m.tag));
    var isDefault = defaultModelTag && normalizeTag(m.tag) === normalizeTag(defaultModelTag);
    var backend = m.backend || 'ollama';
    var requiresGpu = !!m.requiresGpu;
    var minVram = m.minVramGb || 0;

    // "Can't run" styling — requires GPU you don't have, or needs more VRAM than you have
    var cantRun = requiresGpu && (!gpuInfo.available || minVram > gpuInfo.vramGb);

    var rightAction;
    if (isInstalled) {
      rightAction = '<span class="badge-installed">● Installed</span>'
        + '<button class="btn danger" data-remove="' + esc(m.tag) + '">Remove</button>';
    } else if (backend === 'comfyui') {
      rightAction = '<button class="btn" data-comfy-install="' + esc(m.tag) + '">Install via ComfyUI</button>';
    } else {
      rightAction = '<button class="btn" data-install="' + esc(m.tag) + '">Install</button>';
    }

    var defaultBadge = isDefault ? '<span class="badge-default" title="Bot uses this when offline mode is on">DEFAULT</span>' : '';
    var vs = m.vs ? '<div class="vs"><span class="vs-label">vs peers</span>' + esc(m.vs) + '</div>' : '';
    var hwRow = '<div class="hw-row">' + gpuBadge(m) + '</div>';

    return '<div class="model ' + (isInstalled ? 'installed ' : '') + (isDefault ? 'is-default ' : '') + (cantRun ? 'cant-run' : '') + '" '
      + 'id="card-' + cardId(m.tag) + '" '
      + 'data-kind="' + esc(kind) + '" '
      + 'data-backend="' + esc(backend) + '" '
      + 'data-requires-gpu="' + (requiresGpu ? 'true' : 'false') + '" '
      + 'data-min-vram="' + minVram + '" '
      + 'data-installed="' + (isInstalled ? 'true' : 'false') + '">'
      + '<div class="row1"><div class="name">' + esc(m.name) + ' ' + defaultBadge + '</div><div class="size">' + esc(m.sizeLabel) + '</div></div>'
      + '<div class="tag">' + esc(m.tag) + '</div>'
      + '<div class="desc">' + esc(m.description) + '</div>'
      + hwRow
      + vs
      + '<div class="progress-slot"></div>'
      + '<div class="model-actions">' + rightAction + '</div>'
      + '</div>';
  }

  function cardId(tag) { return tag.replace(/[^a-z0-9]/gi, '-'); }

  // ═══ Ollama pull (SSE) ═══════════════════════════════════════════

  function startPull(tag) {
    if (activePulls.has(tag)) return;
    var card = el('card-' + cardId(tag));
    if (!card) return;

    var slot = card.querySelector('.progress-slot');
    slot.innerHTML = '<div class="progress"><div class="progress-fill"></div></div>'
      + '<div class="progress-text">Starting…</div>';
    var fill = slot.querySelector('.progress-fill');
    var text = slot.querySelector('.progress-text');

    var actions = card.querySelector('.model-actions');
    actions.innerHTML = '<button class="btn danger" id="cancel-' + cardId(tag) + '">Cancel</button>';
    var cancelBtn = el('cancel-' + cardId(tag));

    var es = new EventSource('/api/setup/pull-model?model=' + encodeURIComponent(tag));
    activePulls.set(tag, es);

    cancelBtn.addEventListener('click', function () {
      es.close();
      activePulls.delete(tag);
      slot.innerHTML = '';
      actions.innerHTML = '<button class="btn" data-install="' + esc(tag) + '">Install</button>';
      actions.querySelector('[data-install]').addEventListener('click', function () { startPull(tag); });
      toast('Cancelled — partial data saved, resume anytime.', 'ok');
    });

    es.addEventListener('progress', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        var total = d.total || 0;
        var completed = d.completed || 0;
        var pct = total > 0 ? Math.floor((completed / total) * 100) : 0;
        fill.style.width = pct + '%';
        var got = completed > 0 ? fmtBytes(completed) : '';
        var all = total > 0 ? fmtBytes(total) : '';
        var status = d.status || 'downloading';
        text.textContent = got && all ? got + ' / ' + all + ' (' + pct + '%) — ' + status : status;
      } catch (e) { /* ignore parse errors */ }
    });

    es.addEventListener('done', function () {
      es.close();
      activePulls.delete(tag);
      toast('Downloaded: ' + tag, 'ok');
      loadAll();
    });

    es.addEventListener('error', function () {
      es.close();
      activePulls.delete(tag);
      text.textContent = 'Failed — click Install to retry (resumes from partial data).';
      actions.innerHTML = '<button class="btn" data-install="' + esc(tag) + '">Retry</button>';
      actions.querySelector('[data-install]').addEventListener('click', function () { startPull(tag); });
      toast('Pull failed — you can retry to resume', 'err');
    });
  }

  async function removeModel(tag) {
    var card = el('card-' + cardId(tag));
    if (!card) return;
    var actions = card.querySelector('.model-actions');
    var original = actions.innerHTML;
    actions.innerHTML = '<span style="color:var(--muted);font-size:12px;margin-right:auto;">Remove this model?</span>'
      + '<button class="btn" id="cancel-rm-' + cardId(tag) + '">Keep</button>'
      + '<button class="btn danger" id="confirm-rm-' + cardId(tag) + '">Remove</button>';
    el('cancel-rm-' + cardId(tag)).addEventListener('click', function () {
      actions.innerHTML = original;
      wireCardActions(card);
    });
    el('confirm-rm-' + cardId(tag)).addEventListener('click', async function () {
      try {
        var r = await fetch('/api/setup/remove-model?model=' + encodeURIComponent(tag), { method: 'POST' });
        var data = await r.json();
        if (!r.ok) { toast(data.error || 'Remove failed', 'err'); return; }
        toast('Removed: ' + tag, 'ok');
        loadAll();
      } catch (e) {
        toast('Remove failed: ' + e.message, 'err');
      }
    });
  }

  // ═══ ComfyUI install modal ═══════════════════════════════════════

  function openComfyInstallModal(tag) {
    var model = findCatalogModel(tag);
    if (!model) return;
    var slot = el('modal-slot');
    if (!slot) return;

    var setupStep = comfyUiRunning
      ? ''
      : ''
        + '<div class="step-head">Step 1 — Install + start ComfyUI</div>'
        + '<pre>git clone https://github.com/comfyanonymous/ComfyUI\n'
        + 'cd ComfyUI\n'
        + 'python -m venv venv\n'
        + 'venv\\Scripts\\activate\n'
        + 'pip install -r requirements.txt\n'
        + 'python main.py --listen 127.0.0.1 --port 8188</pre>'
        + '<div class="small-note">Needs Python 3.10+ and an NVIDIA driver. First run downloads PyTorch (~2 GB).</div>';

    var downloadStep = ''
      + '<div class="step-head">Step ' + (comfyUiRunning ? '1' : '2') + ' — Download ' + esc(model.name) + '</div>'
      + '<pre>cd ComfyUI\\models\\' + esc(model.comfyFolder) + '\n'
      + 'curl -L -o ' + esc(model.comfyFilename) + ' ^\n'
      + '  "' + esc(model.comfyDownloadUrl) + '"</pre>'
      + '<div class="small-note">File size: ' + esc(model.sizeLabel) + '. Can resume with <code>curl -C -</code>.</div>';

    var useStep = ''
      + '<div class="step-head">Step ' + (comfyUiRunning ? '2' : '3') + ' — Use it</div>'
      + '<div class="small-note">In chat, say "generate an image of …" — Mins Bot will route to ComfyUI automatically when it\'s running.</div>';

    slot.innerHTML = '<div class="modal-backdrop" id="comfy-modal-bd">'
      + '<div class="modal">'
      + '<h3>Install ' + esc(model.name) + '</h3>'
      + '<div class="modal-sub">' + esc(model.description) + '</div>'
      + setupStep + downloadStep + useStep
      + '<div class="modal-actions">'
      + '<button class="btn" id="comfy-modal-close">Got it</button>'
      + '</div>'
      + '</div></div>';

    var close = function () { slot.innerHTML = ''; };
    el('comfy-modal-close').addEventListener('click', close);
    el('comfy-modal-bd').addEventListener('click', function (e) {
      if (e.target.id === 'comfy-modal-bd') close();
    });
  }

  function findCatalogModel(tag) {
    if (!catalogCache || !catalogCache.categories) return null;
    for (var i = 0; i < catalogCache.categories.length; i++) {
      var cat = catalogCache.categories[i];
      for (var j = 0; j < cat.models.length; j++) {
        if (cat.models[j].tag === tag) return cat.models[j];
      }
    }
    return null;
  }

  // ═══ Wiring ══════════════════════════════════════════════════════

  function wireCardActions(scope) {
    (scope || document).querySelectorAll('[data-install]').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', function () { startPull(btn.getAttribute('data-install')); });
    });
    (scope || document).querySelectorAll('[data-remove]').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', function () { removeModel(btn.getAttribute('data-remove')); });
    });
    (scope || document).querySelectorAll('[data-comfy-install]').forEach(function (btn) {
      if (btn.__wired) return;
      btn.__wired = true;
      btn.addEventListener('click', function () { openComfyInstallModal(btn.getAttribute('data-comfy-install')); });
    });
  }

  function renderComingSoon(catalog) {
    var items = (catalog && catalog.comingSoon) || [];
    if (!items.length) return;
    el('coming-soon-slot').innerHTML = '<div class="coming-soon">'
      + '<div class="cs-title">Coming soon</div>'
      + '<ul>' + items.map(function (i) { return '<li><strong>' + esc(i.name) + '</strong> — ' + esc(i.note) + '</li>'; }).join('') + '</ul>'
      + '</div>';
  }

  loadAll();
})();
