import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";
import "./map.css";

if (!import.meta.env.DEV) {
  window.addEventListener("contextmenu", event => event.preventDefault());
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
