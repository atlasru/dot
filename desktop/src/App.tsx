import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { AppPreferences, AppTheme, EngineSnapshot, GroupView, SelectionView, TrafficSnapshot } from "./types";

type Page = "home" | "nodes" | "settings";
const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, message: null };
const ZERO_TRAFFIC: TrafficSnapshot = { download_bytes_per_second: 0, upload_bytes_per_second: 0, session_download_bytes: 0, session_upload_bytes: 0, connected_seconds: 0 };
const DEFAULT_PREFS: AppPreferences = { theme: "amoled", close_to_tray: true };

export default function App() {
  const [page, setPage] = useState<Page>("home");
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [selection, setSelection] = useState<SelectionView>({ group_id: null, node_id: null });
  const [browseGroupId, setBrowseGroupId] = useState<string | null>(null);
  const [vpn, setVpn] = useState<EngineSnapshot>(OFFLINE);
  const [traffic, setTraffic] = useState<TrafficSnapshot>(ZERO_TRAFFIC);
  const [prefs, setPrefs] = useState<AppPreferences>(DEFAULT_PREFS);
  const [name, setName] = useState("vpn1");
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const activeGroup = useMemo(() => groups.find(g => g.id === selection.group_id) ?? groups[0], [groups, selection.group_id]);
  const activeNode = useMemo(() => activeGroup?.nodes.find(n => n.id === selection.node_id) ?? activeGroup?.nodes[0], [activeGroup, selection.node_id]);
  const browseGroup = useMemo(() => groups.find(g => g.id === browseGroupId) ?? activeGroup ?? groups[0], [groups, browseGroupId, activeGroup]);

  async function reloadData() {
    const [nextGroups, nextSelection, nextPrefs] = await Promise.all([
      invoke<GroupView[]>("list_groups"), invoke<SelectionView>("selection"), invoke<AppPreferences>("preferences"),
    ]);
    setGroups(nextGroups); setSelection(nextSelection); setPrefs(nextPrefs);
  }

  useEffect(() => {
    reloadData().catch(e => setError(String(e)));
    const tick = () => Promise.all([
      invoke<EngineSnapshot>("vpn_status").then(setVpn), invoke<TrafficSnapshot>("traffic_status").then(setTraffic),
    ]).catch(() => undefined);
    tick();
    const timer = window.setInterval(tick, 1000);
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") setPage("home"); };
    window.addEventListener("keydown", escape);
    return () => { window.clearInterval(timer); window.removeEventListener("keydown", escape); };
  }, []);

  useEffect(() => { document.documentElement.dataset.theme = prefs.theme; }, [prefs.theme]);

  function openNodes() { setBrowseGroupId(activeGroup?.id ?? groups[0]?.id ?? null); setPage("nodes"); }

  async function chooseNode(groupId: string, nodeId: string) {
    if (busy) return;
    setBusy(true); setError("");
    try {
      const switching = vpn.phase === "connected" || vpn.phase === "starting" || vpn.phase === "stopping";
      if (switching) {
        setVpn({ phase: "stopping", node_name: vpn.node_name, message: "switching node" });
        const nextVpn = await invoke<EngineSnapshot>("switch_node", { groupId, nodeId });
        setSelection({ group_id: groupId, node_id: nodeId });
        setVpn(nextVpn);
      } else {
        setSelection(await invoke<SelectionView>("select_node", { groupId, nodeId }));
      }
      setPage("home");
    } catch (e) {
      setError(String(e));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
      await reloadData().catch(() => undefined);
    } finally { setBusy(false); }
  }

  async function toggleVpn() {
    if (vpn.phase === "stopping") return;
    setError("");
    if (vpn.phase === "connected" || vpn.phase === "starting") { setVpn(await invoke<EngineSnapshot>("disconnect")); return; }
    if (!activeGroup || !activeNode) { setError("select a node first"); return; }
    setVpn({ phase: "starting", node_name: activeNode.name, message: "starting" });
    try { setVpn(await invoke<EngineSnapshot>("connect", { groupId: activeGroup.id, nodeId: activeNode.id })); }
    catch (e) { setError(String(e)); invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined); }
  }

  async function refresh(group = activeGroup) {
    if (!group) return;
    setBusy(true); setError("");
    try { await invoke("refresh_subscription", { groupId: group.id }); await reloadData(); }
    catch (e) { setError(String(e)); }
    finally { setBusy(false); }
  }

  async function addSubscription(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { await invoke("add_subscription", { name, url }); setUrl(""); await reloadData(); }
    catch (e) { setError(String(e)); }
    finally { setBusy(false); }
  }

  async function changeTheme(theme: AppTheme) { try { setPrefs(await invoke<AppPreferences>("set_theme", { theme })); } catch (e) { setError(String(e)); } }
  async function changeCloseToTray(enabled: boolean) { try { setPrefs(await invoke<AppPreferences>("set_close_to_tray", { enabled })); } catch (e) { setError(String(e)); } }

  const button = vpn.phase === "stopping" ? "STOPPING" : (vpn.phase === "connected" || vpn.phase === "starting") ? "DISCONNECT" : "CONNECT";

  return <main className="shell">
    {page === "home" && <>
      <header className="top"><h1>dot.</h1><button className="icon" onClick={() => setPage("settings")} aria-label="settings">⚙</button></header>
      <section className="hero">
        <PixelShield active={vpn.phase === "connected"} pending={vpn.phase === "starting"} />
        <strong className="status">{vpn.phase}</strong>
        <small className="message">{vpn.message ?? "VLESS · Windows TUN"}</small>
        <button className="node-card" onClick={openNodes}><span>{vpn.node_name ?? activeNode?.name ?? "select node"}</span><small>{activeGroup?.name ?? "no subscription"} · {activeNode ? `${activeNode.security} / ${activeNode.transport}` : "nodes"}</small><b>›</b></button>
        <button className="connect" disabled={vpn.phase === "stopping" || (!activeNode && vpn.phase === "offline")} onClick={toggleVpn}>{button}</button>
      </section>
      <section className="traffic"><TrafficCell label="download" value={formatRate(traffic.download_bytes_per_second)} total={formatBytes(traffic.session_download_bytes)} /><TrafficCell label="upload" value={formatRate(traffic.upload_bytes_per_second)} total={formatBytes(traffic.session_upload_bytes)} /><TrafficCell label="session" value={formatTime(traffic.connected_seconds)} /></section>
    </>}

    {page === "nodes" && <>
      <header className="top"><div><h1>nodes.</h1><small>{browseGroup?.nodes.length ?? 0} available</small></div><button className="icon" onClick={() => setPage("home")}>×</button></header>
      <div className="group-tabs">{groups.map(g => <button key={g.id} className={g.id === browseGroup?.id ? "selected" : ""} onClick={() => setBrowseGroupId(g.id)}>{g.name}</button>)}</div>
      <section className="nodes">{browseGroup?.nodes.map(node => <button disabled={busy} key={node.id} className={node.id === activeNode?.id && browseGroup.id === activeGroup?.id ? "node selected" : "node"} onClick={() => chooseNode(browseGroup.id, node.id)}><span>{node.name}</span><small>{node.security} · {node.transport}<br />{node.host}:{node.port}</small></button>)}</section>
      {browseGroup && <button className="quiet" disabled={busy} onClick={() => refresh(browseGroup)}>refresh subscription</button>}
    </>}

    {page === "settings" && <>
      <header className="top"><h1>settings.</h1><button className="icon" onClick={() => setPage("home")}>×</button></header>
      <SettingsSection title="appearance"><div className="theme-row">{(["amoled", "graphite", "matrix"] as AppTheme[]).map(theme => <button key={theme} className={prefs.theme === theme ? "choice selected" : "choice"} onClick={() => changeTheme(theme)}>{theme}</button>)}</div></SettingsSection>
      <SettingsSection title="window"><Toggle label="close to tray" detail="keep VPN running when the window is closed" checked={prefs.close_to_tray} onChange={changeCloseToTray} /></SettingsSection>
      <SettingsSection title="subscriptions">
        {groups.map(g => <div className="subscription" key={g.id}><div><strong>{g.name}</strong><small>{g.nodes.length} nodes</small></div><button className="tiny" disabled={busy} onClick={() => refresh(g)}>↻</button></div>)}
        <form onSubmit={addSubscription}><label>group<input value={name} onChange={e => setName(e.target.value)} placeholder="vpn1" /></label><label>subscription url<input value={url} onChange={e => setUrl(e.target.value)} type="url" placeholder="https://…" /></label><button className="quiet" disabled={busy || !name.trim() || !url.trim()} type="submit">add subscription</button></form>
      </SettingsSection>
      <SettingsSection title="about"><div className="about"><span>dot. Desktop</span><small>0.1.0-alpha.3</small><span>Xray Core</span><small>v26.7.28</small><span>protocol</span><small>VLESS / REALITY</small></div></SettingsSection>
    </>}
    {error && <div className="error" onClick={() => setError("")}>{error}</div>}
  </main>;
}

