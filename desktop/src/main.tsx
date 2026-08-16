import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";
import "./map.css";

const APP_VERSION = "0.2.1-alpha.1";

if (!import.meta.env.DEV) {
  window.addEventListener("contextmenu", event => event.preventDefault());
}

const root = document.getElementById("root")!;
createRoot(root).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

// App.tsx still comes directly from the known-good 0.2.0-alpha.1 UI baseline.
// Keep the displayed version aligned with the package/Tauri metadata without
// otherwise touching that layout while this build is under manual testing.
const syncVersionLabels = () => {
  root.querySelectorAll("small").forEach(element => {
    if (element.textContent === "v0.2.0-alpha.1") element.textContent = `v${APP_VERSION}`;
    if (element.textContent === "0.2.0-alpha.1 + map/urltest") element.textContent = APP_VERSION;
  });
};

syncVersionLabels();
new MutationObserver(syncVersionLabels).observe(root, { childList: true, subtree: true });
