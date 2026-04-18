/* Integration credential modal — opens when any "Configure" button in the
   Integrations tab is clicked. Looks up the integration by its card title,
   shows the right credential fields based on auth type, POSTs to the backend. */
(function () {
  'use strict';

  const modal = document.getElementById('integration-modal');
  if (!modal) return;
  const titleEl = document.getElementById('integration-modal-title');
  const hintEl = document.getElementById('integration-modal-hint');
  const fieldsEl = document.getElementById('integration-modal-fields');
  const errorEl = document.getElementById('integration-modal-error');
  const docsEl = document.getElementById('integration-modal-docs');
  const saveBtn = document.getElementById('integration-modal-save');
  const deleteBtn = document.getElementById('integration-modal-delete');

  /** name (lowercased, normalized) → integration metadata */
  const byName = new Map();
  let currentIntegration = null;

  // ── Load registry + enrich cards ──────────────────────────────────────────
  async function loadRegistry() {
    try {
      const res = await fetch('/api/integrations-api');
      if (!res.ok) return;
      const data = await res.json();
      for (const i of data.integrations || []) {
        byName.set(normalize(i.name), i);
      }
      enrichButtons();
    } catch (e) {
      console.warn('[IntegrationModal] failed to load registry:', e);
    }
  }

  function normalize(name) {
    return String(name || '').toLowerCase().replace(/\s+/g, ' ').trim();
  }

  /** Populate data-integration-id on every .integration-oauth-configure button
     by matching the card's <h3> title text. */
  function enrichButtons() {
    document.querySelectorAll('.integration-oauth-configure').forEach(btn => {
      if (btn.dataset.integrationId && btn.dataset.integrationId.length > 0) return;
      const card = btn.closest('.integration-card');
      if (!card) return;
      const titleEl = card.querySelector('.integration-card-title');
      if (!titleEl) return;
      const title = normalize(titleEl.textContent);
      // Try exact match first, then partial
      let match = byName.get(title);
      if (!match) {
        for (const [k, v] of byName) {
          if (title.includes(k) || k.includes(title)) { match = v; break; }
        }
      }
      if (match) {
        btn.dataset.integrationId = match.id;
        // Update card if already configured — green tint + "Configured" label
        if (match.configured) {
          card.classList.add('integration-card-configured');
          btn.textContent = 'Reconfigure';
        }
      }
    });
  }

  // ── Modal open/close ──────────────────────────────────────────────────────
  async function openModal(integrationId) {
    errorEl.hidden = true;
    errorEl.textContent = '';
    try {
      const res = await fetch('/api/integrations-api/' + encodeURIComponent(integrationId));
      if (!res.ok) {
        showError('Could not load integration: ' + integrationId);
        return;
      }
      const i = await res.json();
      currentIntegration = i;
      titleEl.textContent = 'Configure ' + i.name;
      hintEl.textContent = hintForAuthType(i.authType, i.baseUrl);
      docsEl.href = i.docsUrl || '#';
      docsEl.style.display = i.docsUrl ? 'inline' : 'none';
      deleteBtn.hidden = !i.configured;
      renderFields(i);
      modal.hidden = false;
      const first = fieldsEl.querySelector('input, textarea');
      if (first) first.focus();
    } catch (e) {
      showError(String(e));
    }
  }

  function closeModal() {
    modal.hidden = true;
    currentIntegration = null;
  }

  function hintForAuthType(type, baseUrl) {
    switch (type) {
      case 'API_KEY':        return 'Paste your API key. Will be sent in the configured auth header.';
      case 'BEARER_TOKEN':   return 'Paste your bearer token (Personal Access Token or OAuth-issued).';
      case 'BASIC':          return 'Paste your base64-encoded "username:password" credentials.';
      case 'OAUTH2':         return 'Paste an OAuth2 access token. Refresh flow is not yet automated — re-paste when it expires.';
      case 'SDK':            return 'This service requires its native SDK (AWS/GCP). Set credentials via the SDK\'s standard env vars.';
      default:               return 'Paste credentials below.';
    }
  }

  function renderFields(i) {
    fieldsEl.innerHTML = '';
    const fields = fieldsForAuthType(i.authType);
    for (const f of fields) {
      const label = document.createElement('label');
      label.className = 'integration-modal-field';
      const lbl = document.createElement('span');
      lbl.className = 'integration-modal-field-label';
      lbl.textContent = f.label;
      label.appendChild(lbl);
      const inp = document.createElement('input');
      inp.type = f.secret ? 'password' : 'text';
      inp.name = f.name;
      inp.placeholder = f.placeholder || '';
      inp.autocomplete = 'off';
      inp.spellcheck = false;
      inp.className = 'integration-modal-input';
      label.appendChild(inp);
      if (f.help) {
        const hlp = document.createElement('span');
        hlp.className = 'integration-modal-field-help';
        hlp.textContent = f.help;
        label.appendChild(hlp);
      }
      fieldsEl.appendChild(label);
    }
    // Optional custom base URL override for self-hosted services
    if (['Mattermost','Rocket.Chat','GitLab','Jira','Confluence','n8n','Okta','Auth0','Duo / Cisco Secure','Snowflake','Databricks','WooCommerce','Shopify','Salesforce','Outlook','OneDrive','SharePoint'].indexOf(i.name) >= 0) {
      const label = document.createElement('label');
      label.className = 'integration-modal-field';
      label.innerHTML = '<span class="integration-modal-field-label">Base URL (override)</span>';
      const inp = document.createElement('input');
      inp.type = 'text';
      inp.name = 'baseUrl';
      inp.placeholder = i.baseUrl;
      inp.className = 'integration-modal-input';
      label.appendChild(inp);
      const hlp = document.createElement('span');
      hlp.className = 'integration-modal-field-help';
      hlp.textContent = 'Default: ' + i.baseUrl;
      label.appendChild(hlp);
      fieldsEl.appendChild(label);
    }
  }

  function fieldsForAuthType(type) {
    switch (type) {
      case 'API_KEY':      return [{ name: 'apiKey', label: 'API Key', secret: true, placeholder: 'sk-... / key_... / etc.' }];
      case 'BEARER_TOKEN': return [{ name: 'token', label: 'Bearer Token', secret: true, placeholder: 'Personal Access Token' }];
      case 'BASIC':        return [
        { name: 'username', label: 'Username or Key ID', placeholder: 'email / key_id' },
        { name: 'password', label: 'Password or Secret', secret: true, placeholder: 'password / secret' }
      ];
      case 'OAUTH2':       return [{ name: 'token', label: 'Access Token', secret: true, placeholder: 'OAuth2 access token' }];
      case 'SDK':          return [
        { name: 'note', label: 'Note', placeholder: 'SDK-based — credentials via standard env vars', help: 'See the service docs for the native SDK setup.' }
      ];
      default:             return [{ name: 'token', label: 'Credential', secret: true }];
    }
  }

  async function saveCredentials() {
    if (!currentIntegration) return;
    const creds = {};
    let handled = false;
    fieldsEl.querySelectorAll('input').forEach(inp => {
      if (inp.value && inp.value.trim()) {
        // Basic auth: build "Basic base64(user:pass)"
        creds[inp.name] = inp.value.trim();
        handled = true;
      }
    });
    if (!handled) { showError('Enter at least one credential.'); return; }

    // Basic auth — combine username + password into the token used by the auth template
    if (currentIntegration.authType === 'BASIC' && creds.username && creds.password) {
      creds.token = btoa(creds.username + ':' + creds.password);
    }

    try {
      const res = await fetch('/api/integrations-api/' + encodeURIComponent(currentIntegration.id) + '/credentials', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(creds)
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: 'save failed' }));
        showError(err.error || 'save failed');
        return;
      }
      // Refresh the registry + card state
      await loadRegistry();
      // Update the clicked card — lookup the button
      const btn = document.querySelector(`.integration-oauth-configure[data-integration-id="${currentIntegration.id}"]`);
      if (btn) {
        const card = btn.closest('.integration-card');
        if (card) card.classList.add('integration-card-configured');
        btn.textContent = 'Reconfigure';
      }
      closeModal();
    } catch (e) {
      showError(String(e));
    }
  }

  async function deleteCredentials() {
    if (!currentIntegration) return;
    if (!confirm('Disconnect ' + currentIntegration.name + '? Stored credentials will be removed.')) return;
    try {
      const res = await fetch('/api/integrations-api/' + encodeURIComponent(currentIntegration.id) + '/credentials', {
        method: 'DELETE'
      });
      if (!res.ok) { showError('disconnect failed'); return; }
      await loadRegistry();
      const btn = document.querySelector(`.integration-oauth-configure[data-integration-id="${currentIntegration.id}"]`);
      if (btn) {
        const card = btn.closest('.integration-card');
        if (card) card.classList.remove('integration-card-configured');
        btn.textContent = 'Configure';
      }
      closeModal();
    } catch (e) {
      showError(String(e));
    }
  }

  function showError(msg) {
    errorEl.textContent = msg;
    errorEl.hidden = false;
  }

  // ── Event wiring ──────────────────────────────────────────────────────────
  document.addEventListener('click', function (ev) {
    const btn = ev.target.closest('.integration-oauth-configure');
    if (btn) {
      ev.preventDefault();
      const id = btn.dataset.integrationId;
      if (!id) {
        console.warn('[IntegrationModal] no integration-id for button', btn);
        return;
      }
      openModal(id);
      return;
    }
    if (ev.target.dataset && ev.target.dataset.close === 'integration-modal') {
      ev.preventDefault();
      closeModal();
    }
  });

  saveBtn.addEventListener('click', saveCredentials);
  deleteBtn.addEventListener('click', deleteCredentials);

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && !modal.hidden) closeModal();
    if (ev.key === 'Enter' && !modal.hidden && ev.target && ev.target.tagName === 'INPUT') saveCredentials();
  });

  // Kick off
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', loadRegistry);
  } else {
    loadRegistry();
  }
})();
