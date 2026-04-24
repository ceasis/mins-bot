(() => {
  const $ = (id) => document.getElementById(id);
  const modes = $('modes');
  const go = $('go');
  const cancelBtn = $('cancel');
  const status = $('status');
  const results = $('results');
  let mode = 'primary';
  let currentJobId = null;
  let currentEventSource = null;

  // Auto-fill the working directory using the detected base (e.g. ~\eclipse-workspace).
  fetch('/api/code/default-dir').then(r => r.json()).then(info => {
    const field = $('workingDir');
    if (!field) return;
    const current = field.value.trim();
    const looksUntouched = current === '' || current.startsWith('C:\\Users\\cholo\\code-gen');
    if (looksUntouched && info && info.baseDir) {
      const sep = info.baseDir.includes('\\') ? '\\' : '/';
      field.value = info.baseDir + sep + 'my-project';
    }
  }).catch(() => {});

  modes.addEventListener('click', (e) => {
    const btn = e.target.closest('.mode-btn');
    if (!btn) return;
    modes.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    mode = btn.dataset.mode;
  });

  go.addEventListener('click', async () => {
    const task = $('task').value.trim();
    const workingDir = $('workingDir').value.trim();
    const model = $('localModel').value.trim();
    const createGithub = $('createGithub').checked;
    const isPrivate = $('isPrivate').checked;
    if (!task) { status.textContent = 'Enter a task first.'; return; }
    if (!workingDir) { status.textContent = 'Enter a working directory.'; return; }

    setRunning(true);
    status.textContent = 'Queueing…';
    results.innerHTML = '';
    const panel = buildLivePanel();
    results.appendChild(panel);

    try {
      const resp = await fetch('/api/code/generate', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ task, workingDir, mode, model, createGithub, isPrivate })
      });
      const data = await resp.json();
      if (data.error) {
        setStreamStatus(panel, 'failed');
        appendLog(panel, 'Error: ' + data.error);
        setRunning(false);
        status.textContent = '';
        return;
      }

      currentJobId = data.jobId;
      status.textContent = `Job ${data.jobId} · streaming…`;
      subscribeToJob(panel, data.jobId);
    } catch (err) {
      appendLog(panel, 'Request failed: ' + err);
      setStreamStatus(panel, 'failed');
      setRunning(false);
      status.textContent = '';
    }
  });

  cancelBtn.addEventListener('click', async () => {
    if (!currentJobId) return;
    cancelBtn.disabled = true;
    cancelBtn.textContent = 'Cancelling…';
    try {
      await fetch(`/api/code/jobs/${currentJobId}/cancel`, { method: 'POST' });
    } catch {}
  });

  function subscribeToJob(panel, jobId) {
    if (currentEventSource) { try { currentEventSource.close(); } catch {} }
    const es = new EventSource(`/api/code/jobs/${jobId}/stream`);
    currentEventSource = es;
    const started = Date.now();

    es.addEventListener('status', (e) => {
      setStreamStatus(panel, e.data);
      const elapsed = ((Date.now() - started) / 1000).toFixed(0);
      if (e.data === 'done' || e.data === 'failed' || e.data === 'cancelled') {
        status.textContent = `${e.data} in ${elapsed}s.`;
      } else {
        status.textContent = `${e.data} · ${elapsed}s`;
      }
    });
    es.addEventListener('log', (e) => appendLog(panel, e.data));
    es.addEventListener('file', (e) => appendFile(panel, e.data));
    es.addEventListener('result', (e) => {
      appendFinalResult(panel, e.data);
      es.close();
      currentEventSource = null;
      setRunning(false);
    });
    es.onerror = () => {
      es.close();
      currentEventSource = null;
      setRunning(false);
    };
  }

  function setRunning(running) {
    go.disabled = running;
    cancelBtn.disabled = !running;
    cancelBtn.style.display = running ? '' : 'none';
    cancelBtn.textContent = 'Cancel';
    if (!running) currentJobId = null;
  }

  function buildLivePanel() {
    const div = document.createElement('div');
    div.className = 'result';
    div.innerHTML = `
      <h3>
        <span class="title">Live run</span>
        <span class="badge badge-status" data-status>queued</span>
        <span class="badge badge-count">0 files</span>
      </h3>
      <div class="live-grid">
        <div class="live-col">
          <div class="live-label">Files written</div>
          <ul class="file-list" data-files></ul>
        </div>
        <div class="live-col">
          <div class="live-label">Log</div>
          <pre class="log-pane" data-log></pre>
        </div>
      </div>
      <details style="margin-top:10px">
        <summary style="cursor:pointer;color:var(--muted);font-size:12px">Final result</summary>
        <pre class="final-result" data-final style="margin-top:8px"></pre>
      </details>`;
    return div;
  }

  function setStreamStatus(panel, s) {
    const badge = panel.querySelector('[data-status]');
    if (badge) {
      badge.textContent = s;
      badge.className = 'badge badge-status status-' + s;
    }
    if (s === 'failed' || s === 'cancelled') panel.classList.add('err');
    if (s === 'done') panel.classList.add('ok');
  }

  function appendLog(panel, line) {
    const pane = panel.querySelector('[data-log]');
    if (!pane) return;
    pane.textContent += line + '\n';
    pane.scrollTop = pane.scrollHeight;
  }

  function appendFile(panel, rel) {
    const list = panel.querySelector('[data-files]');
    const countBadge = panel.querySelector('.badge-count');
    if (!list) return;
    const li = document.createElement('li');
    li.textContent = rel;
    list.appendChild(li);
    if (countBadge) {
      const n = list.children.length;
      countBadge.textContent = n + (n === 1 ? ' file' : ' files');
    }
  }

  function appendFinalResult(panel, text) {
    const pre = panel.querySelector('[data-final]');
    if (pre) pre.textContent = text || '(empty)';
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
      '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    }[c]));
  }

  // ── Recent projects ──────────────────────────────────────────────
  const recentList = document.getElementById('recentList');
  const refreshBtn = document.getElementById('refreshRecent');

  function loadRecent() {
    if (!recentList) return;
    recentList.innerHTML = '<div style="color:var(--muted);font-size:12px">Loading…</div>';
    fetch('/api/code/history').then(r => r.json()).then(data => {
      const rows = (data && data.projects) || [];
      if (rows.length === 0) {
        recentList.innerHTML = '<div style="color:var(--muted);font-size:12px;font-style:italic">No projects yet.</div>';
        return;
      }
      recentList.innerHTML = '';
      rows.slice(0, 15).forEach(rec => {
        const item = document.createElement('div');
        item.style.cssText = 'border:1px solid var(--border);border-radius:8px;padding:10px 12px;display:grid;grid-template-columns:1fr auto;gap:6px;align-items:center;background:var(--panel-2)';
        const name = rec.projectName || '(unnamed)';
        const when = rec.completedAt ? new Date(rec.completedAt).toLocaleString() : '';
        const gh = rec.githubUrl ? '<a href="' + escapeHtml(rec.githubUrl) + '" target="_blank" style="color:var(--accent-2);font-size:12px;text-decoration:none">GitHub ↗</a>' : '';
        item.innerHTML =
          '<div style="min-width:0">' +
            '<div style="font-weight:600;font-size:13px">' + escapeHtml(name) + '</div>' +
            '<div style="color:var(--muted);font-size:11px;font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + escapeHtml(rec.workingDir||'?') + '</div>' +
            '<div style="color:var(--muted);font-size:11px;margin-top:2px">' + escapeHtml(rec.mode||'?') + ' · ' + escapeHtml(rec.status||'?') + ' · ' + escapeHtml(when) + ' ' + gh + '</div>' +
          '</div>' +
          '<div style="display:flex;gap:6px">' +
            '<button class="mode-btn r-continue" style="flex:0 0 auto;min-width:0;padding:6px 10px;font-size:12px">Continue</button>' +
            '<button class="mode-btn r-use" style="flex:0 0 auto;min-width:0;padding:6px 10px;font-size:12px">Use dir</button>' +
          '</div>';
        item.querySelector('.r-continue').addEventListener('click', () => {
          $('workingDir').value = rec.workingDir || '';
          $('task').value = 'Continue working on ' + name + '. Review current state, pick the most useful next thing to implement, and make it.';
          $('task').focus();
        });
        item.querySelector('.r-use').addEventListener('click', () => {
          $('workingDir').value = rec.workingDir || '';
          $('task').focus();
        });
        recentList.appendChild(item);
      });
    }).catch(err => {
      recentList.innerHTML = '<div style="color:var(--err);font-size:12px">Failed to load: ' + escapeHtml(String(err)) + '</div>';
    });
  }

  if (refreshBtn) refreshBtn.addEventListener('click', loadRecent);
  loadRecent();

  // ── QA screenshots viewer ────────────────────────────────────────
  const shotsList = document.getElementById('shotsList');
  const shotsProject = document.getElementById('shotsProject');
  const loadShotsBtn = document.getElementById('loadShots');
  function loadShots() {
    if (!shotsList || !shotsProject) return;
    const name = shotsProject.value.trim();
    if (!name) { shotsList.innerHTML = '<div style="color:var(--muted);font-size:12px">Enter a project name.</div>'; return; }
    shotsList.innerHTML = '<div style="color:var(--muted);font-size:12px">Loading…</div>';
    fetch('/api/code/screenshots/' + encodeURIComponent(name))
      .then(r => r.json())
      .then(data => {
        const items = (data && data.items) || [];
        if (items.length === 0) {
          shotsList.innerHTML = '<div style="color:var(--muted);font-size:12px;font-style:italic">No QA screenshots yet for \'' + escapeHtml(name) + '\'. Run visualReview first.</div>';
          return;
        }
        shotsList.innerHTML = '';
        items.forEach(it => {
          const card = document.createElement('div');
          card.style.cssText = 'border:1px solid var(--border);border-radius:8px;overflow:hidden;background:var(--panel-2)';
          card.innerHTML =
            '<div style="padding:6px 10px;font-size:11px;color:var(--muted);display:flex;justify-content:space-between">' +
              '<span>' + escapeHtml(it.device) + '</span>' +
              '<span style="font-family:monospace">' + escapeHtml(it.page) + '</span>' +
            '</div>' +
            '<a href="' + escapeHtml(it.url) + '" target="_blank">' +
              '<img src="' + escapeHtml(it.url) + '" loading="lazy" style="display:block;width:100%;height:auto">' +
            '</a>';
          shotsList.appendChild(card);
        });
      })
      .catch(err => {
        shotsList.innerHTML = '<div style="color:var(--err);font-size:12px">' + escapeHtml(String(err)) + '</div>';
      });
  }
  if (loadShotsBtn) loadShotsBtn.addEventListener('click', loadShots);
  if (shotsProject) shotsProject.addEventListener('keydown', e => { if (e.key === 'Enter') loadShots(); });
})();
