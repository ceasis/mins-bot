/* ─── Sentry Mode ──────────────────────────────────────────────────────
 * Full-screen voice-driven UI. Trigger from chat: "sentry mode".
 * Exit: type "exit sentry" / press Esc / click EXIT.
 *
 * Public API on window.MinsbotSentry:
 *   enter() / exit()
 *   setSpeaking(bool)  — call from TTS hooks to flip the gear into "speaking" pulse
 *   handleText(text)   — programmatic command (used by speech recognition + input)
 * ─────────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const TEETH_OUTER = 18;
  const TEETH_INNER = 12;

  let overlay, gearWrap, statusEl, transcriptEl, inputEl;
  let panels = {}; // name → element
  let panelStack = []; // last-opened panel names (LIFO) for "close that"
  let active = false;
  let voicePollTimer = null;
  let speakingTimer = null;
  let initialized = false;

  // ─── Init / DOM ──────────────────────────────────────────────────────
  function init() {
    if (initialized) return;
    initialized = true;
    overlay = document.getElementById('sentry-overlay');
    if (!overlay) return;
    gearWrap     = overlay.querySelector('.sentry-gear-wrap');
    statusEl     = overlay.querySelector('.sentry-status');
    transcriptEl = overlay.querySelector('.sentry-transcript');
    inputEl      = overlay.querySelector('.sentry-input');
    panels.map     = overlay.querySelector('[data-panel="map"]');
    panels.images  = overlay.querySelector('[data-panel="images"]');
    panels.video   = overlay.querySelector('[data-panel="video"]');

    buildGearTeeth();

    overlay.querySelector('.sentry-exit-btn')
      .addEventListener('click', exit);

    overlay.querySelectorAll('.sentry-panel-close').forEach(b => {
      b.addEventListener('click', () => closePanel(b.dataset.panel));
    });

    // Toggle chips inside panel headers (currently: flights on the map panel)
    overlay.querySelectorAll('.sentry-chip').forEach(b => {
      b.addEventListener('click', () => {
        if (b.dataset.toggle === 'flights') toggleFlights();
      });
    });

    inputEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const v = inputEl.value.trim();
        if (v) { lastInputSource = 'typed'; handleText(v); inputEl.value = ''; }
      } else if (e.key === 'Escape') {
        exit();
      }
    });

    document.addEventListener('keydown', (e) => {
      if (active && e.key === 'Escape') exit();
    });
  }

  function buildGearTeeth() {
    const outer = overlay.querySelector('#sentry-teeth-outer');
    const inner = overlay.querySelector('#sentry-teeth-inner');
    if (!outer || !inner) return;
    addTeeth(outer, TEETH_OUTER, 158, 14, 22);
    addTeeth(inner, TEETH_INNER,  84, 12, 18);
  }
  function addTeeth(group, count, radius, w, h) {
    const cx = 200, cy = 200;
    for (let i = 0; i < count; i++) {
      const angle = (360 / count) * i;
      const r = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      r.setAttribute('x', cx - w / 2);
      r.setAttribute('y', cy - radius - h / 2);
      r.setAttribute('width', w);
      r.setAttribute('height', h);
      r.setAttribute('rx', 2);
      r.setAttribute('class', 'tooth');
      r.setAttribute('transform', `rotate(${angle} ${cx} ${cy})`);
      group.appendChild(r);
    }
  }

  // ─── State ───────────────────────────────────────────────────────────
  // Single source of truth. Three subsystems can each indicate intent —
  // server-side TTS playing, browser speechSynthesis playing, native voice
  // listening — but the GEAR CLASS is recomputed from those flags rather than
  // each subsystem flipping the class directly. Fixes the visible 3–4×/s
  // thrash that happened when poller + voice loop + speechSynthesis raced.
  let _botSpeaking = false;     // any TTS source actively playing
  let _voiceListening = false;  // native voice loop running
  let lastInputSource = 'typed'; // 'typed' or 'voice' — gates ambiguous exit phrasing

  function recomputeState() {
    if (!gearWrap) return;
    let s;
    if (!active)            s = 'idle';
    else if (_botSpeaking)  s = 'speaking';
    else if (_voiceListening) s = 'listening';
    else                    s = 'idle';
    if (gearWrap.dataset.state === s) return; // no churn
    gearWrap.dataset.state = s;
    gearWrap.classList.remove('idle', 'listening', 'speaking');
    gearWrap.classList.add(s);
    if (statusEl) statusEl.textContent = s.toUpperCase();
  }

  // Legacy setState — kept for code paths that still call it explicitly
  // (notably the bare "set listening" one when sentry first opens). Maps to
  // the relevant flag and recomputes.
  function setState(s) {
    if (s === 'speaking')      _botSpeaking = true;
    else if (s === 'listening') { _botSpeaking = false; _voiceListening = true; }
    else /* idle */            { _botSpeaking = false; _voiceListening = false; }
    recomputeState();
  }

  function setSpeaking(on) {
    if (!active) return;
    _botSpeaking = !!on;
    recomputeState();
  }

  function showTranscript(text, who) {
    if (!transcriptEl) return;
    const cls = who === 'you' ? 'you' : (who === 'bot' ? 'bot' : '');
    transcriptEl.innerHTML = cls ? `<span class="${cls}">${escapeHtml(text)}</span>` : escapeHtml(text);
  }

  // ─── Enter / Exit ────────────────────────────────────────────────────
  function enter() {
    init();
    if (!overlay) return;
    if (active) return;
    active = true;
    overlay.hidden = false;
    setState('idle');
    showTranscript('Sentry online. Try: "open map", "search images sunsets", "play video lo-fi", "exit sentry".', 'bot');

    if (window.java && typeof window.java.enterFullscreen === 'function') {
      try { window.java.enterFullscreen(); } catch (e) {}
    }
    setTimeout(() => inputEl && inputEl.focus(), 80);

    startVoiceLoop();
    // Real-amplitude gear pulse — fires only if the user grants mic access.
    // Falls back gracefully to class-based CSS pulse otherwise.
    startMicAmplitude();
  }

  function exit() {
    if (!active) return;
    active = false;
    stopVoiceLoop();
    stopMicAmplitude();
    closeAllPanels();
    if (overlay) overlay.hidden = true;
    setState('idle');
    // Cancel any in-flight TTS so it doesn't keep speaking after exit.
    try { if ('speechSynthesis' in window) window.speechSynthesis.cancel(); } catch (e) {}
    if (window.java && typeof window.java.exitFullscreen === 'function') {
      try { window.java.exitFullscreen(); } catch (e) {}
    }
    // Ensure the chat UI is in expanded state and visible. Sentry hides the
    // overlay; the chat is the underlying #root which app.js controls via
    // the 'expanded' class. Re-assert it in case it drifted.
    try {
      const root = document.getElementById('root');
      if (root) root.classList.add('expanded');
      // Refocus the chat input so the user can resume typing immediately.
      const chatInput = document.getElementById('chat-input') || document.querySelector('textarea, input[type="text"]');
      if (chatInput && typeof chatInput.focus === 'function') {
        setTimeout(() => chatInput.focus(), 100);
      }
    } catch (e) {}
  }

  // ─── Voice loop ──────────────────────────────────────────────────────
  function startVoiceLoop() {
    if (!window.java || !window.java.isNativeVoiceAvailable || !window.java.isNativeVoiceAvailable()) {
      return;
    }
    try { window.java.startNativeVoice(); } catch (e) {}
    _voiceListening = true;
    recomputeState();

    voicePollTimer = setInterval(() => {
      if (!active) return;
      try {
        const t = window.java.consumeNativeVoiceTranscript();
        if (t) {
          lastInputSource = 'voice';
          showTranscript(t, 'you');
          handleText(t);
        }
        // Continuous listening — restart when the OS service stops
        if (window.java.isNativeVoiceListening && !window.java.isNativeVoiceListening()) {
          window.java.startNativeVoice();
        }
      } catch (e) {}
      // Poll server-side TTS state so the gear pulses while audio is playing
      pollTtsState();
    }, 350);
  }

  let ttsPollInFlight = false;
  let lastSpeaking = false;
  function pollTtsState() {
    if (ttsPollInFlight) return;
    ttsPollInFlight = true;
    fetch('/api/tts/state', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(j => {
        const speaking = !!(j && j.speaking);
        if (speaking !== lastSpeaking) {
          lastSpeaking = speaking;
          // Server-side TTS is one of three speaking inputs; treat it as
          // authoritative when it transitions, but route through the same
          // flag the browser-speak path uses so we don't get class thrash.
          _botSpeaking = speaking;
          recomputeState();
        }
      })
      .catch(() => {})
      .finally(() => { ttsPollInFlight = false; });
  }

  // ─── Real audio amplitude pulse ──────────────────────────────────────
  // Drives the gear's `--pulse-amp` CSS variable from the user's mic input
  // so the gear breathes in time with their voice. Class-based pulse remains
  // as fallback when permission is denied or the API is missing.
  let micStream = null;
  let micAudioCtx = null;
  let micAnalyser = null;
  let micRafId = null;
  let micEMA = 0;       // exponential moving average of amplitude

  async function startMicAmplitude() {
    if (micRafId) return;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return;
    try {
      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: false }
      });
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      micAudioCtx = new Ctx();
      const src = micAudioCtx.createMediaStreamSource(micStream);
      micAnalyser = micAudioCtx.createAnalyser();
      micAnalyser.fftSize = 512;
      micAnalyser.smoothingTimeConstant = 0.6;
      src.connect(micAnalyser);

      const buf = new Uint8Array(micAnalyser.frequencyBinCount);
      const tick = () => {
        if (!micAnalyser) return;
        micAnalyser.getByteFrequencyData(buf);
        // Average across the speech-relevant band (skip very low DC + ultra-high noise).
        let sum = 0;
        const lo = 4, hi = Math.min(buf.length, 96);
        for (let i = lo; i < hi; i++) sum += buf[i];
        const avg = sum / (hi - lo);            // 0..255
        const norm = Math.min(1, avg / 90);     // map to 0..1 with some headroom
        // Smooth with EMA so the pulse glides instead of twitching.
        micEMA = micEMA * 0.78 + norm * 0.22;
        // While the bot is speaking, hand control back to the speaking-class
        // CSS values — otherwise the mic amplitude (still picking up bot audio
        // via speakers / room echo) keeps overriding and the gear visibly fights
        // itself between speaking-tone and mic-amplitude-tone.
        if (_botSpeaking) {
          if (gearWrap) {
            gearWrap.style.removeProperty('--pulse-amp');
            gearWrap.style.removeProperty('--pulse-period');
          }
        } else {
          // Map 0..1 to a perceptible scale range. Base 0.012 (idle baseline)
          // up to ~0.10 on shouts. Cap so giant pulses don't break layout.
          const amp = 0.012 + Math.min(0.09, micEMA * 0.10);
          if (gearWrap) gearWrap.style.setProperty('--pulse-amp', amp.toFixed(4));
          // Faster perceived period when mic is hot.
          const period = 1.6 - Math.min(1.0, micEMA * 1.3); // 1.6s → 0.6s
          if (gearWrap) gearWrap.style.setProperty('--pulse-period', period.toFixed(2) + 's');
        }
        micRafId = requestAnimationFrame(tick);
      };
      micRafId = requestAnimationFrame(tick);
    } catch (e) {
      // Permission denied or no device — fall back silently to class-based pulse.
      console.debug('[Sentry] mic amplitude unavailable:', e && e.message);
      stopMicAmplitude();
    }
  }

  function stopMicAmplitude() {
    if (micRafId) { cancelAnimationFrame(micRafId); micRafId = null; }
    if (micAnalyser) { try { micAnalyser.disconnect(); } catch (e) {} micAnalyser = null; }
    if (micAudioCtx) { try { micAudioCtx.close(); } catch (e) {} micAudioCtx = null; }
    if (micStream) {
      try { micStream.getTracks().forEach(t => t.stop()); } catch (e) {}
      micStream = null;
    }
    micEMA = 0;
    // Hand control back to the class-based CSS so idle/listening/speaking
    // states resume driving the pulse.
    if (gearWrap) {
      gearWrap.style.removeProperty('--pulse-amp');
      gearWrap.style.removeProperty('--pulse-period');
    }
  }

  function stopVoiceLoop() {
    if (voicePollTimer) { clearInterval(voicePollTimer); voicePollTimer = null; }
    try { window.java && window.java.stopNativeVoice && window.java.stopNativeVoice(); } catch (e) {}
  }

  // ─── Command routing ─────────────────────────────────────────────────
  function handleText(text) {
    const t = text.trim();
    if (!t) return;
    // Strip trailing punctuation from voice transcripts ("open map." → "open map").
    const low = t.toLowerCase().replace(/[.!?,;]+$/, '').trim();

    // Exit must be UNAMBIGUOUS — bare "stop" / "end" / "close" must not tear
    // down the whole mode just because the user said them mid-sentence to a
    // voice transcript. Require the "sentry" keyword OR a typed "exit"
    // (typing one word in the input field is intentional in a way speech isn't).
    const typedExitWord = (low === 'exit' || low === 'quit') && lastInputSource === 'typed';
    if (/\b(exit|leave|stop|close|end)\s+sentry(?:\s+mode)?\b/.test(low)
        || /^sentry\s+(off|exit|stop|out)$/.test(low)
        || typedExitWord) {
      exit(); return;
    }

    // Context-aware close: "close that" / "close it" / "dismiss" / "go back" /
    // "hide that" / "back" — closes the most-recently-opened panel and returns
    // the user to the bare Sentry view (gear + transcript). If no panel is
    // open, falls through (so plain "close" doesn't accidentally exit sentry).
    if (/^(close|hide|dismiss|go\s+back|back)\s*(that|it|this|the\s+(panel|window|view|tab))?$/.test(low)
        || low === 'close everything that is open'
        || low === 'never mind') {
      if (closeTopPanel()) return;
      // No panel open — let the user know rather than silently doing nothing.
      showTranscript('Nothing to close. Say "exit sentry" to leave.', 'bot');
      return;
    }

    // Local: MAP — match anywhere, with optional "of <place>" / "in <place>".
    // Catches: "open map", "show me a map", "map of paris", "view map",
    //          "I want to see a map of new york", "map view".
    if (/(^|\b)(open|show|view|see|display|bring up|i want|let'?s see|give me)?\s*(a |the |me a |me the )?\s*map(\b|$)/.test(low)
        && !/^close\s+map/.test(low)) {
      const m = low.match(/map\s+(?:of|in|for|at|near|around|to)\s+(.+)$/);
      const m2 = !m && low.match(/(?:of|in|for|near|around)\s+([a-z][\w\s,'-]+)$/);
      const q = m ? m[1].trim() : (m2 ? m2[1].trim() : null);
      openMap(q); return;
    }
    if (/^close\s+(the\s+)?map/.test(low)) { closePanel('map'); return; }

    // Local: FLIGHTS overlay on the map
    if (/(^|\b)(show|see|display|track|view)\s+(the\s+|live\s+)?(flights?|aircraft|planes?|airplanes?)(\b|$)/.test(low)
        || /^flights?\s+on$/.test(low)) {
      ensureLeafletMap();
      openPanel('map');
      if (!flightsActive) toggleFlights();
      return;
    }
    if (/^(hide|stop|close|turn off)\s+(the\s+)?(flights?|aircraft|planes?)/.test(low)
        || /^flights?\s+off$/.test(low)) {
      if (flightsActive) toggleFlights();
      return;
    }

    // Local: IMAGES
    let m = low.match(/(?:^|\b)(?:search|show|find|get|bring up|let'?s see|i want|give me)\s+(?:some\s+|me\s+|me some\s+)?(?:images?|pictures?|photos?|pics?)\s*(?:of|for|about)?\s*(.+)$/);
    if (m && m[1]) { searchImages(m[1].trim()); return; }
    if (/^close\s+(the\s+)?(images?|pictures?|photos?)/.test(low)) { closePanel('images'); return; }

    // Local: VIDEO
    m = low.match(/(?:^|\b)(?:play|show|watch|find|search|bring up)\s+(?:a\s+|the\s+|some\s+)?(?:video|videos|youtube|clip)\s*(?:of|about|for|on)?\s*(.+)$/);
    if (m && m[1]) { playVideo(m[1].trim()); return; }
    if (/^close\s+(the\s+)?(video|videos|clip)/.test(low)) { closePanel('video'); return; }

    // Local: CCTV / public live cameras → YouTube live search.
    // Catches: "open cctvs for new york", "show cameras in tokyo",
    //          "live cam paris", "cctv for london", "webcam shibuya".
    m = low.match(/(?:^|\b)(?:open|show|see|view|find|bring up|tap into)?\s*(?:public\s+|live\s+)?(?:cctvs?|cameras?|webcams?|live\s+cams?|live\s+cam)\s+(?:for|in|of|at|near|around)\s+(.+)$/);
    if (!m) {
      m = low.match(/^(?:cctv|webcam|live\s+cam|cameras?)\s+(.+)$/);
    }
    if (m && m[1]) { openCctv(m[1].trim()); return; }
    if (/^close\s+(the\s+)?(cctvs?|cameras?|webcams?|live\s+cam)/.test(low)) { closePanel('video'); return; }

    if (/^close\s+(all|panels|everything)$/.test(low)) { closeAllPanels(); return; }

    // Fallback: send to /api/chat and speak the reply
    sendToChat(t);
  }

  function sendToChat(text) {
    _botSpeaking = true; recomputeState();
    fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text })
    })
      .then(r => r.json())
      .then(j => {
        const reply = (j && (j.reply || j.message || j.content)) || '...';
        showTranscript(reply, 'bot');
        speak(reply);
      })
      .catch(() => {
        showTranscript('(Chat backend unreachable.)', 'bot');
        _botSpeaking = false; recomputeState();
      });
  }

  function speak(text) {
    if (!('speechSynthesis' in window)) { _botSpeaking = false; recomputeState(); return; }
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(String(text).slice(0, 600));
      u.rate = 1.0;
      u.pitch = 1.0;
      _botSpeaking = true; recomputeState();
      if (speakingTimer) clearTimeout(speakingTimer);
      u.onend = () => {
        _botSpeaking = false;
        recomputeState();
      };
      window.speechSynthesis.speak(u);
      // Hard fallback in case onend doesn't fire (some WebView quirks)
      speakingTimer = setTimeout(() => {
        _botSpeaking = false;
        recomputeState();
      }, Math.min(15000, 800 + text.length * 60));
    } catch (e) {
      _botSpeaking = false; recomputeState();
    }
  }

  // ─── Panels ──────────────────────────────────────────────────────────
  function openPanel(name) {
    const p = panels[name];
    if (!p) return;
    p.hidden = false;
    requestAnimationFrame(() => p.classList.add('show'));
    // Move to top of stack so "close that" hits this one first.
    panelStack = panelStack.filter(n => n !== name);
    panelStack.push(name);
  }
  function closePanel(name) {
    const p = panels[name];
    if (!p) return;
    p.classList.remove('show');
    setTimeout(() => { p.hidden = true; }, 280);
    panelStack = panelStack.filter(n => n !== name);
    // Stop the flights poller when the map closes — no point hammering OpenSky
    // for a panel the user can't see.
    if (name === 'map' && flightsActive) toggleFlights();
  }
  function closeTopPanel() {
    // Close most-recently-opened panel that's still visible.
    for (let i = panelStack.length - 1; i >= 0; i--) {
      const name = panelStack[i];
      const p = panels[name];
      if (p && !p.hidden) { closePanel(name); return true; }
    }
    return false;
  }
  function closeAllPanels() {
    Object.keys(panels).forEach(closePanel);
    panelStack = [];
  }

  // ─── Map (Leaflet) + Flights overlay ────────────────────────────────
  let leafletMap = null;
  let flightsLayer = null;
  let flightMarkers = new Map();    // icao → marker
  let flightsTimer = null;
  let flightsActive = false;

  function ensureLeafletMap() {
    if (leafletMap) return leafletMap;
    if (typeof L === 'undefined') {
      console.warn('[Sentry] Leaflet not loaded');
      return null;
    }
    const body = panels.map.querySelector('.sentry-panel-body');
    body.innerHTML = '';
    const div = document.createElement('div');
    div.className = 'sentry-leaflet';
    body.appendChild(div);

    leafletMap = L.map(div, {
      zoomControl: true,
      attributionControl: true,
      worldCopyJump: true
    }).setView([20, 0], 2);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '© OpenStreetMap'
    }).addTo(leafletMap);

    flightsLayer = L.layerGroup().addTo(leafletMap);

    // Re-poll on pan/zoom (debounced) so flights track the visible window
    let debounce = null;
    leafletMap.on('moveend zoomend', () => {
      if (!flightsActive) return;
      clearTimeout(debounce);
      debounce = setTimeout(pollFlights, 400);
    });

    return leafletMap;
  }

  function openMap(query) {
    openPanel('map');
    panels.map.querySelector('.sentry-panel-title').textContent =
      query ? `Map · ${query}` : 'Map';

    // Leaflet needs the container visible to compute size — defer init by a tick.
    setTimeout(() => {
      const map = ensureLeafletMap();
      if (!map) return;
      map.invalidateSize();

      if (query) {
        fetch('/api/sentry/geocode?q=' + encodeURIComponent(query))
          .then(r => r.ok ? r.json() : null)
          .then(j => {
            if (!j || j.error) return;
            if (Array.isArray(j.bbox) && j.bbox.length === 4) {
              // Nominatim bbox: [south, north, west, east]
              const [s, n, w, e] = j.bbox;
              map.fitBounds([[s, w], [n, e]], { padding: [20, 20] });
            } else if (typeof j.lat === 'number' && typeof j.lon === 'number') {
              map.setView([j.lat, j.lon], 11);
            }
          })
          .catch(() => {});
      }
    }, 60);
  }

  // ─── Flights overlay ────────────────────────────────────────────────
  function toggleFlights() {
    flightsActive = !flightsActive;
    const chip = panels.map.querySelector('[data-toggle="flights"]');
    if (chip) chip.classList.toggle('active', flightsActive);

    if (flightsActive) {
      ensureLeafletMap();
      pollFlights();
      flightsTimer = setInterval(pollFlights, 12000);
    } else {
      if (flightsTimer) { clearInterval(flightsTimer); flightsTimer = null; }
      if (flightsLayer) flightsLayer.clearLayers();
      flightMarkers.clear();
    }
  }

  function pollFlights() {
    if (!leafletMap || !flightsActive) return;
    const b = leafletMap.getBounds();
    const params = new URLSearchParams({
      lamin: b.getSouth().toFixed(3),
      lomin: Math.max(-180, b.getWest()).toFixed(3),
      lamax: b.getNorth().toFixed(3),
      lomax: Math.min(180, b.getEast()).toFixed(3)
    });
    fetch('/api/sentry/flights?' + params.toString(), { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(j => {
        if (!j || !Array.isArray(j.flights)) return;
        renderFlights(j.flights);
      })
      .catch(() => {});
  }

  const PLANE_SVG =
    '<svg viewBox="0 0 24 24"><path d="M12 2 L13.2 9.5 L22 12 L13.2 14.5 L12 22 L10.8 14.5 L2 12 L10.8 9.5 Z"/></svg>';

  function makePlaneIcon(heading, onGround) {
    return L.divIcon({
      className: 'sentry-plane' + (onGround ? ' ground' : ''),
      html: `<div style="transform:rotate(${heading || 0}deg)">${PLANE_SVG}</div>`,
      iconSize: [24, 24],
      iconAnchor: [12, 12]
    });
  }

  function renderFlights(flights) {
    const seen = new Set();
    for (const f of flights) {
      if (!f.icao || typeof f.lat !== 'number' || typeof f.lon !== 'number') continue;
      seen.add(f.icao);
      const hdg = (typeof f.hdg === 'number') ? f.hdg : 0;

      let marker = flightMarkers.get(f.icao);
      if (!marker) {
        marker = L.marker([f.lat, f.lon], {
          icon: makePlaneIcon(hdg, f.onGround),
          interactive: true
        });
        marker.addTo(flightsLayer);
        flightMarkers.set(f.icao, marker);
      } else {
        // setLatLng triggers smooth CSS transition on the inner div
        marker.setLatLng([f.lat, f.lon]);
        marker.setIcon(makePlaneIcon(hdg, f.onGround));
      }
      const cs   = (f.callsign || '').trim() || f.icao;
      const alt  = f.alt != null ? Math.round(f.alt) + ' m' : '–';
      const vel  = f.vel != null ? Math.round(f.vel * 3.6) + ' km/h' : '–';
      const ctry = f.country || '';
      marker.bindPopup(
        `<b>${escapeHtml(cs)}</b><br>${escapeHtml(ctry)}<br>alt ${alt} · spd ${vel}`,
        { closeButton: false }
      );
    }
    // Drop markers that left the bbox
    for (const [icao, m] of flightMarkers.entries()) {
      if (!seen.has(icao)) {
        flightsLayer.removeLayer(m);
        flightMarkers.delete(icao);
      }
    }
  }

  function searchImages(query) {
    const body = panels.images.querySelector('.sentry-panel-body');
    panels.images.querySelector('.sentry-panel-title').textContent = `Images · ${query}`;
    body.innerHTML = '<div class="sentry-empty">Searching…</div>';
    openPanel('images');

    fetch('/api/web-search/images?q=' + encodeURIComponent(query))
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then(json => renderImages(body, json))
      .catch(() => {
        // Fallback: open DuckDuckGo image search in the panel iframe
        body.innerHTML = '';
        const iframe = document.createElement('iframe');
        iframe.src = 'https://duckduckgo.com/?q=' + encodeURIComponent(query) + '&iax=images&ia=images';
        iframe.setAttribute('referrerpolicy', 'no-referrer');
        body.appendChild(iframe);
      });
  }

  function renderImages(body, json) {
    const arr = Array.isArray(json) ? json
              : (json && (json.results || json.images || json.items)) || [];
    if (!arr.length) {
      body.innerHTML = '<div class="sentry-empty">No images found.</div>';
      return;
    }
    const grid = document.createElement('div');
    grid.className = 'sentry-img-grid';
    arr.slice(0, 24).forEach(it => {
      const url = typeof it === 'string' ? it : (it.thumbnail || it.url || it.image || it.src);
      if (!url) return;
      const img = document.createElement('img');
      img.src = url;
      img.loading = 'lazy';
      img.referrerPolicy = 'no-referrer';
      grid.appendChild(img);
    });
    body.innerHTML = '';
    body.appendChild(grid);
  }

  function openCctv(place) {
    // Phase 1: route to YouTube live search. There's no free worldwide CCTV
    // dataset; YouTube live broadcasts cover most major cities (Times Square,
    // Shibuya, Venice, etc.). Phase 2 can layer regional traffic-cam catalogs
    // (NYC DOT, Caltrans, TfL) via /api/sentry/cctv/dot.
    const body = panels.video.querySelector('.sentry-panel-body');
    panels.video.querySelector('.sentry-panel-title').textContent = `CCTV · ${place}`;
    body.innerHTML = '';
    const iframe = document.createElement('iframe');
    // Append "live cam" to bias YouTube toward 24/7 livestreams of that place.
    const q = place + ' live cam';
    iframe.src = 'https://www.youtube.com/embed?listType=search&list=' + encodeURIComponent(q);
    iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture';
    iframe.setAttribute('referrerpolicy', 'no-referrer');
    iframe.setAttribute('allowfullscreen', '');
    body.appendChild(iframe);
    openPanel('video');
  }

  function playVideo(query) {
    const body = panels.video.querySelector('.sentry-panel-body');
    panels.video.querySelector('.sentry-panel-title').textContent = `Video · ${query}`;
    body.innerHTML = '';
    // youtube-nocookie embedded search (lists results that user can pick from)
    const iframe = document.createElement('iframe');
    iframe.src = 'https://www.youtube.com/embed?listType=search&list=' + encodeURIComponent(query);
    iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture';
    iframe.setAttribute('referrerpolicy', 'no-referrer');
    iframe.setAttribute('allowfullscreen', '');
    body.appendChild(iframe);
    openPanel('video');
  }

  // ─── Utils ───────────────────────────────────────────────────────────
  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  // Public
  window.MinsbotSentry = {
    enter,
    exit,
    setSpeaking,
    handleText,
    isActive: () => active
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
