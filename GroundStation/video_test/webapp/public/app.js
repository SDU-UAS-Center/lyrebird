const grid = document.querySelector('#grid');
const summary = document.querySelector('#summary');
const emptyState = document.querySelector('#emptyState');
const healthGrid = document.querySelector('#healthGrid');
const telemetryGrid = document.querySelector('#telemetryGrid');
const videoChartsGrid = document.querySelector('#videoChartsGrid');
const telemetryChartsGrid = document.querySelector('#telemetryChartsGrid');
const publishTargets = document.querySelector('#publishTargets');
const publishGrid = document.querySelector('#publishGrid');
const publishFilter = document.querySelector('#publishFilter');
const publishHttpOnly = document.querySelector('#publishHttpOnly');
const settingsGrid = document.querySelector('#settingsGrid');
const tileTemplate = document.querySelector('#tileTemplate');
// Per-drone payload capabilities, from the phone's /config: name -> { hasThermal }.
// Empty until the first successful fetch; the capture buttons stay disabled meanwhile.
const droneCapabilities = new Map();
const droneModal = document.querySelector('#droneModal');
const modalCloseBtn = document.querySelector('#modalCloseBtn');
const modalTitle = document.querySelector('#modalTitle');
const modalSubtitle = document.querySelector('#modalSubtitle');
const modalTelemetry = document.querySelector('#modalTelemetry');
const modalStats = document.querySelector('#modalStats');
const modalRecovery = document.querySelector('#modalRecovery');
const modalEvent = document.querySelector('#modalEvent');
const modalVideoHost = document.querySelector('#modalVideoHost');
const modalStreamingSelect = document.querySelector('#modalStreamingSelect');
const modalStreamingPath = document.querySelector('#modalStreamingPath');


