(function () {
  "use strict";

  var entries = [];
  var animationEntries = [];
  var panel;
  var details;
  var status;
  var stageLabel;
  var completedStages = 0;
  var stageMessages = {
    bootstrap: "Loading OpenUI5…",
    component: "Preparing warehouse data…",
    controller: "Building control panel…",
    viewport: "Starting 3D engine…",
    application: "Warehouse ready"
  };

  function text(value) {
    if (value instanceof Error) {
      return value.stack || value.message;
    }
    if (typeof value === "string") {
      return value;
    }
    try {
      return JSON.stringify(value);
    } catch (_error) {
      return String(value);
    }
  }

  function render() {
    if (!details) {
      return;
    }
    details.textContent = entries.map(function (entry) {
      return entry.time + " [" + entry.level + "] " + entry.stage + " — " + entry.message;
    }).join("\n");
  }

  function log(level, stage, value) {
    var entry = {
      time: new Date().toISOString().slice(11, 23),
      level: level,
      stage: stage,
      message: text(value)
    };
    entries.push(entry);
    if (level === "INFO" && stageMessages[stage] && stageLabel) {
      stageLabel.textContent = stageMessages[stage];
      completedStages = Math.min(4, completedStages + 1);
      document.querySelectorAll(".startupSteps i").forEach(function (step, index) {
        step.classList.toggle("active", index < completedStages);
      });
    }
    render();
    var method = level === "ERROR" ? "error" : level === "WARN" ? "warn" : "info";
    console[method]("[Warehouse:" + stage + "]", value);
  }

  function showFailure(value, stage) {
    log("ERROR", stage || "runtime", value);
    queue("ERROR", stage || "runtime", text(value), value);
    flush();
    if (status) {
      status.className = "startupError";
      status.textContent = "Warehouse Visualizer could not start: " + text(value);
    }
    if (panel) {
      panel.open = true;
    }
  }

  // --- shipping to the backend -------------------------------------------------
  // The browser sees things the server cannot (a pallet missing from a shelf) and the
  // server sees things the browser cannot (a vehicle holding a stale task). Correlating
  // them used to mean reading this panel and a container log side by side. Everything
  // queued here is already in the buffers above; this only forwards it.
  var pending = [];
  var correlationId = (function () {
    try {
      var random = window.crypto && window.crypto.randomUUID
        ? window.crypto.randomUUID()
        : String(Date.now()) + "-" + Math.random().toString(16).slice(2);
      return "web-" + random;
    } catch (_error) {
      return "web-unknown";
    }
  }());

  // Scene events that repeat on a timer. They earn their place in the in-page buffer,
  // where the question is "is the render keeping up with the target", but shipping a
  // record every second would make the shared log mostly pose noise and bury the
  // handovers and decisions it exists to show.
  var localOnly = { POSE_TARGET: true, CAMERA_PANNED: true };

  function queue(level, event, message, payload) {
    // showFailure runs from a window error handler that can fire during bootstrap,
    // before the var below has been assigned. A logging path that throws inside the
    // error handler would hide the very failure it was called to report.
    if (!pending) { pending = []; }
    if (localOnly[event] && level === "INFO") { return; }
    var source = payload || {};
    pending.push({
      level: level,
      event: event,
      message: message,
      correlationId: correlationId,
      orderId: source.orderId || source.transportOrderId || null,
      taskId: source.taskId || null,
      // Deliberately not source.id: scene payloads use `id` for racks, aisles and
      // conveyors too, and a vehicleId field that sometimes holds a rack id is worse
      // than an absent one -- people will query on it.
      vehicleId: source.vehicleId || source.agvId || null,
      loadId: source.loadId || null
    });
    // Bounded so a page that errors in a render loop cannot grow this without limit
    // before the next flush; the oldest go first, matching the buffers above.
    if (pending.length > 200) { pending.shift(); }
  }

  function flush() {
    if (pending.length === 0) { return; }
    var batch = pending.splice(0, pending.length);
    try {
      // keepalive so a flush triggered by a page unload still leaves the browser.
      window.fetch("/api/v1/client-logs", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Correlation-ID": correlationId },
        body: JSON.stringify({ entries: batch }),
        keepalive: true
      })["catch"](function () {
        // Never surface a logging failure to the operator: the app works without it,
        // and an error banner about logs would be worse than the missing logs.
      });
    } catch (_error) { /* offline demo, or fetch unavailable */ }
  }

  window.setInterval(flush, 10000);
  window.addEventListener("beforeunload", flush);

  window.__warehouseDiagnostics = {
    entries: entries,
    correlationId: correlationId,
    flush: flush,
    info: function (stage, value) { log("INFO", stage, value); queue("INFO", stage, text(value), value); },
    warn: function (stage, value) { log("WARN", stage, value); queue("WARN", stage, text(value), value); },
    error: function (stage, value) { showFailure(value, stage); queue("ERROR", stage, text(value), value); }
  };
  window.__warehouseAnimationTelemetry = animationEntries;
  window.__warehouseRecordAnimation = function (event, payload) {
    var entry = {
      time: new Date().toISOString(),
      elapsedMs: Math.round(performance.now()),
      event: event,
      payload: payload || {}
    };
    animationEntries.push(entry);
    if (animationEntries.length > 500) { animationEntries.shift(); }
    log("INFO", "animation", entry);
    queue("INFO", event, "scene event", entry.payload);
  };

  window.addEventListener("error", function (event) {
    showFailure(event.error || event.message, "window.error");
  });
  window.addEventListener("unhandledrejection", function (event) {
    showFailure(event.reason, "unhandledrejection");
  });

  document.addEventListener("DOMContentLoaded", function () {
    status = document.getElementById("startup-status");
    stageLabel = document.getElementById("startup-stage");
    panel = document.createElement("details");
    panel.id = "diagnostic-log";
    panel.innerHTML = "<summary>Diagnostics (<span id=\"diagnostic-count\">0</span>)</summary><pre></pre>";
    details = panel.querySelector("pre");
    document.body.appendChild(panel);
    var originalRender = render;
    render = function () {
      originalRender();
      var count = document.getElementById("diagnostic-count");
      if (count) { count.textContent = String(entries.length); }
    };
    render();
    log("INFO", "bootstrap", "DOM ready; Babylon global: " + Boolean(window.WarehouseBabylon));
  });

  window.warehouseVisualizerReady = function () {
    log("INFO", "application", "Main view rendered");
    if (status) {
      status.remove();
      status = null;
    }
  };

  log("INFO", "diagnostics", "Logger initialized");
}());
