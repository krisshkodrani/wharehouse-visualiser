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
    if (status) {
      status.className = "startupError";
      status.textContent = "Warehouse Visualizer could not start: " + text(value);
    }
    if (panel) {
      panel.open = true;
    }
  }

  window.__warehouseDiagnostics = {
    entries: entries,
    info: function (stage, value) { log("INFO", stage, value); },
    warn: function (stage, value) { log("WARN", stage, value); },
    error: function (stage, value) { showFailure(value, stage); }
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