const players = new Map();
const healthCards = new Map();
const settingsCards = new Map();
const telemetryCards = new Map();
const chartInstances = new Map();
const chartHistory = new Map();
const telemetryRateState = new Map();
const healthTrendState = new Map();
const chartRangeSelects = [...document.querySelectorAll('[data-chart-range]')];
const CHART_HISTORY_SECONDS = 60 * 60;
const CHART_RANGE_STORAGE_KEY = 'lyrebird-chart-range-seconds';
const chartTimeFormatter = new Intl.DateTimeFormat(undefined, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

function formatChartTime(time) {
  if (typeof time !== 'number' || !Number.isFinite(time)) return '';
  return chartTimeFormatter.format(new Date(time * 1000));
}

function validChartRange(value) {
  const seconds = Number(value);
  return [300, 900, 1800, 3600].includes(seconds) ? seconds : 300;
}

let chartRangeSeconds = (() => {
  try {
    return validChartRange(localStorage.getItem(CHART_RANGE_STORAGE_KEY));
  } catch (_) {
    return 300;
  }
})();

function setChartRange(value) {
  chartRangeSeconds = validChartRange(value);
  chartRangeSelects.forEach((select) => { select.value = String(chartRangeSeconds); });
  try { localStorage.setItem(CHART_RANGE_STORAGE_KEY, String(chartRangeSeconds)); } catch (_) {}
  updateCharts();
}

chartRangeSelects.forEach((select) => {
  select.value = String(chartRangeSeconds);
  select.addEventListener('change', () => setChartRange(select.value));
});

let latestState = null;
let selectedDroneName = null;
let modalMountedPlayer = null;
let modalReturnAnchor = null;

function getRelayBaseUrl() {
  const configured = latestState?.mediamtxWebrtcUrl;
  if (typeof configured === 'string' && configured.trim()) {
    return configured.trim().replace(/\/$/, '');
  }
  return `http://${location.hostname}:8889`;
}

function getRelayWhepUrl(streamName) {
  return `${getRelayBaseUrl()}/${encodeURIComponent(streamName)}/whep`;
}

const droneColors = ['#42d392', '#6fb6ff', '#ffcc66', '#ff6b6b', '#c48cff', '#4dd0e1', '#f48fb1', '#a3e635'];
const receiverBufferPolicy = {
  mode: 'adaptive-low-latency',
  minMs: 40,
  initialMs: 60,
  stepMs: 20,
  maxMs: 220,
  stableAfterMs: 12_000,
};

const videoChartDefinitions = [
  { key: 'fps', title: 'Decoded FPS', unit: 'fps' },
  { key: 'bitrateKbps', title: 'Browser Receive Bitrate', unit: 'kbps' },
  { key: 'framesDropped', title: 'Dropped Frames', unit: 'frames' },
  { key: 'packetsLost', title: 'Packets Lost', unit: 'packets' },
  { key: 'jitter', title: 'Jitter', unit: 's' },
  { key: 'jitterBufferTargetMs', title: 'Receiver Jitter Target', unit: 'ms' },
  { key: 'jitterBufferDelayMs', title: 'Receiver Jitter Delay', unit: 'ms/frame' },
  { key: 'freezeCount', title: 'Receiver Freezes', unit: 'count' },
  { key: 'nackCount', title: 'NACK Requests', unit: 'count' },
  { key: 'pliCount', title: 'PLI Requests', unit: 'count' },
  { key: 'keyFramesDecoded', title: 'Keyframes Decoded', unit: 'frames' },
  { key: 'inboundFramesInError', title: 'MediaMTX Frame Errors', unit: 'frames' },
];

const telemetryChartDefinitions = [
  { key: 'telemetryRate', title: 'Telemetry Packet Rate', unit: 'Hz' },
  { key: 'batteryLevel', title: 'Battery Level', unit: '%' },
  { key: 'satelliteCount', title: 'Satellite Count', unit: 'sat' },
  { key: 'altitude', title: 'Altitude ASL', unit: 'm' },
  { key: 'distanceToHome', title: 'Distance To Home', unit: 'm' },
  { key: 'speedMagnitude', title: 'Speed Magnitude', unit: 'm/s' },
  { key: 'phoneBattery', title: 'Phone Battery', unit: '%' },
  { key: 'wifiRssi', title: 'Wi-Fi RSSI', unit: 'dBm' },
];

const chartGroups = {
  videoCharts: { grid: videoChartsGrid, definitions: videoChartDefinitions },
  telemetryCharts: { grid: telemetryChartsGrid, definitions: telemetryChartDefinitions },
};

const summaryDefinitions = [
  ['detected', 'Detected Phones'],
  ['active', 'Active'],
  ['discovered', 'Discovered'],
  ['telemetry', 'Telemetry'],
  ['streams', 'Streams Ready'],
  ['browser', 'Browser WebRTC'],
  ['logFile', 'Log File', 'wide'],
];

const publishCatalog = [
  ['HTTP Flight Control', 'Direct phone commands on http://DRONE_IP:8080. These hit the Lyrebird Android app first; the phone then calls the DJI SDK. Use these for quick bench tests or when ROS is not running.', [
    ['Takeoff', 'POST', '/send/takeoff', '', 'Requests DJI takeoff. The aircraft arms as part of the DJI takeoff flow.'],
    ['Land', 'POST', '/send/land', '', 'Requests immediate landing from the current position.'],
    ['Return To Home', 'POST', '/send/RTH', '', 'Starts DJI return-to-home using the currently configured home point and RTH altitude.'],
    ['Goto Altitude', 'POST', '/send/gotoAltitude', '35', 'Commands a target altitude in meters while keeping the current horizontal position.'],
    ['Goto Yaw', 'POST', '/send/gotoYaw', '180', 'Commands an absolute aircraft heading in degrees.'],
    ['Set RTH Altitude', 'POST', '/send/setRTHAltitude', '60', 'Updates the DJI return-to-home altitude in meters before an RTH command is needed.'],
  ]],
  ['HTTP Virtual Stick', 'Manual-control style inputs and interruption commands. Virtual stick must be enabled before velocity-like control is useful, and stick values are normalized rather than physical units.', [
    ['Enable Virtual Stick', 'POST', '/send/enableVirtualStick', '', 'Enables DJI virtual stick mode so subsequent stick commands are accepted.'],
    ['Stick Command', 'POST', '/send/stick', '0.0,0.2,0.0,-0.1', 'Sends leftX,leftY,rightX,rightY normalized stick values. Typical range is -1.0 to 1.0.'],
    ['Abort Mission', 'POST', '/send/abortMission', '', 'Stops the current mission flow, zeros stick input, and disables virtual stick where supported.'],
    ['Abort All', 'POST', '/send/abortAll', '', 'Broad cancellation path for queued or active mission activity.'],
    ['Deactivate Manual Override', 'POST', '/send/deactivateManualOverride', '', 'Returns control to autonomous command handling after manual override has been active.'],
  ]],
  ['HTTP Camera And Gimbal', 'Payload commands routed through the phone to DJI camera/gimbal APIs. These are useful for operator payload checks independently of navigation.', [
    ['Gimbal Pitch Move', 'POST', '/send/gimbal/pitch', '0,-20,0', 'Sends an absolute roll,pitch,yaw command; this route is normally used for pitch.'],
    ['Gimbal Yaw Move', 'POST', '/send/gimbal/yaw', '0,0,45', 'Sends an absolute roll,pitch,yaw command; this route is normally used for yaw.'],
    ['Camera Zoom', 'POST', '/send/camera/zoom', '2.0', 'Sets the camera zoom ratio directly, subject to the aircraft/camera zoom limits.'],
    ['Start Recording', 'POST', '/send/camera/startRecording', '', 'Starts camera recording on the aircraft payload.'],
    ['Stop Recording', 'POST', '/send/camera/stopRecording', '', 'Stops camera recording on the aircraft payload.'],
    ['Capture Photo', 'POST', '/send/capture', '', 'Trips one shutter on the payload and stores to the SD card; the file is fetched later by name.'],
    ['Capture Temperature', 'POST', '/send/captureTemperature', '', 'Reads the thermal spot temperature synchronously; no shutter, nothing stored.'],
    ['Capture Thermal Image', 'POST', '/send/captureThermalImage', '', 'Trips one shutter on the thermal payload, storing thermal R-JPEG plus wide/zoom when enabled.'],
  ]],
  ['HTTP Waypoint And Mission', 'Higher-level navigation routes for single-waypoint and multi-waypoint movement. These commands carry coordinates in the request body, so check units before publishing from scripts.', [
    ['Goto Waypoint (Hold Heading)', 'POST', '/send/gotoWaypointHoldHeading', '55.6761,12.5683,35,90,4', 'Single lat,lon,alt waypoint plus final yaw and maxSpeed: lat,lon,alt,yaw,maxSpeed. The nose stays on the commanded heading while translating.'],
    ['Goto Waypoint (Nose Forward)', 'POST', '/send/gotoWaypointNoseForward', '55.6761,12.5683,35,90,4', 'Single lat,lon,alt waypoint plus final yaw and maxSpeed: lat,lon,alt,yaw,maxSpeed. The nose follows the path; yaw is the arrival heading.'],
    ['Navigate DJI Native', 'POST', '/send/navigateTrajectoryDJINative', '4.0;55.6761,12.5683,25;55.6768,12.5692,32', 'DJI native mission upload with speed first, then at least two lat,lon,alt waypoints.'],
    ['Abort DJI Mission', 'POST', '/send/abort/DJIMission', '', 'Stops the active DJI native mission path.'],
  ]],
  ['HTTP Sensing And Status', 'Read/control endpoints for operator state, AutoSensing, and phone-side configuration. These are useful for checking what the app believes before sending motion commands.', [
    ['Start AutoSensing', 'POST', '/send/autoSensing/start', '', 'Enables AutoSensing on the device.'],
    ['Stop AutoSensing', 'POST', '/send/autoSensing/stop', '', 'Disables AutoSensing on the device.'],
    ['Manual Override State', 'POST', '/get/isManualOverrideActive', '', 'Returns whether manual override is currently active.'],
    ['AutoSensing Status', 'POST', '/get/autoSensing/status', '', 'Returns AutoSensing enablement and target count.'],
    ['AutoSensing Targets', 'POST', '/get/autoSensing/targets', '', 'Returns the current detected target array from the phone app.'],
    ['Config', 'GET', '/config', '', 'Reads IP, drone name, and port configuration from the phone app.'],
  ]],
];

class WhepPlayer {
  constructor(drone, tile, options = {}) {
    this.drone = drone;
    this.tile = tile;
    this.options = options;
    this.video = options.video || tile.querySelector('video');
    this.badge = options.badge || tile.querySelector('.statusBadge');
    this.events = options.events || tile.querySelector('.events');
    this.pc = null;
    this.resourceUrl = null;
    this.reconnectTimer = null;
    this.statsTimer = null;
    this.isConnecting = false;
    this.lastFramesDecoded = 0;
    this.lastStatsAt = 0;
    this.connectionLosses = 0;
    this.stalls = 0;
    this.decodeErrors = 0;
    this.lastVideoTime = 0;
    this.lastVideoProgressAt = Date.now();
    this.receiverBufferTargetMs = receiverBufferPolicy.initialMs;
    this.receiverBufferSupported = false;
    this.receiverBufferAppliedMs = null;
    this.receiverStableSince = Date.now();
    this.lastPacketsLost = 0;
    this.lastFramesDropped = 0;
    this.lastFreezeCount = 0;
    this.lastJitterBufferDelay = 0;
    this.lastJitterBufferEmittedCount = 0;
    this.lastBytesReceived = 0;
    this.latestSdpRecovery = emptySdpRecovery();

    this.video.addEventListener('error', () => {
      this.decodeErrors += 1;
      this.report('video_error', { error: this.video.error?.message || this.video.error?.code || 'unknown' });
    });
    this.video.addEventListener('stalled', () => this.report('video_stalled_event'));
    this.video.addEventListener('waiting', () => this.report('video_waiting'));
  }

  async connect() {
    if (this.isConnecting) return;
    this.isConnecting = true;
    this.close();
    this.setStatus('connecting', 'status-warn');
    const whepUrl = getRelayWhepUrl(this.drone.streamName);

    try {
      this.pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
      const videoTransceiver = this.pc.addTransceiver('video', { direction: 'recvonly' });
      this.applyReceiverBufferTarget(videoTransceiver.receiver, 'transceiver_created');
      this.pc.ontrack = (event) => {
        this.applyReceiverBufferTarget(event.receiver, 'track_attached');
        if (event.streams?.[0]) {
          this.video.srcObject = event.streams[0];
          this.video.play().catch(() => {});
          const mode = this.drone.lastTelemetry?.streaming?.mode?.toUpperCase() || 'WEBRTC';
          const modeLabel = mode === 'WEBRTC' ? 'WebRTC' : mode;
          this.setStatus(`playing (${modeLabel})`, 'status-good');
          this.report('track_attached');
        }
      };
      this.pc.onconnectionstatechange = () => {
        const state = this.pc?.connectionState || 'closed';
        if (state === 'connected') {
          const mode = this.drone.lastTelemetry?.streaming?.mode?.toUpperCase() || 'WEBRTC';
          const modeLabel = mode === 'WEBRTC' ? 'WebRTC' : mode;
          this.setStatus(`playing (${modeLabel})`, 'status-good');
        } else {
          this.setStatus(state, state === 'connected' ? 'status-good' : 'status-warn');
        }
        if (state === 'failed' || state === 'disconnected') {
          this.connectionLosses += 1;
          this.report('peer_connection_loss', { state });
          this.scheduleReconnect();
        }
      };

      const offer = await this.pc.createOffer();
      await this.pc.setLocalDescription(offer);
      await this.waitForIceGathering();
      this.latestSdpRecovery = summarizeSdpRecovery(this.pc.localDescription?.sdp || '', '');
      const response = await fetch(whepUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/sdp' },
        body: this.pc.localDescription.sdp,
      });
      if (!response.ok) throw new Error(`WHEP ${response.status}: ${await response.text()}`);
      this.resourceUrl = response.headers.get('location');
      const answerSdp = await response.text();
      await this.pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
      this.latestSdpRecovery = summarizeSdpRecovery(this.pc.localDescription?.sdp || '', answerSdp);
      this.report('whep_answer_set', { url: whepUrl, recovery: this.latestSdpRecovery.summary });
      this.startStats();
    } catch (error) {
      const mode = this.drone.lastTelemetry?.streaming?.mode?.toLowerCase() || 'webrtc';
      this.setStatus('error', 'status-bad');
      this.report('connect_error', { error: error.message, mode, relayUrl: whepUrl });
      this.scheduleReconnect();
    } finally {
      this.isConnecting = false;
    }
  }

  waitForIceGathering() {
    if (!this.pc || this.pc.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise((resolve) => {
      const timeout = setTimeout(resolve, 5000);
      this.pc.addEventListener('icegatheringstatechange', () => {
        if (this.pc?.iceGatheringState === 'complete') {
          clearTimeout(timeout);
          resolve();
        }
      });
    });
  }

  startStats() {
    clearInterval(this.statsTimer);
    this.lastFramesDecoded = 0;
    this.lastStatsAt = performance.now();
    this.lastVideoTime = this.video.currentTime || 0;
    this.lastVideoProgressAt = Date.now();
    this.receiverStableSince = Date.now();
    this.lastPacketsLost = 0;
    this.lastFramesDropped = 0;
    this.lastFreezeCount = 0;
    this.lastJitterBufferDelay = 0;
    this.lastJitterBufferEmittedCount = 0;
    this.lastBytesReceived = 0;
    this.applyReceiverBufferTarget(null, 'stats_started');
    this.statsTimer = setInterval(() => this.collectStats(), 1000);
  }

  applyReceiverBufferTarget(receiver = null, reason = 'update') {
    const targetSeconds = this.receiverBufferTargetMs / 1000;
    const receivers = receiver ? [receiver] : (this.pc?.getReceivers?.() || []);
    let supported = false;
    for (const activeReceiver of receivers) {
      if (activeReceiver?.track?.kind !== 'video') continue;
      if (!('jitterBufferTarget' in activeReceiver)) continue;
      try {
        activeReceiver.jitterBufferTarget = targetSeconds;
        supported = true;
      } catch (error) {
        this.report('receiver_jitter_target_error', { error: error.message, targetMs: this.receiverBufferTargetMs, reason });
      }
    }
    if (supported) {
      const changed = this.receiverBufferAppliedMs !== this.receiverBufferTargetMs;
      this.receiverBufferSupported = true;
      this.receiverBufferAppliedMs = this.receiverBufferTargetMs;
      if (changed) this.report('receiver_jitter_target_set', { targetMs: this.receiverBufferTargetMs, reason });
    }
  }

  async collectStats() {
    if (!this.pc) return;
    const now = performance.now();
    const stats = await this.pc.getStats();
    let inbound = null;
    stats.forEach((report) => {
      if (report.type === 'inbound-rtp' && report.kind === 'video') inbound = report;
    });
    if (!inbound) return;
    const codec = inbound.codecId ? stats.get(inbound.codecId) : null;

    const seconds = Math.max((now - this.lastStatsAt) / 1000, 0.001);
    const framesDecoded = inbound.framesDecoded || 0;
    const fps = (framesDecoded - this.lastFramesDecoded) / seconds;
    this.lastFramesDecoded = framesDecoded;
    this.lastStatsAt = now;

    const currentTime = this.video.currentTime || 0;
    const freezeCount = inbound.freezeCount || 0;
    const packetsLost = inbound.packetsLost || 0;
    const framesDropped = inbound.framesDropped || 0;
    const bytesReceived = inbound.bytesReceived || 0;
    const bitrateKbps = this.lastBytesReceived > 0 ? ((bytesReceived - this.lastBytesReceived) * 8) / seconds / 1000 : 0;
    this.lastBytesReceived = bytesReceived;
    const jitterBufferMetrics = this.computeJitterBufferMetrics(inbound);
    this.adaptReceiverBuffer({
      jitterSeconds: inbound.jitter || 0,
      packetsLost,
      framesDropped,
      freezeCount,
      jitterDelayMs: jitterBufferMetrics.delayMs,
    });

    if (currentTime > this.lastVideoTime + 0.05) {
      this.lastVideoTime = currentTime;
      this.lastVideoProgressAt = Date.now();
    } else if (Date.now() - this.lastVideoProgressAt > 5000) {
      this.stalls += 1;
      this.lastVideoProgressAt = Date.now();
      this.report('video_progress_stalled', { stalls: this.stalls });
      this.scheduleReconnect();
    }

    const payload = {
      drone: this.drone.name,
      connectionState: this.pc.connectionState,
      iceConnectionState: this.pc.iceConnectionState,
      fps: Number(fps.toFixed(2)),
      framesDecoded,
      framesDropped,
      packetsLost,
      jitter: Number((inbound.jitter || 0).toFixed(4)),
      jitterBufferTargetMs: this.receiverBufferAppliedMs ?? this.receiverBufferTargetMs,
      jitterBufferSupported: this.receiverBufferSupported,
      jitterBufferDelayMs: jitterBufferMetrics.delayMs,
      jitterBufferEmittedCount: jitterBufferMetrics.emittedCount,
      jitterBufferMinimumDelayMs: msOrNull(inbound.jitterBufferMinimumDelay),
      jitterBufferTargetDelayMs: msOrNull(inbound.jitterBufferTargetDelay),
      receiverBufferPolicy: receiverBufferPolicy.mode,
      freezeCount,
      keyFramesDecoded: inbound.keyFramesDecoded || 0,
      pliCount: inbound.pliCount || 0,
      firCount: inbound.firCount || 0,
      nackCount: inbound.nackCount || 0,
      pauseCount: inbound.pauseCount || 0,
      totalFreezesDuration: Number((inbound.totalFreezesDuration || 0).toFixed(3)),
      bytesReceived,
      bitrateKbps: Number(Math.max(0, bitrateKbps).toFixed(1)),
      frameWidth: inbound.frameWidth || this.video.videoWidth || 0,
      frameHeight: inbound.frameHeight || this.video.videoHeight || 0,
      codecMimeType: codec?.mimeType || '',
      codecPayloadType: codec?.payloadType ?? null,
      codecFmtpLine: codec?.sdpFmtpLine || '',
      sdpRecovery: this.latestSdpRecovery,
      connectionLosses: this.connectionLosses,
      stalls: this.stalls,
      decodeErrors: this.decodeErrors,
    };
    this.updateStats(payload);
    if (selectedDroneName === this.drone.name) updateModalForDrone(this.drone.name);
    addChartSample(this.drone.name, {
      fps: payload.fps,
      bitrateKbps: payload.bitrateKbps,
      framesDropped: payload.framesDropped,
      packetsLost: payload.packetsLost,
      jitter: payload.jitter,
      jitterBufferTargetMs: payload.jitterBufferTargetMs,
      jitterBufferDelayMs: payload.jitterBufferDelayMs,
      freezeCount: payload.freezeCount,
      nackCount: payload.nackCount,
      pliCount: payload.pliCount,
      keyFramesDecoded: payload.keyFramesDecoded,
    });
    fetch('/api/client-stats', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    }).catch(() => {});
  }

  updateStats(stats) {
    this.latestStats = stats;
    setText(this.tile, '[data-stat="fps"]', stats.fps.toFixed(1));
    setText(this.tile, '[data-stat="framesDecoded"]', stats.framesDecoded);
    setText(this.tile, '[data-stat="framesDropped"]', stats.framesDropped);
    setText(this.tile, '[data-stat="packetsLost"]', stats.packetsLost);
    setText(this.tile, '[data-stat="jitterBufferTarget"]', stats.jitterBufferSupported ? `${stats.jitterBufferTargetMs}ms` : 'n/a');
    setText(this.tile, '[data-stat="jitter"]', stats.jitter);
    setText(this.tile, '[data-stat="size"]', stats.frameWidth && stats.frameHeight ? `${stats.frameWidth}x${stats.frameHeight}` : '-');
    this.options.onStats?.(stats);
  }

  computeJitterBufferMetrics(inbound) {
    const emittedCount = inbound.jitterBufferEmittedCount || 0;
    const delay = inbound.jitterBufferDelay || 0;
    const emittedDelta = Math.max(0, emittedCount - this.lastJitterBufferEmittedCount);
    const delayDelta = Math.max(0, delay - this.lastJitterBufferDelay);
    this.lastJitterBufferEmittedCount = emittedCount;
    this.lastJitterBufferDelay = delay;
    const delayMs = emittedDelta > 0 ? Number(((delayDelta / emittedDelta) * 1000).toFixed(1)) : null;
    return { delayMs, emittedCount };
  }

  adaptReceiverBuffer(metrics) {
    const now = Date.now();
    const lostDelta = Math.max(0, metrics.packetsLost - this.lastPacketsLost);
    const dropDelta = Math.max(0, metrics.framesDropped - this.lastFramesDropped);
    const freezeDelta = Math.max(0, metrics.freezeCount - this.lastFreezeCount);
    this.lastPacketsLost = metrics.packetsLost;
    this.lastFramesDropped = metrics.framesDropped;
    this.lastFreezeCount = metrics.freezeCount;

    const jitterMs = metrics.jitterSeconds * 1000;
    const latePressure = lostDelta > 0 || dropDelta > 0 || freezeDelta > 0 || jitterMs > this.receiverBufferTargetMs * 0.8;
    if (latePressure) {
      this.receiverStableSince = now;
      const nextTarget = Math.min(receiverBufferPolicy.maxMs, this.receiverBufferTargetMs + receiverBufferPolicy.stepMs);
      if (nextTarget !== this.receiverBufferTargetMs) {
        this.receiverBufferTargetMs = nextTarget;
        this.applyReceiverBufferTarget(null, 'late_pressure');
      }
      return;
    }

    if (now - this.receiverStableSince < receiverBufferPolicy.stableAfterMs) return;
    const nextTarget = Math.max(receiverBufferPolicy.minMs, this.receiverBufferTargetMs - receiverBufferPolicy.stepMs);
    if (nextTarget !== this.receiverBufferTargetMs) {
      this.receiverBufferTargetMs = nextTarget;
      this.receiverStableSince = now;
      this.applyReceiverBufferTarget(null, 'stable_decay');
    }
  }

  report(type, extra = {}) {
    const message = `${new Date().toLocaleTimeString()} ${type}`;
    if (this.events) this.events.textContent = message;
    if (selectedDroneName === this.drone.name) modalEvent.textContent = `${message}\n${JSON.stringify(extra, null, 2)}`;
    this.options.onReport?.(type, extra, message);
    fetch('/api/client-stats', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ drone: this.drone.name, event: type, ...extra }),
    }).catch(() => {});
  }

  setStatus(text, className = '') {
    if (!this.badge) return;
    this.badge.textContent = text;
    this.badge.className = `badge statusBadge ${className}`;
  }

  scheduleReconnect() {
    if (this.reconnectTimer) return;
    // Always consume through MediaMTX relay, regardless of source mode.
    if (!this.drone.mediaMtx?.ready) return;
    const delay = 2500;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  close() {
    clearInterval(this.statsTimer);
    this.statsTimer = null;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.pc?.close();
    this.pc = null;
    if (this.video.srcObject) {
      this.video.srcObject.getTracks().forEach((track) => track.stop());
      this.video.srcObject = null;
    }
    if (this.video.src) {
      this.video.removeAttribute('src');
      this.video.load();
    }
  }
}

function emptySdpRecovery() {
  return summarizeSdpRecovery('', '');
}

function summarizeSdpRecovery(localSdp, remoteSdp) {
  const local = parseSdpRecovery(localSdp);
  const remote = parseSdpRecovery(remoteSdp);
  const negotiated = remoteSdp ? remote : local;
  const missing = [];
  if (!negotiated.nack) missing.push('NACK');
  if (!negotiated.pli) missing.push('PLI');
  if (!negotiated.fir) missing.push('FIR');
  if (!negotiated.rtx) missing.push('RTX');
  const health = !remoteSdp ? 'pending' : (negotiated.nack && (negotiated.pli || negotiated.fir) ? (negotiated.rtx ? 'strong' : 'usable') : 'weak');
  return {
    health,
    summary: remoteSdp ? `${health}; ${missing.length ? `missing ${missing.join(', ')}` : 'NACK/PLI/FIR/RTX present'}` : 'waiting for WHEP answer',
    negotiated,
    local,
    remote,
  };
}