function PixelShield({ active, pending }: { active: boolean; pending: boolean }) { return <div className={`pixel-shield ${active ? "active" : ""} ${pending ? "pending" : ""}`} aria-hidden="true">{Array.from({ length: 25 }, (_, i) => <i key={i} />)}</div>; }
function TrafficCell({ label, value, total }: { label: string; value: string; total?: string }) { return <div><small>{label}</small><strong>{value}</strong>{total && <em>{total}</em>}</div>; }
function SettingsSection({ title, children }: { title: string; children: React.ReactNode }) { return <section className="panel"><div className="panel-title">{title}.</div>{children}</section>; }
function Toggle({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (v: boolean) => void }) { return <button className="toggle" onClick={() => onChange(!checked)}><div><strong>{label}</strong><small>{detail}</small></div><span className={checked ? "switch on" : "switch"}><i /></span></button>; }
function formatRate(v: number) { return `${formatBytes(v)}/s`; }
function formatBytes(v: number) { if (v >= 1024 ** 3) return `${(v / 1024 ** 3).toFixed(2)} GB`; if (v >= 1024 ** 2) return `${(v / 1024 ** 2).toFixed(1)} MB`; if (v >= 1024) return `${(v / 1024).toFixed(0)} KB`; return `${v} B`; }
function formatTime(v: number) { const h = Math.floor(v / 3600); const m = Math.floor((v % 3600) / 60); const s = v % 60; return [h, m, s].map(x => String(x).padStart(2, "0")).join(":"); }
