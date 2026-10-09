/*
 * Injected at document start into every instance webview (see
 * InstanceWebViewActivity). Android's WebView has no getDisplayMedia, and the
 * Harmony client cannot be modified, so this provides one: it asks native
 * (MediaProjection) for frames, draws them to a canvas, and hands back the
 * canvas-captured video track. The client calls getDisplayMedia and replaceTrack
 * exactly as it would in a browser.
 */
(() => {
  "use strict";

  const channel = window.HarmonyScreen;
  if (!channel || !navigator.mediaDevices || window.__harmonyScreenShim) return;
  window.__harmonyScreenShim = true;

  let active = null;
  let watchdog = null;

  channel.addEventListener("message", (event) => {
    const data = event.data;
    if (typeof data === "string") onControl(data);
    else onFrame(data);
  });

  function onControl(raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      return;
    }
    if (message.type === "frame" && typeof message.data === "string") {
      onFrame(base64ToBuffer(message.data));
      return;
    }
    const state = active;
    if (!state) return;
    if (message.type === "started") {
      if (message.width && message.height) {
        state.canvas.width = message.width;
        state.canvas.height = message.height;
      }
      state.resolve();
    } else if (message.type === "denied") {
      finish(state, true);
    } else if (message.type === "stopped") {
      finish(state, false);
    }
  }

  async function onFrame(buffer) {
    const state = active;
    if (!state) return;
    let bitmap;
    try {
      bitmap = await createImageBitmap(new Blob([buffer], { type: "image/jpeg" }));
    } catch {
      return;
    }
    if (active !== state) {
      bitmap.close();
      return;
    }
    try {
      state.ctx.drawImage(bitmap, 0, 0, state.canvas.width, state.canvas.height);
    } finally {
      bitmap.close();
    }
  }

  function finish(state, denied) {
    if (active !== state) return;
    active = null;
    if (watchdog !== null) {
      clearInterval(watchdog);
      watchdog = null;
    }
    for (const track of state.stream.getTracks()) track.stop();
    if (denied) {
      state.reject(new DOMException("Screen sharing was not allowed", "NotAllowedError"));
    } else {
      // Mirrors the browser's own Stop sharing button.
      state.track.dispatchEvent(new Event("ended"));
    }
  }

  function base64ToBuffer(encoded) {
    const binary = atob(encoded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  // Report the instance's own background color so the system bars can match it.
  // The client writes it to <meta name="theme-color"> (see its lib/theme.ts).
  let lastTheme = null;
  function reportTheme() {
    const meta = document.querySelector('meta[name="theme-color"]');
    const color = meta && meta.getAttribute("content");
    if (!color || color === lastTheme) return;
    lastTheme = color;
    channel.postMessage(JSON.stringify({ type: "theme", background: color }));
  }
  try {
    reportTheme();
    window.addEventListener("load", () => setTimeout(reportTheme, 200));
    setTimeout(reportTheme, 1000);
    setTimeout(reportTheme, 3000);
    new MutationObserver(reportTheme).observe(document.documentElement, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ["content"],
    });
  } catch {
    // Theming the bars is best-effort.
  }

  navigator.mediaDevices.getDisplayMedia = async (constraints) => {
    const maxHeight = constraints?.video?.height?.max ?? 720;
    const maxFrameRate = constraints?.video?.frameRate?.max ?? 30;

    const canvas = document.createElement("canvas");
    canvas.width = Math.max(2, Math.round((maxHeight * 16) / 9));
    canvas.height = Math.max(2, maxHeight);
    const ctx = canvas.getContext("2d", { alpha: false });
    ctx.fillStyle = "#000000";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    const stream = canvas.captureStream(maxFrameRate);
    const track = stream.getVideoTracks()[0];
    if (!track) throw new DOMException("No screen track", "NotAllowedError");

    let resolve;
    let reject;
    const ready = new Promise((res, rej) => {
      resolve = res;
      reject = rej;
    });
    const state = { canvas, ctx, stream, track, resolve, reject };
    active = state;

    channel.postMessage(JSON.stringify({ type: "start", height: maxHeight, frameRate: maxFrameRate }));

    // The client stops the track itself when the member stops sharing; when it
    // does, stop native capture too (stop() fires no event).
    watchdog = setInterval(() => {
      if (active === state && track.readyState === "ended") {
        clearInterval(watchdog);
        watchdog = null;
        active = null;
        channel.postMessage(JSON.stringify({ type: "stop" }));
      }
    }, 500);

    await ready;
    return stream;
  };
})();