function parseSdpRecovery(sdp) {
  const result = {
    nack: false,
    pli: false,
    fir: false,
    rtx: false,
    h264: false,
    h264PayloadTypes: [],
    rtxPayloadTypes: [],
    codecs: [],
    h264Profiles: [],
    h264PacketizationModes: [],
  };
  if (!sdp) return result;
  const fmtpByPayload = new Map();
  for (const rawLine of sdp.split(/\r?\n/)) {
    const line = rawLine.trim();
    const rtcpFb = line.match(/^a=rtcp-fb:(\*|\d+)\s+(.+)$/i);
    if (rtcpFb) {
      const feedback = rtcpFb[2].toLowerCase();
      if (feedback.includes('nack')) result.nack = true;
      if (feedback.includes('nack pli')) result.pli = true;
      if (feedback.includes('ccm fir')) result.fir = true;
    }
    const rtpMap = line.match(/^a=rtpmap:(\d+)\s+([^/\s]+)(?:\/([^\s]+))?/i);
    if (rtpMap) {
      const payloadType = rtpMap[1];
      const codec = rtpMap[2].toUpperCase();
      result.codecs.push(`${payloadType}:${codec}`);
      if (codec === 'RTX') {
        result.rtx = true;
        result.rtxPayloadTypes.push(payloadType);
      }
      if (codec === 'H264') {
        result.h264 = true;
        result.h264PayloadTypes.push(payloadType);
      }
    }
    const fmtp = line.match(/^a=fmtp:(\d+)\s+(.+)$/i);
    if (fmtp) fmtpByPayload.set(fmtp[1], fmtp[2]);
  }
  for (const payloadType of result.h264PayloadTypes) {
    const fmtp = fmtpByPayload.get(payloadType) || '';
    const profile = fmtp.match(/profile-level-id=([^;\s]+)/i)?.[1];
    const packetization = fmtp.match(/packetization-mode=([^;\s]+)/i)?.[1];
    if (profile && !result.h264Profiles.includes(profile)) result.h264Profiles.push(profile);
    if (packetization && !result.h264PacketizationModes.includes(packetization)) result.h264PacketizationModes.push(packetization);
  }
  return result;
}

function render(state) {
  latestState = state;
  const connected = state.drones.filter((drone) => drone.telemetryConnected).length;
  const discovered = state.drones.filter((drone) => drone.lastDiscoveryAt).length;
  const readyStreams = state.drones.filter((drone) => drone.mediaMtx?.ready).length;
  const browserConnections = state.drones.filter((drone) => drone.browserStats?.connectionState === 'connected').length;
  const activeDrones = state.drones.filter((drone) => !drone.ignored).length;
  grid.dataset.count = String(Math.max(1, state.drones.length));
  grid.dataset.active = String(Math.max(1, activeDrones));
  updateSummary({ detected: state.drones.length, active: activeDrones, discovered, telemetry: connected, streams: readyStreams, browser: browserConnections, logFile: state.logFile });
  emptyState.hidden = state.drones.length > 0;
  renderVideoTiles(state);
  renderHealthPanels(state);
  renderTelemetryPanels(state);
  renderSettingsPanel(state);
  renderPublishTargets(state);
  renderPublishCatalog();
  // Kept current as drones come and go, rather than only when the tab is opened: a drone
  // discovered while the panel is already visible has to appear in the picker.
  refreshMavlinkTargets();
  if (selectedDroneName) updateModalForDrone(selectedDroneName);
  updateTelemetryChartSamples(state);
  updateCharts();
}

// ---- Remote settings panel (phone /config/settings + setters via server proxy) ----
function renderSettingsPanel(state) {
  for (const drone of state.drones) {
    let card = settingsCards.get(drone.name);
    if (!card) {
      card = createSettingsCard(drone.name);
      settingsCards.set(drone.name, card);
      settingsGrid.appendChild(card.element);
      loadSettings(drone.name); // auto-load on first appearance
    }
    const ip = drone.ip || drone.discoveredIp || 'no ip';
    card.subtitle.textContent = `${ip} \u00b7 telemetry ${drone.telemetryConnected ? 'ok' : 'down'}`;
  }
  for (const [name, card] of settingsCards.entries()) {
    if (!state.drones.some((drone) => drone.name === name)) {
      card.element.remove();
      settingsCards.delete(name);
    }
  }
}

function createSettingsCard(name) {
  const element = document.createElement('div');
  element.className = 'healthCard settingsCard';
  element.innerHTML = `
    <div class="healthHeader">
      <div><h3>${escapeHtml(name)}</h3><p data-role="subtitle">not loaded</p></div>
      <button type="button" class="settingsLoad" data-role="load">Refresh</button>
    </div>
    <div class="settingsBody" data-role="body" hidden>
      <dl class="settingsReadonly"></dl>
      <div class="settingsFields"></div>
      <div class="settingsStatus" data-role="status"></div>
    </div>`;
  element.querySelector('[data-role="load"]').addEventListener('click', () => loadSettings(name));
  return {
    element,
    subtitle: element.querySelector('[data-role="subtitle"]'),
    body: element.querySelector('[data-role="body"]'),
    readonly: element.querySelector('.settingsReadonly'),
    fields: element.querySelector('.settingsFields'),
    status: element.querySelector('[data-role="status"]'),
    busy: false,
  };
}

async function loadSettings(name) {
  const card = settingsCards.get(name);
  if (!card || card.busy) return;
  card.busy = true;
  card.status.textContent = 'Loading\u2026';
  try {
    const resp = await fetch(`/api/drones/${encodeURIComponent(name)}/settings`);
    const data = await resp.json();
    if (!resp.ok || !data.ok) throw new Error(data.error || `HTTP ${resp.status}`);
    renderSettingsValues(card, name, data.settings);
    card.status.textContent = `Loaded ${new Date().toLocaleTimeString()}`;
  } catch (err) {
    card.status.textContent = `Error: ${err.message}`;
  } finally {
    card.busy = false;
  }
}

function renderSettingsValues(card, name, s) {
  const ro = [
    ['Drone name', s.droneName],
    ['Detected aircraft', s.detectedAircraft],
    ['Control profile', s.controlProfile],
    ['Video source', s.videoSource],
    ['Streaming mode', s.streamingMode],
    ['WebRTC', `${s.webrtcResolution} @ ${s.webrtcFps}fps`],
    ['Detection', `${s.detectionSource} (${s.detectionsEnabled ? 'on' : 'off'})`],
    ['Edge confidence', s.edgeConfidenceThreshold],
    ['RC stick mode', s.rcControlMode],
    ['RC pairing', s.rcPairingStatus],
    ['HD frequency band', s.hdFrequencyBand],
  ];
  card.readonly.innerHTML = ro
    .map(([k, v]) => `<div><dt>${escapeHtml(k)}</dt><dd>${escapeHtml(String(v ?? 'n/a'))}</dd></div>`)
    .join('');

  const fields = [
    { key: 'droneName', label: 'Drone name', type: 'text', value: s.droneName },
    { key: 'videoSource', label: 'Video source', type: 'select', options: ['drone', 'phone', 'mock'], value: s.videoSource },
    { key: 'streamingMode', label: 'Streaming protocol', type: 'select', options: ['webrtc', 'rtsp', 'rtmp', 'agora', 'gb28181'], value: s.streamingMode, endpoint: '/streaming/mode', payloadKey: 'mode' },
    { key: 'webrtcResolution', label: 'WebRTC resolution', type: 'select', options: ['auto', '1080p', '720p', '480p'], value: s.webrtcResolution },
    { key: 'webrtcFps', label: 'WebRTC FPS', type: 'select', options: ['5', '10', '15', '20', '25', '30'], value: s.webrtcFps != null ? String(s.webrtcFps) : '' },
    { key: 'mediamtxServer', label: 'MediaMTX server (blank = auto)', type: 'text', value: s.mediamtxServer || '' },
    { key: 'rthAltitude', label: 'RTH altitude (m)', type: 'number', value: s.rthAltitude },
    { key: 'maxFlightHeight', label: 'Max flight height (m)', type: 'number', value: s.maxFlightHeight },
    { key: 'maxFlightDistance', label: 'Max distance from home (m)', type: 'number', value: s.maxFlightDistance },
    { key: 'distanceLimitEnabled', label: 'Distance limit enabled', type: 'checkbox', value: s.distanceLimitEnabled },
    { key: 'detectionsEnabled', label: 'Detections enabled', type: 'checkbox', value: s.detectionsEnabled },
    { key: 'detectionSource', label: 'Detection source', type: 'select', options: ['none', 'dji_onboard', 'yolo_on_phone'], value: s.detectionSource },
    { key: 'edgeConfidenceThreshold', label: 'Edge confidence', type: 'select', options: ['0.10', '0.15', '0.20', '0.25', '0.30', '0.40', '0.50', '0.60', '0.70'], value: s.edgeConfidenceThreshold != null ? String(s.edgeConfidenceThreshold) : '' },
    { key: 'rcControlMode', label: 'RC stick mode', type: 'select', options: ['jp', 'usa', 'ch', 'custom'], value: s.rcControlMode },
  ];
  // Group metadata comes from the settings payload itself (phone /config/settings "groups"),
  // so older phone builds without it render flat and newer ones render sectioned.
  const groups = (s && s.groups) || {};
  const groupLabels = { identity: 'Identity', video: 'Video & streaming', flight: 'Flight limits', detection: 'Detection', rc: 'Remote controller' };
  card.fields.innerHTML = '';
  let currentGroup = null;
  for (const f of fields) {
    const fgroup = groups[f.key] || null;
    if (fgroup && fgroup !== currentGroup) {
      currentGroup = fgroup;
      const header = document.createElement('div');
      header.className = 'settingsGroupLabel';
      header.textContent = groupLabels[fgroup] || fgroup;
      card.fields.appendChild(header);
    }
    const row = document.createElement('div');
    row.className = 'settingsFieldRow';
    const label = document.createElement('label');
    label.textContent = f.label;
    row.appendChild(label);
    let input;
    if (f.type === 'checkbox') {
      input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = !!f.value;
    } else if (f.type === 'select') {
      input = document.createElement('select');
      for (const opt of f.options) {
        const o = document.createElement('option');
        o.value = opt;
        o.textContent = opt;
        if (String(f.value) === opt) o.selected = true;
        input.appendChild(o);
      }
    } else {
      input = document.createElement('input');
      input.type = f.type;
      input.value = f.value == null ? '' : String(f.value);
      if (f.type === 'number') input.step = '1';
    }
    row.appendChild(input);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = 'Apply';
    btn.className = 'settingsApply';
    btn.addEventListener('click', async () => {
      if (card.busy) return;
      card.busy = true;
      const value = f.type === 'checkbox' ? input.checked : input.value;
      const endpoint = f.endpoint || `/settings/${f.key}`;
      const payload = f.payloadKey ? { [f.payloadKey]: value } : { value };
      card.status.textContent = 'Sending\u2026';
      let applied = false;
      try {
        const resp = await fetch(`/api/drones/${encodeURIComponent(name)}${endpoint}`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const data = await resp.json();
        applied = resp.ok && data.ok;
        card.status.textContent =
          applied ? `OK: ${data.message}` : `Error: ${data.error || 'HTTP ' + resp.status}`;
      } catch (err) {
        card.status.textContent = `Error: ${err.message}`;
      } finally {
        card.busy = false;
      }
      if (applied) loadSettings(name); // reflect the new value immediately
    });
    row.appendChild(btn);
    card.fields.appendChild(row);
  }

  // RC pairing actions (start / stop pairing between RC and aircraft)
  const pairingRow = document.createElement('div');
  pairingRow.className = 'settingsFieldRow';
  const pairingLabel = document.createElement('label');
  pairingLabel.textContent = 'RC pairing';
  pairingRow.appendChild(pairingLabel);
  for (const [action, labelText] of [['start', 'Start'], ['stop', 'Stop']]) {
    const pbtn = document.createElement('button');
    pbtn.type = 'button';
    pbtn.textContent = labelText;
    pbtn.className = 'settingsApply';
    pbtn.addEventListener('click', async () => {
      if (card.busy) return;
      card.busy = true;
      card.status.textContent = 'Sending\u2026';
      try {
        const resp = await fetch(`/api/drones/${encodeURIComponent(name)}/rcPairing/${action}`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: '{}',
        });
        const data = await resp.json();
        card.status.textContent =
          resp.ok && data.ok ? `OK: ${data.message}` : `Error: ${data.error || 'HTTP ' + resp.status}`;
      } catch (err) {
        card.status.textContent = `Error: ${err.message}`;
      } finally {
        card.busy = false;
      }
      loadSettings(name); // reflect pairing status immediately
    });
    pairingRow.appendChild(pbtn);
  }
  card.fields.appendChild(pairingRow);
  card.body.hidden = false;
}

// Auto-refresh settings so the panel mirrors the phones without manual clicks.
// Skipped while a card has an in-flight request or an input is focused (editing).
function refreshSettingsPeriodically() {
  for (const [name, card] of settingsCards.entries()) {
    if (card.busy) continue;
    if (card.element.contains(document.activeElement)) continue;
    loadSettings(name);
  }
}
setInterval(refreshSettingsPeriodically, 10_000);

// ---- ROS 2 bridge status (reported by the ros-monitor container) ----
// Built once and patched in place on every poll: rebuilding the whole tree
// via innerHTML (as this used to) destroys and recreates the scrollable
// table containers each time, which reset their scroll position to the top.
const rosStatus = document.querySelector('#rosStatus');
let rosElements = null;
let latestRosReport = null;
const rosSelectedDrone = { published: null, subscribed: null };

