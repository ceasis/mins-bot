/* First-run setup wizard. On first launch, checks if Ollama + a default model
   are installed. If not, offers a 1-click install + model download. Skippable. */
(function () {
  'use strict';

  const OVERLAY_ID = 'first-run-overlay';

  async function fetchStatus() {
    try {
      const r = await fetch('/api/setup/status');
      if (!r.ok) return null;
      return await r.json();
    } catch { return null; }
  }

  function build() {
    if (document.getElementById(OVERLAY_ID)) return;
    const div = document.createElement('div');
    div.id = OVERLAY_ID;
    div.innerHTML = `
      <div class="fr-backdrop"></div>
      <div class="fr-dialog">
        <div class="fr-hero">
          <div class="fr-badge">WELCOME</div>
          <h2>Set up your local AI</h2>
          <p class="fr-sub">Install Ollama and a small local model so MinsBot works <strong>offline</strong> with zero API keys. Takes ~2&#x2011;5 minutes on a good connection.</p>
        </div>

        <ol class="fr-steps">
          <li id="fr-step-ollama" class="fr-step">
            <span class="fr-step-num">1</span>
            <div class="fr-step-body">
              <div class="fr-step-title">Install Ollama</div>
              <div class="fr-step-status" id="fr-ollama-status">Checking...</div>
            </div>
          </li>
          <li id="fr-step-model" class="fr-step">
            <span class="fr-step-num">2</span>
            <div class="fr-step-body">
              <div class="fr-step-title">Download llama3.2:3b (~2 GB)</div>
              <div class="fr-step-status" id="fr-model-status">Waiting...</div>
              <div class="fr-progress" id="fr-progress" hidden>
                <div class="fr-progress-bar"><div class="fr-progress-fill" id="fr-progress-fill"></div></div>
                <div class="fr-progress-text" id="fr-progress-text"></div>
              </div>
            </div>
          </li>
        </ol>

        <div class="fr-error" id="fr-error" hidden></div>

        <div class="fr-actions">
          <button type="button" class="fr-btn fr-btn-ghost" id="fr-skip">Skip — use cloud AI only</button>
          <button type="button" class="fr-btn fr-btn-primary" id="fr-start">Start setup</button>
          <button type="button" class="fr-btn fr-btn-primary" id="fr-done" hidden>Done</button>
        </div>
      </div>
    `;
    document.body.appendChild(div);
    document.getElementById('fr-start').addEventListener('click', runSetup);
    document.getElementById('fr-skip').addEventListener('click', skip);
    document.getElementById('fr-done').addEventListener('click', dismiss);
  }

  function setStepState(stepId, state) {
    const el = document.getElementById(stepId);
    if (!el) return;
    el.classList.remove('fr-step-running', 'fr-step-done', 'fr-step-error');
    if (state) el.classList.add('fr-step-' + state);
  }

  async function updateFromStatus() {
    const s = await fetchStatus();
    if (!s) return;
    const o = document.getElementById('fr-ollama-status');
    const m = document.getElementById('fr-model-status');
    if (s.ollamaInstalled) {
      o.textContent = s.ollamaRunning ? 'Installed & running ✓' : 'Installed (not running yet)';
      setStepState('fr-step-ollama', 'done');
    } else {
      o.textContent = 'Not installed';
    }
    if (s.defaultModelReady) {
      m.textContent = s.defaultModel + ' ready ✓';
      setStepState('fr-step-model', 'done');
    }
    if (s.setupComplete) {
      document.getElementById('fr-start').hidden = true;
      document.getElementById('fr-skip').hidden = true;
      document.getElementById('fr-done').hidden = false;
    }
  }

  async function runSetup() {
    document.getElementById('fr-start').disabled = true;
    document.getElementById('fr-start').textContent = 'Working...';
    hideError();

    // Step 1: install Ollama
    setStepState('fr-step-ollama', 'running');
    document.getElementById('fr-ollama-status').textContent = 'Installing via winget...';
    try {
      const r = await fetch('/api/setup/install-ollama', { method: 'POST' });
      const data = await r.json();
      if (!r.ok) {
        setStepState('fr-step-ollama', 'error');
        document.getElementById('fr-ollama-status').textContent = data.error || 'install failed';
        showError((data.error || 'Install failed') + ' — install manually from https://ollama.com and retry.');
        document.getElementById('fr-start').disabled = false;
        document.getElementById('fr-start').textContent = 'Retry';
        return;
      }
      setStepState('fr-step-ollama', 'done');
      document.getElementById('fr-ollama-status').textContent = 'Installed ✓';
    } catch (e) {
      setStepState('fr-step-ollama', 'error');
      showError('Install error: ' + e.message);
      document.getElementById('fr-start').disabled = false;
      document.getElementById('fr-start').textContent = 'Retry';
      return;
    }

    // Step 2: pull model via SSE
    setStepState('fr-step-model', 'running');
    document.getElementById('fr-model-status').textContent = 'Starting download...';
    document.getElementById('fr-progress').hidden = false;

    const es = new EventSource('/api/setup/pull-model');
    es.addEventListener('progress', (ev) => {
      try {
        const data = JSON.parse(ev.data);
        const total = data.total || 0;
        const completed = data.completed || 0;
        const pct = total > 0 ? Math.floor((completed / total) * 100) : 0;
        document.getElementById('fr-progress-fill').style.width = pct + '%';
        const sizeMB = total > 0 ? (total / (1024 * 1024)).toFixed(0) + ' MB' : '';
        const gotMB = completed > 0 ? (completed / (1024 * 1024)).toFixed(0) + ' MB' : '';
        const statusMsg = data.status || 'downloading';
        document.getElementById('fr-progress-text').textContent = gotMB && sizeMB ? `${gotMB} / ${sizeMB} (${pct}%) — ${statusMsg}` : statusMsg;
        document.getElementById('fr-model-status').textContent = statusMsg;
      } catch { /* ignore parse errors */ }
    });
    es.addEventListener('done', () => {
      es.close();
      setStepState('fr-step-model', 'done');
      document.getElementById('fr-model-status').textContent = 'Ready ✓';
      document.getElementById('fr-progress').hidden = true;
      document.getElementById('fr-start').hidden = true;
      document.getElementById('fr-skip').hidden = true;
      document.getElementById('fr-done').hidden = false;
    });
    es.addEventListener('error', (ev) => {
      es.close();
      setStepState('fr-step-model', 'error');
      showError('Model download failed. You can retry, or skip and add a model later via chat.');
      document.getElementById('fr-start').disabled = false;
      document.getElementById('fr-start').textContent = 'Retry';
    });
  }

  async function skip() {
    await fetch('/api/setup/complete', { method: 'POST' });
    dismiss();
  }

  async function dismiss() {
    await fetch('/api/setup/complete', { method: 'POST' });
    const el = document.getElementById(OVERLAY_ID);
    if (el) el.remove();
  }

  function showError(msg) {
    const e = document.getElementById('fr-error');
    e.textContent = msg;
    e.hidden = false;
  }
  function hideError() { document.getElementById('fr-error').hidden = true; }

  async function init() {
    const s = await fetchStatus();
    if (!s || !s.firstRun) return;     // not first run — skip entirely
    if (s.setupComplete) {             // ollama already installed + model ready
      await fetch('/api/setup/complete', { method: 'POST' });
      return;
    }
    build();
    updateFromStatus();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