function createRosTopicTable(kind, title, blurb) {
  const element = document.createElement('div');
  element.className = 'healthCard rosTopics';
  element.innerHTML = `<div class="healthHeader"><div><h3>${escapeHtml(title)}</h3><p>${escapeHtml(blurb)}</p></div><div class="rosDronePicker"><span class="rosDronePickerLabel">Drone</span><select class="rosDroneSelect" data-role="droneSelect" aria-label="Select drone"></select><span class="pill" data-role="phonePill"></span></div></div><div class="rosTableWrap"><table class="rosTable"><thead><tr><th>Topic</th><th>Type</th><th>Rate</th><th>Last update</th><th>Last value</th></tr></thead><tbody></tbody></table></div>`;
  const select = element.querySelector('[data-role="droneSelect"]');
  select.addEventListener('change', () => {
    rosSelectedDrone[kind] = select.value || null;
    if (latestRosReport) renderRosStatus(latestRosReport);
  });
  return { element, tbody: element.querySelector('tbody'), rows: new Map(), select, phonePill: element.querySelector('[data-role="phonePill"]') };
}

function ensureRosElements() {
  if (rosElements) return rosElements;
  const summaryEl = document.createElement('div');
  summaryEl.className = 'healthCard rosSummary';
  summaryEl.innerHTML = `<div class="healthHeader"><div><h3>ros-monitor</h3><p data-role="timestamp"></p></div><span class="pill" data-role="statusPill"></span></div><dl class="healthMetrics" data-role="metrics"></dl>`;

  const pubTable = createRosTopicTable('published', 'Published topics', 'Telemetry and state topics published by the selected drone’s DjiNode, rates over a 3 s window.');
  const subTable = createRosTopicTable('subscribed', 'Subscribed topics (command surface)', 'Command topics the selected drone’s DjiNode consumes. They only show traffic when a command is actually sent.');

  const columns = document.createElement('div');
  columns.className = 'rosColumns col5050';
  columns.append(pubTable.element, subTable.element);

  rosStatus.replaceChildren(summaryEl, columns);
  rosElements = { summaryEl, pubTable, subTable };
  return rosElements;
}

function updateRosDroneSelect(table, kind, names) {
  const existing = Array.from(table.select.options, (option) => option.value);
  const changed = existing.length !== names.length || existing.some((value, i) => value !== names[i]);
  if (changed) {
    table.select.replaceChildren(...names.map((name) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      return option;
    }));
    if (!names.includes(rosSelectedDrone[kind])) rosSelectedDrone[kind] = names[0] || null;
  }
  table.select.disabled = names.length === 0;
  if (rosSelectedDrone[kind] && table.select.value !== rosSelectedDrone[kind]) table.select.value = rosSelectedDrone[kind];
}

function updateRosDronePhonePill(table, droneReport) {
  const pill = table.phonePill;
  if (!droneReport) {
    updateText(pill, 'no drone');
    updateClass(pill, 'pill');
    return;
  }
  updateText(pill, droneReport.phoneReachable ? 'phone ok' : 'phone down');
  updateClass(pill, `pill ${droneReport.phoneReachable ? 'good' : 'warn'}`);
}

function updateRosTopicTable(table, entries) {
  const seen = new Set();
  for (const [topic, t] of entries) {
    seen.add(topic);
    let row = table.rows.get(topic);
    if (!row) {
      row = document.createElement('tr');
      row.innerHTML = '<td><code></code></td><td data-role="type"></td><td data-role="rate"></td><td data-role="fresh"></td><td><span class="rosLastValue" data-role="lastValue"></span></td>';
      updateText(row.querySelector('code'), topic);
      table.tbody.appendChild(row);
      table.rows.set(topic, row);
    }
    const tone = t.seen ? 'good' : 'warn';
    const rateCell = row.querySelector('[data-role="rate"]');
    updateText(rateCell, t.seen ? `${t.rate_hz} Hz` : '—');
    updateClass(rateCell, `rate ${tone}`);
    const freshCell = row.querySelector('[data-role="fresh"]');
    updateText(freshCell, t.seen ? (t.seconds_ago == null ? 'never' : `${t.seconds_ago}s ago`) : 'not seen');
    updateClass(freshCell, tone);
    updateText(row.querySelector('[data-role="type"]'), t.type);
    const last = t.last_value == null ? '-' : String(t.last_value);
    const lastValueEl = row.querySelector('[data-role="lastValue"]');
    updateText(lastValueEl, last);
    lastValueEl.title = last;
  }
  for (const [topic, row] of table.rows.entries()) {
    if (!seen.has(topic)) {
      row.remove();
      table.rows.delete(topic);
    }
  }
}

function renderRosStatus(report) {
  if (!report) return;
  latestRosReport = report;
  const { summaryEl, pubTable, subTable } = ensureRosElements();
  const drones = report.drones || {};
  const droneNames = Object.keys(drones);
  const droneCount = report.droneCount ?? droneNames.length;
  const reachableCount = droneNames.filter((name) => drones[name].phoneReachable).length;
  const totalTopics = droneNames.reduce((sum, name) => sum + (drones[name].topicCount || 0), 0);

  updateText(summaryEl.querySelector('[data-role="timestamp"]'), report.generatedAt || '');
  const pillEl = summaryEl.querySelector('[data-role="statusPill"]');
  updateText(pillEl, droneCount > 0 ? `${droneCount} drone${droneCount === 1 ? '' : 's'} bridged` : 'no drones bridged');
  updateClass(pillEl, `pill ${droneCount > 0 ? 'good' : 'warn'}`);
  updateDetailList(summaryEl.querySelector('[data-role="metrics"]'), [
    ['droneCount', 'Drones bridged', droneCount],
    ['phoneReachable', 'Phones reachable', `${reachableCount} / ${droneCount}`],
    ['topicsTracked', 'Topics tracked (total)', totalTopics],
  ]);

  updateRosDroneSelect(pubTable, 'published', droneNames);
  updateRosDroneSelect(subTable, 'subscribed', droneNames);

  const pubDrone = drones[rosSelectedDrone.published];
  const subDrone = drones[rosSelectedDrone.subscribed];
  const pubEntries = pubDrone ? Object.entries(pubDrone.topics).filter(([t]) => t.startsWith('fmu/out/')) : [];
  const subEntries = subDrone ? Object.entries(subDrone.topics).filter(([t]) => t.startsWith('fmu/in/')) : [];
  updateRosTopicTable(pubTable, pubEntries);
  updateRosTopicTable(subTable, subEntries);
  updateRosDronePhonePill(pubTable, pubDrone);
  updateRosDronePhonePill(subTable, subDrone);
}

async function loadRosStatus() {
  try {
    const resp = await fetch('/api/ros-status');
    if (!resp.ok) return;
    const report = await resp.json();
    if (report && report.generatedAt) renderRosStatus(report);
  } catch (err) {
    /* dashboard not ready or monitor offline; keep previous view */
  }
}
setInterval(loadRosStatus, 3000);
loadRosStatus();

// ---- Theme toggle (System / Light / Dark segmented control) ----
const themeToggle = document.querySelector('#themeToggle');

function applyTheme(theme) {
  const light = theme === 'light'
    || (theme !== 'dark' && window.matchMedia('(prefers-color-scheme: light)').matches);
  document.documentElement.classList.toggle('light', light);
}

function initTheme() {
  const saved = localStorage.getItem('wb-theme') || 'system';
  themeToggle.querySelectorAll('[data-theme]').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.theme === saved);
  });
  applyTheme(saved);
  themeToggle.addEventListener('click', (event) => {
    const btn = event.target.closest('[data-theme]');
    if (!btn) return;
    localStorage.setItem('wb-theme', btn.dataset.theme);
    themeToggle.querySelectorAll('[data-theme]').forEach((b) => b.classList.toggle('active', b === btn));
    applyTheme(btn.dataset.theme);
  });
  window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
    if ((localStorage.getItem('wb-theme') || 'system') === 'system') applyTheme('system');
  });
}
initTheme();

function renderVideoTiles(state) {
  for (const drone of state.drones) {
    let player = players.get(drone.name);
    if (!player) {
      const tile = tileTemplate.content.firstElementChild.cloneNode(true);
      tile.dataset.drone = drone.name;
      tile.tabIndex = 0;
      tile.setAttribute('role', 'button');
      tile.setAttribute('aria-label', `Open details for ${drone.name}`);
      updateText(tile.querySelector('h2'), drone.name);
      tile.querySelector('.ignoreBtn').addEventListener('click', (event) => {
        event.stopPropagation();
        setDroneIgnored(drone.name, !getDrone(drone.name)?.ignored);
      });
      tile.addEventListener('click', () => openDroneModal(drone.name));
      tile.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          openDroneModal(drone.name);
        }
      });
      // One-shot camera test controls (photo/temp/thermal), proxied through the server to the
      // phone's HTTP surface. Per tile, because the video wall is where a pilot looks.
      const tileBody = tile.querySelector('.tileBody');
      const captureRow = document.createElement('div');
      captureRow.className = 'tileCaptureRow';
      const captureButtons = document.createElement('span');
      captureButtons.className = 'captureButtons';
      const captureStatus = document.createElement('span');
      captureStatus.className = 'captureStatus';
      captureStatus.setAttribute('data-role', 'captureStatus');
      for (const [action, label, tip] of [
        ['photo', 'Photo', 'Trip one shutter on the payload'],
        ['temperature', 'Temp', 'Read the thermal spot temperature'],
        ['thermal', 'Thermal', 'Trip one shutter on the thermal payload'],
      ]) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'settingsApply captureBtn';
        btn.textContent = label;
        btn.dataset.action = action;
        btn.dataset.tip = tip;
        btn.title = tip;
        btn.addEventListener('click', (event) => {
          event.stopPropagation();
          captureOnDrone(drone.name, action, btn, captureStatus);
        });
        captureButtons.appendChild(btn);
      }
      captureRow.append(captureButtons, captureStatus);
      tileBody.appendChild(captureRow);

      grid.appendChild(tile);
      player = new WhepPlayer(drone, tile);
      players.set(drone.name, player);
    }
    player.drone = drone;
    updateCaptureButtons(drone, player.tile);
    if (drone.ip && !droneCapabilities.has(drone.name)) refreshDroneCapabilities(drone, player.tile);
    player.tile.classList.toggle('ignored', !!drone.ignored);
    updateText(player.tile.querySelector('.ip'), drone.ip || 'no ip');
    const ignoreButton = player.tile.querySelector('.ignoreBtn');
    updateText(ignoreButton, drone.ignored ? 'Use' : 'Ignore');
    ignoreButton.setAttribute('aria-pressed', String(!!drone.ignored));
    updateTelemetryStats(player.tile, drone);

    if (drone.ignored) {
      player.close();
      player.setStatus('ignored', 'status-warn');
    } else if (drone.mediaMtx?.ready && !player.isConnecting && player.pc?.connectionState !== 'connected') {
      player.connect();
    } else if (!drone.mediaMtx?.ready && player.pc?.connectionState !== 'connected') {
      const lostMessage = cameraLostMessage(drone);
      if (lostMessage) {
        player.setStatus('camera feed lost', 'status-bad');
        updateText(player.events, lostMessage);
      } else {
        player.setStatus('awaiting relay…', drone.telemetryConnected ? 'status-warn' : 'status-bad');
      }
    }
  }
}

async function captureOnDrone(name, action, btn, status) {
  if (btn.disabled) return;
  btn.disabled = true;
  status.textContent = 'sending\u2026';
  status.classList.remove('ok', 'err');
  try {
    const resp = await fetch(`/api/drones/${encodeURIComponent(name)}/capture/${action}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
    });
    const data = await resp.json();
    const ok = resp.ok && data.ok;
    status.textContent = ok ? (data.message || 'ok') : (data.error || `HTTP ${resp.status}`);
    status.classList.add(ok ? 'ok' : 'err');
  } catch (err) {
    status.textContent = `Error: ${err.message}`;
    status.classList.add('err');
  } finally {
    const current = getDrone(name);
    if (current) updateCaptureButtons(current, btn.closest('.tile'));
  }
}

// Capture buttons are gated on what the drone actually has: Photo needs a reachable phone
// (every payload has a camera), Temp/Thermal additionally need a thermal lens, which the
// phone reports in /config. Before that config arrives the thermal buttons stay disabled.
function updateCaptureButtons(drone, tile) {
  if (!tile) return;
  const cap = droneCapabilities.get(drone.name);
  tile.querySelectorAll('.captureBtn').forEach((btn) => {
    const action = btn.dataset.action;
    const needsThermal = action === 'temperature' || action === 'thermal';
    if (needsThermal) {
      const has = !!(cap && cap.hasThermal);
      btn.disabled = !(drone.ip && has);
      btn.title = has ? (btn.dataset.tip || '') : (cap ? 'No thermal payload detected' : 'Checking payload…');
    } else {
      btn.disabled = !drone.ip;
      btn.title = btn.dataset.tip || '';
    }
  });
}

async function refreshDroneCapabilities(drone, tile) {
  try {
    const resp = await fetch(`/api/drones/${encodeURIComponent(drone.name)}/config`);
    const data = await resp.json();
    if (resp.ok && data.ok && data.config) {
      droneCapabilities.set(drone.name, { hasThermal: !!data.config.hasThermal });
      const current = getDrone(drone.name) || drone;
      updateCaptureButtons(current, tile);
    }
  } catch {
    // Capability stays unknown; the thermal buttons remain disabled until a later attempt.
  }
}

function renderHealthPanels(state) {
  for (const drone of state.drones) {
    let card = healthCards.get(drone.name);
    if (!card) {
      card = createHealthCard(drone.name);
      healthCards.set(drone.name, card);
      healthGrid.appendChild(card.element);
    }
    updateHealthCard(card, drone);
  }
  for (const [name, card] of healthCards.entries()) {
    if (!state.drones.some((drone) => drone.name === name)) {
      card.element.remove();
      healthCards.delete(name);
      healthTrendState.delete(name);
    }
  }
}

function createHealthCard(name) {
  const element = document.createElement('article');
  element.className = 'healthCard';
  element.tabIndex = 0;
  element.setAttribute('role', 'button');
  element.setAttribute('aria-label', `Open details for ${name}`);
  element.innerHTML = `<div class="healthHeader"><div><h3>${escapeHtml(name)}</h3><p data-role="subtitle">Health waiting</p></div><div class="telemetryPills" data-role="pills"></div></div><div class="healthSummary" data-role="health"></div><dl class="healthMetrics" data-role="metrics"></dl>`;
  element.addEventListener('click', () => openDroneModal(name));
  element.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      openDroneModal(name);
    }
  });
  return { element, subtitle: element.querySelector('[data-role="subtitle"]'), pills: element.querySelector('[data-role="pills"]'), health: element.querySelector('[data-role="health"]'), metrics: element.querySelector('[data-role="metrics"]') };
}

function updateHealthCard(card, drone) {
  const telemetry = drone.lastTelemetry || {};
  const phone = telemetry.phoneLocation || {};
  const sender = telemetry.webRtc || {};
  const streamMode = telemetry.streaming?.mode?.toUpperCase() || 'WEBRTC';
  const senderLabel = streamMode === 'WEBRTC' ? 'Sender FPS' : `${streamMode} Source FPS`;
  const processingLabel = streamMode === 'WEBRTC' ? 'Sender Processing' : `${streamMode} Source Processing`;
  const media = drone.mediaMtx || {};
  const browser = drone.browserStats || {};
  const recovery = browser.sdpRecovery || emptySdpRecovery();
  const trend = updateHealthTrend(drone);
  const health = buildDroneHealth(drone, trend);
  card.element.classList.toggle('ignored', !!drone.ignored);
  updateText(card.subtitle, `${drone.ip || 'no ip'} | ${health.primary}`);
  card.pills.replaceChildren(
    makePill(health.label, health.tone),
    makePill(drone.telemetryConnected ? 'telemetry ok' : 'telemetry down', drone.telemetryConnected ? 'good' : 'bad'),
    makePill(media.ready ? 'stream ready' : 'stream wait', media.ready ? 'good' : 'warn'),
    makePill(browser.connectionState || 'browser idle', browser.connectionState === 'connected' ? 'good' : 'warn'),
    makePill(drone.ignored ? 'ignored' : 'active', drone.ignored ? 'warn' : 'good'),
  );
  renderHealthSummary(card.health, health);
  updateDetailList(card.metrics, [
    ['wifi', 'Wi-Fi RSSI', phone.wifiRssi === undefined ? '-' : `${phone.wifiRssi} dBm`],
    ['phoneBattery', 'Phone Battery', phone.battery === undefined ? '-' : `${phone.battery}%`],
    ['senderFps', senderLabel, sender.outputFps === undefined ? '-' : `${Number(sender.outputFps).toFixed(1)} / ${sender.targetFps || '-'}`],
    ['senderProcessing', processingLabel, sender.averageFrameProcessingMs === undefined ? '-' : `${Number(sender.averageFrameProcessingMs).toFixed(1)} ms/frame`],
    ['browserFps', 'Browser FPS', browser.fps === undefined ? '-' : Number(browser.fps).toFixed(1)],
    ['bitrate', 'Bitrate', browser.bitrateKbps === undefined ? '-' : `${browser.bitrateKbps} kbps`],
    ['loss', 'Loss / Drop', `${browser.packetsLost ?? '-'} / ${browser.framesDropped ?? '-'}`],
    ['recentLoss', 'Recent Loss / Drop', trend.ready ? `${trend.lostDelta} / ${trend.dropDelta}` : '-'],
    ['jitter', 'Jitter', browser.jitter === undefined ? '-' : `${Number(browser.jitter * 1000).toFixed(0)} ms`],
    ['freezes', 'Freezes', browser.freezeCount ?? '-'],
    ['recentFreezes', 'Recent Freezes', trend.ready ? trend.freezeDelta : '-'],
    ['recovery', 'Recovery', recovery.summary || '-'],
  ]);
}

function updateHealthTrend(drone) {
  const browser = drone.browserStats || {};
  const media = drone.mediaMtx || {};
  const now = Date.now();
  const previous = healthTrendState.get(drone.name);
  const hasBrowserStats = !!drone.browserStats;
  const current = {
    mediaErrors: Number(media.inboundFramesInError || 0),
    packetsLost: hasBrowserStats ? Number(browser.packetsLost || 0) : Number(previous?.packetsLost || 0),
    framesDropped: hasBrowserStats ? Number(browser.framesDropped || 0) : Number(previous?.framesDropped || 0),
    freezeCount: hasBrowserStats ? Number(browser.freezeCount || 0) : Number(previous?.freezeCount || 0),
  };
  if (!previous) {
    const initialTrend = { ready: false, seconds: 0, inboundErrorDelta: 0, lostDelta: 0, dropDelta: 0, freezeDelta: 0 };
    healthTrendState.set(drone.name, { ...current, at: now, lastTrend: initialTrend });
    return initialTrend;
  }
  const delta = (key) => Math.max(0, current[key] - Number(previous[key] || 0));
  const trend = {
    ready: true,
    seconds: Math.max((now - previous.at) / 1000, 0.001),
    inboundErrorDelta: delta('mediaErrors'),
    lostDelta: delta('packetsLost'),
    dropDelta: delta('framesDropped'),
    freezeDelta: delta('freezeCount'),
  };
  healthTrendState.set(drone.name, { ...current, at: now, lastTrend: trend });
  return trend;
}

function renderTelemetryPanels(state) {
  for (const drone of state.drones) {
    let card = telemetryCards.get(drone.name);
    if (!card) {
      card = createTelemetryCard(drone.name);
      telemetryCards.set(drone.name, card);
      telemetryGrid.appendChild(card.element);
    }
    updateTelemetryCard(card, drone);
  }
  for (const [name, card] of telemetryCards.entries()) {
    if (!state.drones.some((drone) => drone.name === name)) {
      card.element.remove();
      telemetryCards.delete(name);
    }
  }
}

function createTelemetryCard(name) {
  const element = document.createElement('article');
  element.className = 'telemetryCard';
  element.innerHTML = `<div class="telemetryHeader"><div><h3>${escapeHtml(name)}</h3><p data-role="subtitle">Telemetry waiting</p></div><div class="telemetryPills" data-role="pills"></div></div><div class="telemetryTree" data-role="tree"></div>`;
  return { element, subtitle: element.querySelector('[data-role="subtitle"]'), pills: element.querySelector('[data-role="pills"]'), tree: element.querySelector('[data-role="tree"]'), treeState: new Map() };
}

function updateTelemetryCard(card, drone) {
  const media = drone.mediaMtx || {};
  updateText(card.subtitle, `${drone.ip || 'no ip'} | telemetry ${drone.telemetryConnected ? 'ok' : 'down'} | ${telemetryAgeLabel(drone)}`);
  card.pills.replaceChildren(
    makePill(drone.telemetryConnected ? 'telemetry ok' : 'telemetry down', drone.telemetryConnected ? 'good' : 'bad'),
    makePill(media.ready ? 'stream ready' : 'stream wait', media.ready ? 'good' : 'warn'),
    makePill(drone.browserStats?.connectionState || 'browser idle', drone.browserStats?.connectionState === 'connected' ? 'good' : 'warn'),
    makePill(drone.ignored ? 'ignored' : 'active', drone.ignored ? 'warn' : 'good'),
  );
  rememberTreeState(card);
  card.tree.replaceChildren(renderTreeNode('root', {
    meta: {
      ip: drone.ip,
      discoveredIp: drone.discoveredIp,
      status: drone.status,
      telemetryConnected: drone.telemetryConnected,
      telemetryPackets: drone.telemetryPackets,
      telemetryReconnects: drone.telemetryReconnects,
      lastTelemetryAt: drone.lastTelemetryAt,
      lastDiscoveryAt: drone.lastDiscoveryAt,
      lastError: drone.lastError,
      ignored: drone.ignored,
    },
    telemetry: drone.lastTelemetry || {},
    senderWebRtc: drone.lastTelemetry?.webRtc || {},
    mediaMtx: {
      ready: media.ready,
      readers: media.readers,
      tracks: media.tracks,
      bytesReceived: media.bytesReceived,
      bytesSent: media.bytesSent,
      inboundFramesInError: media.inboundFramesInError,
    },
    browser: drone.browserStats || {},
  }, { open: true, hideLabel: true, path: 'root', state: card.treeState }));
}

function rememberTreeState(card) {
  card.tree.querySelectorAll('details.treeNode[data-tree-path]').forEach((details) => {
    card.treeState.set(details.dataset.treePath, details.open);
  });
}

function renderPublishTargets(state) {
  publishTargets.replaceChildren();
  for (const drone of state.drones) {
    const card = document.createElement('article');
    card.className = 'targetCard';
    const baseUrl = drone.ip ? `http://${drone.ip}:8080` : 'waiting for IP';
    card.innerHTML = `<div class="targetCardHeader"><div><h3>${escapeHtml(drone.name)}</h3><p>${escapeHtml(drone.status || 'unknown')} | telemetry ${drone.telemetryConnected ? 'ok' : 'down'}</p></div><div class="targetPills"></div></div><div class="targetRow"><span class="treeMeta">Command base</span><span class="targetUrl">${escapeHtml(baseUrl)}</span></div><div class="targetRow"><span class="treeMeta">Config</span><span class="targetUrl">${escapeHtml(drone.ip ? `${baseUrl}/config` : 'waiting for IP')}</span></div>`;
    card.querySelector('.targetPills').append(
      makePill(drone.ip ? 'reachable IP' : 'no IP', drone.ip ? 'good' : 'warn'),
      makePill(drone.ignored ? 'ignored' : 'armed for test', drone.ignored ? 'warn' : 'good'),
    );
    publishTargets.appendChild(card);
  }
}

// Endpoints that also have a MAVLink form, from the transport's own tables. Empty until the
// first fetch, which is fine: the catalogue renders without coverage and gains it a moment later.
let mavlinkCoveredEndpoints = new Set();
let mavlinkStreamedFields = new Set();

// Read-only endpoints, and the telemetry field that carries the same answer on MAVLink.
//
// MAVLink streams state rather than answering polls, so these have no command form and never
// will. Marking them simply absent said "MAVLink cannot do this" when the truth is "MAVLink does
// this a different way" -- and for the override latch it was on the wire the whole time.
const READ_ENDPOINT_FIELDS = {
  '/get/isManualOverrideActive': 'isManualOverrideActive',
  '/get/autoSensing/status': 'autoSensingActive',
  '/get/autoSensing/targets': 'detectedTargets',
  '/config': 'ipAddress',
};

async function loadMavlinkCoverage() {
  try {
    const response = await fetch('/api/mavlink-coverage');
    const payload = await response.json();
    if (payload.available) {
      mavlinkCoveredEndpoints = new Set(payload.endpoints);
      mavlinkStreamedFields = new Set(payload.streamed || []);
    }
  } catch {
    // The catalogue is still useful without it; every entry simply shows no coverage chip.
  }
}

// One coverage model for both tabs.
//
// They previously each showed a tile labelled "HTTP only" counting different things — endpoints
// on one, telemetry fields on the other — so the two tabs appeared to contradict each other.
// Every number here carries its unit, and both tabs render the same strip.
const coverage = {
  commands: 0,
  commandsTotal: 0,
  // shared / httpTotal, not mavlinkTotal / httpTotal. Those are different populations: MAVLink
  // reports fields HTTP has no equivalent for, so a ratio between the two totals implies a
  // containment that does not hold — 43 of 47 read as "four missing" when ten were.
  shared: 0,
  httpTotal: 0,
  mavlinkOnly: 0,
  differing: null,
};

function renderCoverageStrips() {
  document.querySelectorAll('.coverageStrip').forEach((strip) => {
    // A fact with nothing behind it yet is worse than no fact: "0/0" reads as a finding.
    const parts = [];
    if (coverage.commandsTotal) {
      parts.push(coverageFact(`${coverage.commands}/${coverage.commandsTotal}`, 'commands on MAVLink'));
    }
    if (coverage.httpTotal) {
      parts.push(coverageFact(
        `${coverage.shared}/${coverage.httpTotal}`,
        'HTTP telemetry fields also on MAVLink',
      ));
    }
    if (coverage.mavlinkOnly) {
      parts.push(coverageFact(`+${coverage.mavlinkOnly}`, 'fields only MAVLink reports'));
    }
    if (coverage.differing !== null) {
      parts.push(coverageFact(
        `${coverage.differing}`,
        coverage.differing === 1 ? 'field disagrees with HTTP' : 'fields disagree with HTTP',
        coverage.differing ? 'warn' : 'good',
      ));
    }
    strip.replaceChildren(...parts);
  });
}

function coverageFact(value, label, tone) {
  const item = document.createElement('div');
  item.className = `coverageFact${tone ? ` ${tone}` : ''}`;
  const strong = document.createElement('strong');
  strong.textContent = value;
  const span = document.createElement('span');
  span.textContent = label;
  item.append(strong, span);
  return item;
}

function renderHttpPublishCatalog() {
  // A table, not thirty cards each wrapping a code block. The catalogue is reference material
  // and reference material is scanned, so density and alignment matter more than decoration.
  const table = document.createElement('table');
  table.className = 'publishTable';
  table.innerHTML =
    '<thead><tr><th>Endpoint</th><th>Body</th><th>What it does</th><th>MAVLink</th></tr></thead>';
  const body = document.createElement('tbody');

  for (const [groupTitle, , commands] of publishCatalog) {
    const groupRow = document.createElement('tr');
    groupRow.className = 'publishGroup';
    const cell = document.createElement('td');
    cell.colSpan = 4;
    cell.textContent = groupTitle.replace(/^HTTP\s+/, '');
    groupRow.append(cell);
    body.append(groupRow);

    for (const [title, method, endpoint, commandBody, commandDescription] of commands) {
      const readField = READ_ENDPOINT_FIELDS[endpoint];
      const state = mavlinkCoveredEndpoints.has(endpoint)
        ? 'yes'
        : readField && mavlinkStreamedFields.has(readField)
          ? 'streamed'
          : 'no';
      const row = document.createElement('tr');
      row.className = 'publishItem';
      row.dataset.coverage = state === 'no' ? 'httpOnly' : 'both';
      row.dataset.search = `${title} ${endpoint} ${commandDescription}`.toLowerCase();

      const path = document.createElement('td');
      path.className = 'publishPath';
      path.innerHTML =
        `<span class="publishMethod">${escapeHtml(method)}</span><code>${escapeHtml(endpoint)}</code>`;

      const bodyCell = document.createElement('td');
      bodyCell.className = 'publishBody';
      bodyCell.textContent = commandBody || '—';

      const what = document.createElement('td');
      what.className = 'publishWhat';
      what.textContent = commandDescription;

      const mav = document.createElement('td');
      mav.className = 'publishCoverage';
      mav.dataset.state = state;
      mav.textContent = state === 'streamed' ? 'streamed' : state;
      if (state === 'streamed') {
        mav.title = `No command needed — ${readField} is on the MAVLink telemetry stream.`;
      }

      row.append(path, bodyCell, what, mav);
      body.append(row);
    }
  }
  table.append(body);
  publishGrid.replaceChildren(table);
  applyPublishFilter();
}

function renderPublishCatalog() {
  if (publishGrid.childElementCount) return;
  renderHttpPublishCatalog();
  loadMavlinkCoverage().then(() => {
    publishGrid.replaceChildren();
    renderHttpPublishCatalog();
  });
}

function applyPublishFilter() {
  const needle = (publishFilter.value || '').trim().toLowerCase();
  const httpOnly = publishHttpOnly.checked;
  publishGrid.querySelectorAll('.publishItem').forEach((row) => {
    const matchesText = !needle || row.dataset.search.includes(needle);
    const matchesCoverage = !httpOnly || row.dataset.coverage === 'httpOnly';
    row.hidden = !(matchesText && matchesCoverage);
  });
  // A group heading with nothing under it is noise.
  publishGrid.querySelectorAll('.publishGroup').forEach((heading) => {
    let sibling = heading.nextElementSibling;
    let visible = false;
    while (sibling && !sibling.classList.contains('publishGroup')) {
      if (!sibling.hidden) visible = true;
      sibling = sibling.nextElementSibling;
    }
    heading.hidden = !visible;
  });

  const items = Array.from(publishGrid.querySelectorAll('.publishItem'));
  coverage.commandsTotal = items.length;
  coverage.commands = items.filter((row) => row.dataset.coverage === 'both').length;
  renderCoverageStrips();
}

function renderHealthSummary(root, health) {
  if (!root) return;
  const issues = health.issues.length ? health.issues : [{ label: 'No active symptom detected', tone: 'good', detail: health.primary }];
  const visibleIssues = issues.slice(0, 3).map((issue) => {
    const item = document.createElement('div');
    item.className = `healthIssue ${issue.tone || 'warn'}`;
    item.innerHTML = `<strong>${escapeHtml(issue.label)}</strong><span>${escapeHtml(issue.detail || '-')}</span>`;
    return item;
  });
  if (issues.length > visibleIssues.length) {
    const more = document.createElement('div');
    more.className = 'healthIssue more';
    more.innerHTML = `<strong>${issues.length - visibleIssues.length} more indicators</strong><span>Open details for the full telemetry and recovery view.</span>`;
    visibleIssues.push(more);
  }
  root.replaceChildren(...visibleIssues);
}

function buildDroneHealth(drone, trend = { ready: false, inboundErrorDelta: 0, lostDelta: 0, dropDelta: 0, freezeDelta: 0 }) {
  const telemetry = drone.lastTelemetry || {};
  const phone = telemetry.phoneLocation || {};
  const sender = telemetry.webRtc || {};
  const media = drone.mediaMtx || {};
  const browser = drone.browserStats || {};
  const recovery = browser.sdpRecovery?.negotiated || {};
  const issues = [];

  const addIssue = (label, tone, detail) => issues.push({ label, tone, detail });
  const wifiRssi = Number(phone.wifiRssi);
  const phoneBattery = Number(phone.battery);
  const senderTargetFps = Number(sender.targetFps || sender.configuredFps || 0);
  const senderOutputFps = Number(sender.outputFps || 0);
  const senderInputFps = Number(sender.inputFps || 0);
  const senderProcessingMs = Number(sender.averageFrameProcessingMs || 0);
  const browserFps = Number(browser.fps || 0);
  const browserJitterMs = Number(browser.jitter || 0) * 1000;
  const browserBitrateKbps = Number(browser.bitrateKbps || 0);
  const framesDecoded = Number(browser.framesDecoded || 0);
  const packetsLost = Number(browser.packetsLost || 0);
  const framesDropped = Number(browser.framesDropped || 0);
  const freezes = Number(browser.freezeCount || 0);
  const inboundErrors = Number(media.inboundFramesInError || 0);
  const recentLoss = Number(trend.lostDelta || 0);
  const recentDrops = Number(trend.dropDelta || 0);
  const recentFreezes = Number(trend.freezeDelta || 0);
  const recentInboundErrors = Number(trend.inboundErrorDelta || 0);

  if (!drone.telemetryConnected) addIssue('Telemetry down', 'bad', 'Phone telemetry socket is not producing samples.');
  if (drone.telemetryConnected && !media.ready && (drone.telemetryPackets || 0) > 20) addIssue('Stream not ready', 'bad', 'Telemetry is alive but MediaMTX has no ready video path.');
  if (Number.isFinite(wifiRssi)) {
    if (wifiRssi <= -78) addIssue('Wi-Fi weak', 'bad', `${wifiRssi} dBm can produce loss and jitter.`);
    else if (wifiRssi <= -67) addIssue('Wi-Fi marginal', 'warn', `${wifiRssi} dBm leaves limited airtime margin.`);
  }
  if (Number.isFinite(phoneBattery) && phoneBattery >= 0) {
    if (phoneBattery <= 15) addIssue('Phone battery low', 'bad', `${phoneBattery}% may trigger thermal or power limits.`);
    else if (phoneBattery <= 30) addIssue('Phone battery watch', 'warn', `${phoneBattery}% remaining.`);
  }
  if (senderTargetFps > 0) {
    const frameBudgetMs = 1000 / senderTargetFps;
    if (senderOutputFps > 0 && senderOutputFps < senderTargetFps * 0.55) addIssue('Sender FPS low', 'bad', `Phone output ${senderOutputFps.toFixed(1)} / ${senderTargetFps} fps.`);
    else if (senderInputFps > 0 && senderOutputFps < senderInputFps * 0.65) addIssue('Sender dropping frames', 'warn', `Input ${senderInputFps.toFixed(1)} fps, output ${senderOutputFps.toFixed(1)} fps.`);
    if (senderProcessingMs >= frameBudgetMs * 0.9) addIssue('Encoder pipeline saturated', 'bad', `${senderProcessingMs.toFixed(1)} ms/frame near ${frameBudgetMs.toFixed(1)} ms budget.`);
    else if (senderProcessingMs >= frameBudgetMs * 0.7) addIssue('Encoder pipeline hot', 'warn', `${senderProcessingMs.toFixed(1)} ms/frame resize/processing.`);
  }
  if (browser.connectionState === 'connected') {
    if (senderOutputFps > 2 && browserFps > 0 && browserFps < senderOutputFps * 0.55) addIssue('Browser decode lag', 'bad', `Browser ${browserFps.toFixed(1)} fps while sender outputs ${senderOutputFps.toFixed(1)} fps.`);
    else if (media.ready && framesDecoded > 0 && browserFps <= 1) addIssue('Browser video stalled', 'bad', `Browser is connected but decoding only ${browserFps.toFixed(1)} fps.`);
    else if (media.ready && browserBitrateKbps > 0 && browserBitrateKbps < 80) addIssue('Browser bitrate low', 'warn', `Receiving ${browserBitrateKbps.toFixed(0)} kbps from a ready H264 stream.`);
    if (browserJitterMs >= 150) addIssue('Browser jitter high', 'bad', `${browserJitterMs.toFixed(0)} ms RTP jitter.`);
    else if (browserJitterMs >= 60) addIssue('Browser jitter elevated', 'warn', `${browserJitterMs.toFixed(0)} ms RTP jitter.`);
    if (recentLoss > 0) addIssue('Packet loss active', recentLoss > 20 ? 'bad' : 'warn', `${recentLoss} new lost packets; ${packetsLost} cumulative.`);
    if (recentDrops > 0) addIssue('Frame drops active', recentDrops > 10 ? 'bad' : 'warn', `${recentDrops} new dropped frames; ${framesDropped} cumulative.`);
    if (recentFreezes > 0) addIssue('Receiver freezes active', recentFreezes > 2 ? 'bad' : 'warn', `${recentFreezes} new freeze events; ${freezes} cumulative.`);
  }
  if (recentInboundErrors > 0) addIssue('MediaMTX frame errors active', recentInboundErrors > 5 ? 'bad' : 'warn', `${recentInboundErrors} new inbound frame errors; ${inboundErrors} cumulative.`);
  if (browser.sdpRecovery) {
    if (!recovery.nack) addIssue('NACK missing', 'warn', 'Negotiated SDP does not advertise generic NACK.');
    if (!recovery.pli && !recovery.fir) addIssue('Keyframe request weak', 'warn', 'Negotiated SDP lacks PLI/FIR feedback.');
    if (!recovery.rtx) addIssue('RTX missing', 'warn', 'Retransmission payloads were not negotiated.');
  }

  const worstTone = issues.some((issue) => issue.tone === 'bad') ? 'bad' : (issues.some((issue) => issue.tone === 'warn') ? 'warn' : 'good');
  const label = worstTone === 'good' ? 'health ok' : (worstTone === 'bad' ? 'health bad' : 'health watch');
  const primary = issues[0]?.detail || 'Telemetry, stream, sender, browser, and recovery indicators look nominal.';
  return { label, tone: worstTone, primary, issues };
}

function cameraLostMessage(drone) {
  if (drone.mediaMtx?.ready || !drone.telemetryConnected || (drone.telemetryPackets || 0) < 20) return '';
  return 'Device may have been idle too long and the camera feed was lost. Power-cycle the drone and let it cool down before retrying.';
}

function setText(root, selector, value) { updateText(root?.querySelector(selector), value); }
function updateText(element, value) {
  if (!element) return;
  const next = String(value ?? '-');
  if (element.textContent !== next) element.textContent = next;
}
function updateClass(element, className) { if (element && element.className !== className) element.className = className; }

function updateSummary(values) {
  for (const [key, label, extraClass] of summaryDefinitions) {
    let item = summary.querySelector(`[data-summary="${key}"]`);
    if (!item) {
      item = document.createElement('div');
      item.className = `summaryItem${extraClass ? ` ${extraClass}` : ''}`;
      item.dataset.summary = key;
      const labelElement = document.createElement('span');
      labelElement.textContent = label;
      const valueElement = document.createElement('strong');
      item.append(labelElement, valueElement);
      summary.appendChild(item);
    }
    updateText(item.querySelector('strong'), values[key]);
  }
}

function updateTelemetryStats(tile, drone) {
  const media = drone.mediaMtx || {};
  setText(tile, '[data-stat="telemetry"]', drone.telemetryConnected ? 'ok' : 'down');
  updateClass(tile.querySelector('[data-stat="telemetry"]'), drone.telemetryConnected ? 'status-good' : 'status-bad');
  setText(tile, '[data-stat="mediaMtx"]', media.ready ? `${media.readers?.length || 0}` : 'wait');
  updateClass(tile.querySelector('[data-stat="mediaMtx"]'), media.ready ? 'status-good' : 'status-warn');
}

function droneColor(name) {
  const index = latestState?.drones.findIndex((drone) => drone.name === name) ?? 0;
  return droneColors[Math.max(0, index) % droneColors.length];
}

function addChartSample(droneName, values) {
  const drone = getDrone(droneName);
  if (drone?.ignored) return;
  const time = Math.floor(Date.now() / 1000);
  let droneHistory = chartHistory.get(droneName);
  if (!droneHistory) {
    droneHistory = {};
    chartHistory.set(droneName, droneHistory);
  }
  for (const [key, rawValue] of Object.entries(values)) {
    if (rawValue === null || rawValue === undefined) continue;
    const value = Number(rawValue);
    if (!Number.isFinite(value)) continue;
    const series = droneHistory[key] || [];
    const last = series[series.length - 1];
    if (last?.time === time) last.value = value;
    else series.push({ time, value });
    const cutoff = time - CHART_HISTORY_SECONDS;
    while (series.length && series[0].time < cutoff) series.shift();
    droneHistory[key] = series;
  }
}

function updateTelemetryChartSamples(state) {
  for (const drone of state.drones) {
    if (drone.ignored) continue;
    const currentPackets = Number(drone.telemetryPackets || 0);
    const telemetry = drone.lastTelemetry || {};
    const speed = telemetry.speed || {};
    const phone = telemetry.phoneLocation || {};
    const previous = telemetryRateState.get(drone.name) || { packets: currentPackets, at: Date.now() };
    const now = Date.now();
    const seconds = Math.max((now - previous.at) / 1000, 0.001);
    telemetryRateState.set(drone.name, { packets: currentPackets, at: now });
    addChartSample(drone.name, {
      telemetryRate: Math.max(0, (currentPackets - previous.packets) / seconds),
      inboundFramesInError: drone.mediaMtx?.inboundFramesInError || 0,
      batteryLevel: telemetry.batteryLevel,
      satelliteCount: telemetry.satelliteCount,
      altitude: telemetry.location?.altitude,
      distanceToHome: telemetry.distanceToHome,
      speedMagnitude: magnitude(speed.x, speed.y, speed.z),
      phoneBattery: phone.battery,
      wifiRssi: phone.wifiRssi,
    });
  }
}

function createSeries(chart, options) {
  if (chart.addSeries && window.LightweightCharts?.LineSeries) return chart.addSeries(window.LightweightCharts.LineSeries, options);
  return chart.addLineSeries(options);
}

function ensureCharts(groupName) {
  const group = chartGroups[groupName];
  const chartsGrid = group?.grid;
  if (!group || !chartsGrid) return false;
  if (!window.LightweightCharts) {
    chartsGrid.innerHTML = '<div class="emptyState chartsUnavailable">Charts are unavailable because Lightweight Charts did not load. Check network access to the CDN.</div>';
    return false;
  }
  chartsGrid.querySelector('.chartsUnavailable')?.remove();
  for (const definition of group.definitions) {
    const chartKey = `${groupName}:${definition.key}`;
    if (chartInstances.has(chartKey)) continue;
    const card = document.createElement('section');
    card.className = 'chartCard';
    card.innerHTML = `<div class="chartTitle"><h3>${definition.title}</h3><span>${definition.unit}</span></div><div class="chartCanvas"></div>`;
    chartsGrid.appendChild(card);
    const chartIsLight = document.documentElement.classList.contains('light');
    const chart = window.LightweightCharts.createChart(card.querySelector('.chartCanvas'), {
      autoSize: true,
      layout: {
        background: { color: chartIsLight ? '#ffffff' : '#131a24' },
        textColor: chartIsLight ? '#10151d' : '#e8edf3',
      },
      grid: {
        vertLines: { color: chartIsLight ? 'rgba(15,23,42,0.08)' : 'rgba(255,255,255,0.06)' },
        horzLines: { color: chartIsLight ? 'rgba(15,23,42,0.08)' : 'rgba(255,255,255,0.06)' },
      },
      timeScale: {
        timeVisible: true,
        secondsVisible: true,
        tickMarkFormatter: formatChartTime,
        borderColor: chartIsLight ? 'rgba(15,23,42,0.15)' : 'rgba(255,255,255,0.12)',
      },
      localization: { timeFormatter: formatChartTime },
      rightPriceScale: { borderColor: chartIsLight ? 'rgba(15,23,42,0.15)' : 'rgba(255,255,255,0.12)' },
      crosshair: { mode: 1 },
    });
    chartInstances.set(chartKey, { chart, card, series: new Map() });
  }
  return true;
}

function updateCharts(groupName = null) {
  const groups = groupName ? [groupName] : Object.keys(chartGroups).filter((name) => document.querySelector(`[data-panel="${name}"]`)?.classList.contains('active'));
  if (!groups.length || !latestState) return;
  for (const activeGroup of groups) updateChartGroup(activeGroup);
}

function updateChartGroup(groupName) {
  const group = chartGroups[groupName];
  if (!group || !ensureCharts(groupName)) return;
  const now = Math.floor(Date.now() / 1000);
  const from = now - chartRangeSeconds;
  const activeDrones = latestState.drones.filter((drone) => !drone.ignored);
  for (const definition of group.definitions) {
    const instance = chartInstances.get(`${groupName}:${definition.key}`);
    if (!instance) continue;
    for (const drone of activeDrones) {
      let series = instance.series.get(drone.name);
      if (!series) {
        series = createSeries(instance.chart, { title: drone.name, color: droneColor(drone.name), lineWidth: 2, lastValueVisible: true, priceLineVisible: false });
        instance.series.set(drone.name, series);
      }
      const data = (chartHistory.get(drone.name)?.[definition.key] || [])
        .filter((point) => point.time >= from);
      series.setData(data);
    }
    for (const [name, series] of instance.series.entries()) {
      if (!activeDrones.some((drone) => drone.name === name)) {
        instance.chart.removeSeries(series);
        instance.series.delete(name);
      }
    }
    try { instance.chart.timeScale().setVisibleRange({ from, to: now }); } catch (_) {}
  }
}

async function setDroneIgnored(name, ignored) {
  const player = players.get(name);
  if (ignored) player?.close();
  await fetch(`/api/drones/${encodeURIComponent(name)}/ignore`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ignored }),
  }).catch(() => {});
}

function getDrone(name) { return latestState?.drones.find((drone) => drone.name === name) || null; }

function updateDetailList(root, rows) {
  if (!root) return;
  for (const [key, label, value, className = ''] of rows) {
    let row = root.querySelector(`[data-detail="${key}"]`);
    if (!row) {
      row = document.createElement('div');
      row.dataset.detail = key;
      const term = document.createElement('dt');
      term.textContent = label;
      const description = document.createElement('dd');
      row.append(term, description);
      root.appendChild(row);
    }
    const description = row.querySelector('dd');
    updateText(description, value);
    updateClass(description, className);
  }
}

function openModalDetails() { droneModal.querySelectorAll('details').forEach((details) => { details.open = true; }); }

function restoreModalVideo() {
  if (!modalMountedPlayer || !modalReturnAnchor) return;
  modalMountedPlayer.tile.insertBefore(modalMountedPlayer.videoWrap, modalReturnAnchor);
  modalReturnAnchor.remove();
  modalMountedPlayer = null;
  modalReturnAnchor = null;
}

function mountPlayerVideo(player) {
  if (modalMountedPlayer === player) return;
  restoreModalVideo();
  const videoWrap = player.tile.querySelector('.videoWrap');
  if (!videoWrap) return;
  modalReturnAnchor = document.createComment('video-return-anchor');
  player.tile.insertBefore(modalReturnAnchor, videoWrap);
  player.videoWrap = videoWrap;
  modalVideoHost.replaceChildren(videoWrap);
  modalMountedPlayer = player;
}

function updateModalForDrone(name) {
  const drone = getDrone(name);
  if (!drone) return;
  const telemetry = drone.lastTelemetry || {};
  const sender = telemetry.webRtc || {};
  const streamMode = telemetry.streaming?.mode?.toUpperCase() || 'WEBRTC';
  const senderLabel = streamMode === 'WEBRTC' ? 'Sender FPS' : `${streamMode} Source FPS`;
  const processingLabel = streamMode === 'WEBRTC' ? 'Sender Processing' : `${streamMode} Source Processing`;
  const media = drone.mediaMtx || {};
  const browserStats = modalMountedPlayer?.latestStats || drone.browserStats || {};
  const recovery = browserStats.sdpRecovery || emptySdpRecovery();
  const negotiatedRecovery = recovery.negotiated || {};
  const health = buildDroneHealth(drone, healthTrendState.get(drone.name)?.lastTrend);
  const lostMessage = cameraLostMessage(drone);
  const speed = telemetry.speed || {};
  updateText(modalTitle, drone.name);
  updateText(modalSubtitle, `${drone.ip || 'no ip'} | ${drone.ignored ? 'ignored' : 'active'} | telemetry ${drone.telemetryConnected ? 'ok' : 'down'} | stream ${media.ready ? 'ready' : 'waiting'}`);
  updateDetailList(modalTelemetry, [
    ['telemetry', 'Telemetry', drone.telemetryConnected ? `ok (${telemetryAgeLabel(drone)})` : `down (${telemetryAgeLabel(drone)})`, drone.telemetryConnected ? 'status-good' : 'status-bad'],
    ['packets', 'Packets', drone.telemetryPackets],
    ['battery', 'Battery', telemetry.batteryLevel === undefined ? '-' : `${telemetry.batteryLevel}%`],
    ['flightMode', 'Flight Mode', telemetry.flightMode],
    ['location', 'Location', telemetry.location ? `${telemetry.location.latitude}, ${telemetry.location.longitude}, ${telemetry.location.altitude}m` : '-'],
    ['speed', 'Speed', Number.isFinite(magnitude(speed.x, speed.y, speed.z)) ? `${magnitude(speed.x, speed.y, speed.z).toFixed(2)} m/s` : '-'],
    ['phoneBattery', 'Phone Battery', telemetry.phoneLocation?.battery === undefined ? '-' : `${telemetry.phoneLocation.battery}%`],
    ['wifiRssi', 'Wi-Fi RSSI', telemetry.phoneLocation?.wifiRssi],
    ['senderFps', senderLabel, sender.outputFps === undefined ? '-' : `${Number(sender.outputFps).toFixed(1)} / ${sender.targetFps || '-'}`],
    ['senderProcessing', processingLabel, sender.averageFrameProcessingMs === undefined ? '-' : `${Number(sender.averageFrameProcessingMs).toFixed(1)} ms/frame`],
    ['lastError', 'Last Error', drone.lastError || '-'],
  ]);
  updateDetailList(modalStats, [
    ['health', 'Health', health.label, `status-${health.tone}`],
    ['healthPrimary', 'Primary Symptom', health.issues[0]?.label || 'No active symptom'],
    ['mediamtx', 'MediaMTX', media.ready ? `ready (${media.readers?.length || 0} readers)` : 'waiting', media.ready ? 'status-good' : 'status-warn'],
    ['operatorMessage', 'Operator Message', lostMessage || '-'],
    ['codec', 'Codec', media.tracks?.join(', ') || '-'],
    ['inboundErrors', 'Inbound Errors', media.inboundFramesInError ?? '-'],
    ['fps', 'FPS', browserStats.fps === undefined ? '-' : Number(browserStats.fps).toFixed(1)],
    ['bitrate', 'Receive Bitrate', browserStats.bitrateKbps === undefined ? '-' : `${browserStats.bitrateKbps} kbps`],
    ['decodedFrames', 'Decoded Frames', browserStats.framesDecoded],
    ['droppedFrames', 'Dropped Frames', browserStats.framesDropped],
    ['packetsLost', 'Packets Lost', browserStats.packetsLost],
    ['jitter', 'Jitter', browserStats.jitter],
    ['receiverPolicy', 'Receiver Policy', browserStats.receiverBufferPolicy || '-'],
    ['jitterTarget', 'Jitter Target', browserStats.jitterBufferSupported ? `${browserStats.jitterBufferTargetMs} ms` : 'not supported'],
    ['jitterDelay', 'Jitter Delay', browserStats.jitterBufferDelayMs === null || browserStats.jitterBufferDelayMs === undefined ? '-' : `${browserStats.jitterBufferDelayMs} ms/frame`],
    ['freezes', 'Freezes', browserStats.freezeCount],
    ['connectionLosses', 'Connection Losses', browserStats.connectionLosses],
    ['stalls', 'Stalls', browserStats.stalls],
    ['decodeErrors', 'Decode Errors', browserStats.decodeErrors],
  ]);
  updateDetailList(modalRecovery, [
    ['sdpHealth', 'SDP Recovery', recovery.summary || '-'],
    ['nack', 'NACK Negotiated', negotiatedRecovery.nack ? 'yes' : 'no', negotiatedRecovery.nack ? 'status-good' : 'status-warn'],
    ['pli', 'PLI Negotiated', negotiatedRecovery.pli ? 'yes' : 'no', negotiatedRecovery.pli ? 'status-good' : 'status-warn'],
    ['fir', 'FIR Negotiated', negotiatedRecovery.fir ? 'yes' : 'no', negotiatedRecovery.fir ? 'status-good' : 'status-warn'],
    ['rtx', 'RTX Negotiated', negotiatedRecovery.rtx ? 'yes' : 'no', negotiatedRecovery.rtx ? 'status-good' : 'status-warn'],
    ['h264Profile', 'H264 Profile', negotiatedRecovery.h264Profiles?.join(', ') || browserStats.codecFmtpLine || '-'],
    ['packetization', 'Packetization', negotiatedRecovery.h264PacketizationModes?.join(', ') || '-'],
    ['nackCount', 'NACK Count', browserStats.nackCount ?? '-'],
    ['pliCount', 'PLI Count', browserStats.pliCount ?? '-'],
    ['firCount', 'FIR Count', browserStats.firCount ?? '-'],
    ['keyFrames', 'Keyframes Decoded', browserStats.keyFramesDecoded ?? '-'],
    ['codecStats', 'Browser Codec', browserStats.codecMimeType ? `${browserStats.codecMimeType} pt=${browserStats.codecPayloadType ?? '-'}` : '-'],
  ]);
  if (drone.ignored) modalMountedPlayer?.setStatus('ignored', 'status-warn');
  else if (lostMessage) {
    modalMountedPlayer?.setStatus('camera feed lost', 'status-bad');
    modalEvent.textContent = lostMessage;
  } else if (!media.ready && modalMountedPlayer?.pc?.connectionState !== 'connected') {
    modalMountedPlayer?.setStatus(drone.telemetryConnected ? 'waiting for stream' : 'waiting for telemetry', drone.telemetryConnected ? 'status-warn' : 'status-bad');
  }

  if (modalStreamingSelect) {
    modalStreamingSelect.value = telemetry.streaming?.mode?.toLowerCase() || 'webrtc';
  }
  if (modalStreamingPath) {
    modalStreamingPath.textContent = getConsumptionPath(drone);
  }
}

function getConsumptionPath(drone) {
  const telemetry = drone.lastTelemetry || {};
  const mode = telemetry.streaming?.mode?.toLowerCase() || 'webrtc';
  const droneIp = drone.ip || 'PHONE_IP';
  const rawPath = telemetry.streaming?.consumptionPath;
  let info = '';

  switch (mode) {
    case 'webrtc':
      info = `WHIP Ingest (Phone) → MediaMTX relay WHEP (Browser): ${getRelayWhepUrl(drone.streamName)}`;
      break;
    case 'rtsp': {
      const rtspPort = telemetry.streaming?.rtspPort || 8554;
      const rtspUser = telemetry.streaming?.rtspUser || 'admin';
      const rtspPwd  = telemetry.streaming?.rtspPwd  || 'lyrebird';
      info = `RTSP Server (Phone): rtsp://${rtspUser}:${rtspPwd}@${droneIp}:${rtspPort}/streaming/live/1 → MediaMTX pulling & bridging to WHEP`;
      break;
    }
    case 'rtmp':
      info = `RTMP Publisher (Phone): rtmp://${location.hostname}:1935/${encodeURIComponent(drone.streamName)} → MediaMTX receiving & bridging to WHEP`;
      break;
    default:
      info = `Unknown streaming protocol: ${mode}`;
      break;
  }

  if (rawPath) {
    info += `\n(Raw Telemetry Path: ${rawPath})`;
  }
  return info;
}

function openDroneModal(name) {
  const drone = getDrone(name);
  const player = players.get(name);
  if (!drone || !player) return;
  selectedDroneName = name;
  mountPlayerVideo(player);
  openModalDetails();
  updateModalForDrone(name);
  if (!droneModal.open) droneModal.showModal();
}

function closeDroneModal() {
  selectedDroneName = null;
  restoreModalVideo();
  if (droneModal.open) droneModal.close();
}

async function loadState() { const response = await fetch('/api/drones'); render(await response.json()); }
function reconnectAll() {
  for (const player of players.values()) {
    if (player.drone.ignored) continue;
    if (player.drone.mediaMtx?.ready) player.connect();
  }
}

function renderTreeNode(label, value, options = {}) {
  const { open = false, hideLabel = false, path = label, state = null } = options;
  if (Array.isArray(value) || (value && typeof value === 'object')) {
    const entries = Array.isArray(value) ? value.map((child, index) => [String(index), child]) : Object.entries(value).filter(([, child]) => child !== undefined);
    const details = document.createElement('details');
    details.className = 'treeNode';
    details.dataset.treePath = path;
    details.open = state?.has(path) ? !!state.get(path) : open;
    details.addEventListener('toggle', () => state?.set(path, details.open));
    const summary = document.createElement('summary');
    summary.innerHTML = `${hideLabel ? '' : `<span class="treeKey">${escapeHtml(label)}</span>`}<span class="treeMeta">${Array.isArray(value) ? 'array' : 'object'}(${entries.length})</span>`;
    details.appendChild(summary);
    const children = document.createElement('div');
    children.className = 'treeChildren';
    entries.forEach(([childKey, childValue], index) => children.appendChild(renderTreeNode(childKey, childValue, { open: open && index < 3, path: `${path}.${childKey}`, state })));
    details.appendChild(children);
    return details;
  }
  const leaf = document.createElement('div');
  leaf.className = 'treeLeaf';
  const key = document.createElement('span');
  key.className = 'treeKey';
  key.textContent = label;
  const displayValue = formatTreeValue(value);
  const text = document.createElement('span');
  text.className = `treeValue ${displayValue.type}`;
  text.textContent = displayValue.text;
  leaf.append(key, text);
  return leaf;
}

function formatTreeValue(value) {
  if (value === null) return { text: 'null', type: 'null' };
  if (typeof value === 'boolean') return { text: value ? 'true' : 'false', type: 'boolean' };
  if (typeof value === 'number') return { text: Number.isFinite(value) ? String(value) : '-', type: 'number' };
  if (typeof value === 'string') return { text: value || '-', type: 'string' };
  return { text: String(value), type: 'string' };
}

function makePill(label, tone = '') {
  const element = document.createElement('span');
  element.className = `pill${tone ? ` ${tone}` : ''}`;
  element.textContent = label;
  return element;
}

function telemetryAgeLabel(drone) {
  return drone.lastTelemetryAt ? `${Math.max(0, Math.round((Date.now() - Date.parse(drone.lastTelemetryAt)) / 1000))}s ago` : 'no sample yet';
}

function magnitude(x, y, z) {
  const values = [Number(x), Number(y), Number(z)];
  if (values.some((value) => !Number.isFinite(value))) return NaN;
  return Math.sqrt(values.reduce((sum, value) => sum + value * value, 0));
}

function msOrNull(seconds) {
  const value = Number(seconds);
  return Number.isFinite(value) ? Number((value * 1000).toFixed(1)) : null;
}

function escapeHtml(value) {
  return String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

document.querySelector('#discoverBtn').addEventListener('click', () => { fetch('/api/discover', { method: 'POST' }).catch(() => {}); });
document.querySelector('#reconnectBtn').addEventListener('click', reconnectAll);
modalCloseBtn.addEventListener('click', closeDroneModal);

if (modalStreamingSelect) {
  modalStreamingSelect.addEventListener('change', async (event) => {
    const selectedMode = event.target.value;
    if (!selectedDroneName) return;
    
    modalStreamingSelect.disabled = true;
    modalStreamingPath.textContent = 'Switching streaming protocol... Phone is restarting streams...';
    
    try {
      const response = await fetch(`/api/drones/${encodeURIComponent(selectedDroneName)}/streaming/mode`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: selectedMode })
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || 'Failed to switch protocol');
      }
      console.log('Remote protocol switch successful:', result);
      setTimeout(loadState, 1500);
    } catch (error) {
      alert(`Failed to switch streaming protocol: ${error.message}`);
      const drone = getDrone(selectedDroneName);
      if (drone) {
        modalStreamingSelect.value = drone.lastTelemetry?.streaming?.mode?.toLowerCase() || 'webrtc';
        modalStreamingPath.textContent = getConsumptionPath(drone);
      }
    } finally {
      modalStreamingSelect.disabled = false;
    }
  });
}
droneModal.addEventListener('close', closeDroneModal);
droneModal.addEventListener('click', (event) => { if (event.target === droneModal) closeDroneModal(); });

// ---- Guide modal ----
const guideModal = document.querySelector('#guideModal');
document.querySelector('#guideBtn').addEventListener('click', () => { if (!guideModal.open) guideModal.showModal(); });
document.querySelector('#guideCloseBtn').addEventListener('click', () => guideModal.close());
guideModal.addEventListener('click', (event) => { if (event.target === guideModal) guideModal.close(); });

// ---- MAVLink vs HTTP comparison -------------------------------------------------------------
// The instrument that found every MAVLink defect in this project. Both wires are read against one
// drone and shown field by field, because the failures here are frames that decode cleanly and
// mean the wrong thing -- which no amount of reading either side alone will reveal.

const mavlinkTarget = document.querySelector('#mavlinkTarget');
const mavlinkDiffOnly = document.querySelector('#mavlinkDiffOnly');
const mavlinkRefresh = document.querySelector('#mavlinkRefresh');
const mavlinkCompare = document.querySelector('#mavlinkCompare');
let mavlinkTimer = null;

const MAVLINK_STATUS_LABELS = {
  agree: 'matches HTTP',
  differ: 'differs from HTTP',
  // Two different things were both called "not on MAVLink": a field the protocol has no form
  // for, and one it carries perfectly well that simply has not arrived — home position before a
  // home point exists, flight time before the battery reports one. Calling both an absence made
  // the interface look less complete than it is.
  notYet: 'not reported yet',
  httpOnly: 'no MAVLink form',
  mavlinkOnly: 'MAVLink only',
};

function mavlinkRowState(row) {
  if (row.status !== 'httpOnly') return row.status;
  return mavlinkStreamedFields.has(row.key) ? 'notYet' : 'httpOnly';
}

function mavlinkDroneNames() {
  // latestState, not state: `state` is a parameter of render(), so reading it here found nothing
  // and the picker stayed empty. Only drones with a known address can be compared, because the
  // MAVLink side binds a socket filtered to that aircraft.
  return ((latestState && latestState.drones) || [])
    .filter((drone) => drone.ip)
    .map((drone) => drone.name)
    .sort();
}

function refreshMavlinkTargets() {
  const names = mavlinkDroneNames();
  const chosen = mavlinkTarget.value;
  const unchanged =
    names.length === mavlinkTarget.options.length &&
    names.every((name, index) => mavlinkTarget.options[index].value === name);
  // Rebuilding the list on every state push would reset the operator's choice twice a second.
  if (unchanged) return;
  mavlinkTarget.replaceChildren(
    ...names.map((name) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      return option;
    }),
  );
  if (names.includes(chosen)) {
    mavlinkTarget.value = chosen;
  } else if (names.length) {
    // Nothing chosen yet, or the chosen drone went away: pick the first so the panel shows
    // something the moment a drone exists, rather than an empty table beside a full picker.
    mavlinkTarget.value = names[0];
    if (mavlinkTimer !== null) loadMavlinkComparison();
  }
}

async function loadMavlinkComparison() {
  const name = mavlinkTarget.value;
  if (!name) {
    mavlinkCompare.replaceChildren(mavlinkEmptyState('Pick a drone to compare the two transports.'));
      return;
  }
  try {
    const response = await fetch(`/api/drones/${encodeURIComponent(name)}/mavlink`);
    const payload = await response.json();
    if (!response.ok) {
      mavlinkCompare.replaceChildren(mavlinkEmptyState(payload.error || 'Comparison unavailable.'));
          return;
    }
    renderMavlinkComparison(payload);
  } catch (error) {
    mavlinkCompare.replaceChildren(mavlinkEmptyState(`Comparison failed: ${error.message}`));
  }
}

function mavlinkEmptyState(text) {
  const note = document.createElement('p');
  note.className = 'emptyState';
  note.textContent = text;
  return note;
}

function renderMavlinkComparison(payload) {
  const rows = payload.rows || [];
  const compare = mavlinkDiffOnly.checked;
  const reported = rows.filter((row) => row.status !== 'httpOnly');

  // Capability, not what happens to have arrived: a field MAVLink carries is carried whether or
  // not the aircraft has reported one yet, and a ratio that moved as the drone warmed up would
  // be describing the weather rather than the interface.
  coverage.shared = rows.filter((row) => mavlinkRowState(row) !== 'httpOnly').length
    - rows.filter((row) => row.status === 'mavlinkOnly').length;
  coverage.httpTotal = payload.httpKeys;
  coverage.mavlinkOnly = rows.filter((row) => row.status === 'mavlinkOnly').length;
  coverage.differing = rows.filter((row) => row.status === 'differ').length;
  renderCoverageStrips();

  const shown = compare ? rows : reported;
  if (!shown.length) {
    mavlinkCompare.replaceChildren(mavlinkEmptyState('Waiting for telemetry.'));
    return;
  }

  const table = document.createElement('table');
  table.className = 'mavlinkTable';
  const head = document.createElement('thead');
  head.innerHTML = compare
    ? '<tr><th>Field</th><th>MAVLink</th><th>HTTP</th><th></th></tr>'
    : '<tr><th>Field</th><th>Value</th></tr>';
  const body = document.createElement('tbody');
  shown.forEach((row) => {
    const state = mavlinkRowState(row);
    const tr = document.createElement('tr');
    tr.className = `mavlinkRow ${state}`;
    const key = document.createElement('td');
    key.className = 'mavlinkKey';
    key.textContent = row.key;
    const mav = document.createElement('td');
    mav.textContent = row.mavlink;
    tr.append(key, mav);
    if (compare) {
      const http = document.createElement('td');
      http.textContent = row.http;
      const status = document.createElement('td');
      status.className = 'mavlinkVerdict';
      status.textContent = MAVLINK_STATUS_LABELS[state] || state;
      status.dataset.state = state;
      tr.append(http, status);
    }
    body.append(tr);
  });
  table.append(head, body);
  mavlinkCompare.replaceChildren(table);
}

function startMavlinkPolling() {
  stopMavlinkPolling();
  loadMavlinkComparison();
  // Two seconds: fast enough to watch a value move while tilting a gimbal, slow enough that each
  // sample is a fresh read of both wires rather than a queue of overlapping requests.
  mavlinkTimer = setInterval(loadMavlinkComparison, 2000);
}

function stopMavlinkPolling() {
  if (mavlinkTimer !== null) clearInterval(mavlinkTimer);
  mavlinkTimer = null;
}

publishFilter.addEventListener('input', applyPublishFilter);
publishHttpOnly.addEventListener('change', applyPublishFilter);

mavlinkTarget.addEventListener('change', loadMavlinkComparison);
mavlinkDiffOnly.addEventListener('change', loadMavlinkComparison);
mavlinkRefresh.addEventListener('click', loadMavlinkComparison);

// ---- Tab activation (persisted in the URL hash so a refresh/back-button keeps the view) ----
const validTabs = new Set(Array.from(document.querySelectorAll('.tabButton'), (button) => button.dataset.tab));

function activateTab(tab, { pushHistory = true } = {}) {
  if (!validTabs.has(tab)) return;
  document.querySelectorAll('.tabButton').forEach((item) => item.classList.toggle('active', item.dataset.tab === tab));
  document.querySelectorAll('.tabPanel').forEach((panel) => panel.classList.toggle('active', panel.dataset.panel === tab));
  if (pushHistory && location.hash.slice(1) !== tab) history.replaceState(null, '', `#${tab}`);
  if (tab === 'videoCharts' || tab === 'telemetryCharts') {
    updateCharts(tab);
    setTimeout(() => updateCharts(tab), 50);
  }
  // Only sample while the panel is visible: each request opens a live read of both wires, and
  // leaving that running behind a hidden tab holds a socket on the aircraft's telemetry port.
  if (tab === 'mavlink') {
    refreshMavlinkTargets();
    startMavlinkPolling();
  } else {
    stopMavlinkPolling();
  }
}

document.querySelectorAll('.tabButton').forEach((button) => {
  button.addEventListener('click', () => activateTab(button.dataset.tab));
});
window.addEventListener('hashchange', () => activateTab(location.hash.slice(1), { pushHistory: false }));
activateTab(validTabs.has(location.hash.slice(1)) ? location.hash.slice(1) : 'video', { pushHistory: false });

const events = new EventSource('/api/events');
events.onmessage = (event) => {
  const message = JSON.parse(event.data);
  if (message.type === 'state') render(message.payload);
};

loadState();
setInterval(() => { if (latestState) render(latestState); }, 2000);