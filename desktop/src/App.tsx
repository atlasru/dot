import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { AppPreferences, AppTheme, EngineSnapshot, GroupView, NodeView, SelectionView, TrafficSnapshot, UrlTestResult } from "./types";

type View = "connection" | "settings";
const VERSION = "0.1.0-alpha.3";
const XRAY_VERSION = "v26.7.28";
const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, message: null };
const ZERO_TRAFFIC: TrafficSnapshot = { download_bytes_per_second: 0, upload_bytes_per_second: 0, session_download_bytes: 0, session_upload_bytes: 0, connected_seconds: 0 };
const DEFAULT_PREFS: AppPreferences = { theme: "amoled", close_to_tray: true };

export default function App() {
  const [view, setView] = useState<View>("connection");
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
  const [latencies, setLatencies] = useState<Record<string, number>>({});
  const [testingNodeId, setTestingNodeId] = useState<string | null>(null);
  const [urlTestError, setUrlTestError] = useState("");

  const activeGroup = useMemo(() => groups.find(g => g.id === selection.group_id) ?? groups[0], [groups, selection.group_id]);
  const activeNode = useMemo(() => activeGroup?.nodes.find(n => n.id === selection.node_id) ?? activeGroup?.nodes[0], [activeGroup, selection.node_id]);
  const browseGroup = useMemo(() => groups.find(g => g.id === browseGroupId) ?? activeGroup ?? groups[0], [groups, browseGroupId, activeGroup]);

  async function reloadData() {
    const [nextGroups, nextSelection, nextPrefs] = await Promise.all([
      invoke<GroupView[]>("list_groups"),
      invoke<SelectionView>("selection"),
      invoke<AppPreferences>("preferences"),
    ]);
    setGroups(nextGroups);
    setSelection(nextSelection);
    setPrefs(nextPrefs);
    setBrowseGroupId(current => {
      if (current && nextGroups.some(group => group.id === current)) return current;
      return nextSelection.group_id ?? nextGroups[0]?.id ?? null;
    });
  }

  useEffect(() => {
    reloadData().catch(e => setError(String(e)));
    const tick = () => Promise.all([
      invoke<EngineSnapshot>("vpn_status").then(setVpn),
      invoke<TrafficSnapshot>("traffic_status").then(setTraffic),
    ]).catch(() => undefined);
    tick();
    const timer = window.setInterval(tick, 1000);
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") setView("connection"); };
    window.addEventListener("keydown", escape);
    return () => { window.clearInterval(timer); window.removeEventListener("keydown", escape); };
  }, []);

  useEffect(() => { document.documentElement.dataset.theme = prefs.theme; }, [prefs.theme]);
  useEffect(() => { setUrlTestError(""); }, [selection.node_id]);

  async function chooseNode(groupId: string, nodeId: string) {
    if (busy || vpn.phase === "stopping") return;
    setBusy(true);
    setError("");
    setUrlTestError("");
    try {
      const switching = vpn.phase === "connected" || vpn.phase === "starting";
      if (switching) {
        setVpn({ phase: "stopping", node_name: vpn.node_name, message: "switching node" });
        const nextVpn = await invoke<EngineSnapshot>("switch_node", { groupId, nodeId });
        setSelection({ group_id: groupId, node_id: nodeId });
        setVpn(nextVpn);
      } else {
        setSelection(await invoke<SelectionView>("select_node", { groupId, nodeId }));
      }
      setBrowseGroupId(groupId);
      setView("connection");
    } catch (e) {
      setError(String(e));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
      await reloadData().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  async function toggleVpn() {
    if (vpn.phase === "stopping") return;
    setError("");
    setUrlTestError("");
    if (vpn.phase === "connected" || vpn.phase === "starting") {
      setVpn(await invoke<EngineSnapshot>("disconnect"));
      return;
    }
    if (!activeGroup || !activeNode) {
      setError("select a node first");
      return;
    }
    setVpn({ phase: "starting", node_name: activeNode.name, message: "starting" });
    try {
      setVpn(await invoke<EngineSnapshot>("connect", { groupId: activeGroup.id, nodeId: activeNode.id }));
    } catch (e) {
      setError(String(e));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    }
  }

  async function runUrlTest(group = activeGroup, node = activeNode) {
    if (!group || !node || testingNodeId || vpn.phase === "starting" || vpn.phase === "stopping") return;
    setTestingNodeId(node.id);
    setUrlTestError("");
    setError("");
    try {
      const result = await invoke<UrlTestResult>("url_test", { groupId: group.id, nodeId: node.id });
      setLatencies(current => ({ ...current, [result.node_id]: result.latency_ms }));
    } catch (e) {
      const message = String(e);
      setUrlTestError(message);
      setError(message);
    } finally {
      setTestingNodeId(null);
    }
  }

  async function refresh(group = browseGroup) {
    if (!group) return;
    setBusy(true);
    setError("");
    try {
      await invoke("refresh_subscription", { groupId: group.id });
      setLatencies({});
      await reloadData();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function addSubscription(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await invoke("add_subscription", { name, url });
      setUrl("");
      await reloadData();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function changeTheme(theme: AppTheme) {
    try { setPrefs(await invoke<AppPreferences>("set_theme", { theme })); }
    catch (e) { setError(String(e)); }
  }

  async function changeCloseToTray(enabled: boolean) {
    try { setPrefs(await invoke<AppPreferences>("set_close_to_tray", { enabled })); }
    catch (e) { setError(String(e)); }
  }

  return <main className="desktop-shell">
    <Sidebar
      groups={groups}
      browseGroup={browseGroup}
      activeGroup={activeGroup}
      activeNode={activeNode}
      vpn={vpn}
      latencies={latencies}
      testingNodeId={testingNodeId}
      busy={busy}
      view={view}
      onBrowseGroup={setBrowseGroupId}
      onChooseNode={chooseNode}
      onRefresh={() => refresh()}
      onSettings={() => setView(view === "settings" ? "connection" : "settings")}
    />

    <section className="content-pane">
      {view === "connection" ? <ConnectionPanel
        group={activeGroup}
        node={activeNode}
        vpn={vpn}
        traffic={traffic}
        latency={activeNode ? latencies[activeNode.id] : undefined}
        testing={!!activeNode && testingNodeId === activeNode.id}
        testFailed={!!urlTestError}
        onToggleVpn={toggleVpn}
        onUrlTest={() => runUrlTest()}
      /> : <SettingsPanel
        groups={groups}
        prefs={prefs}
        busy={busy}
        name={name}
        url={url}
        onName={setName}
        onUrl={setUrl}
        onTheme={changeTheme}
        onCloseToTray={changeCloseToTray}
        onRefresh={refresh}
        onAddSubscription={addSubscription}
        onBack={() => setView("connection")}
      />}
    </section>

    {error && <button className="error-toast" onClick={() => setError("")}>{error}</button>}
  </main>;
}

function Sidebar({
  groups, browseGroup, activeGroup, activeNode, vpn, latencies, testingNodeId, busy, view,
  onBrowseGroup, onChooseNode, onRefresh, onSettings,
}: {
  groups: GroupView[];
  browseGroup?: GroupView;
  activeGroup?: GroupView;
  activeNode?: NodeView;
  vpn: EngineSnapshot;
  latencies: Record<string, number>;
  testingNodeId: string | null;
  busy: boolean;
  view: View;
  onBrowseGroup: (id: string) => void;
  onChooseNode: (groupId: string, nodeId: string) => void;
  onRefresh: () => void;
  onSettings: () => void;
}) {
  return <aside className="sidebar">
    <div className="sidebar-head">
      <Wordmark compact />
      <span>{VERSION}</span>
    </div>

    <div className="group-picker">
      <label>GROUP</label>
      <div className="select-shell">
        <select value={browseGroup?.id ?? ""} onChange={event => onBrowseGroup(event.target.value)} disabled={!groups.length}>
          {!groups.length && <option value="">NO SUBSCRIPTIONS</option>}
          {groups.map(group => <option key={group.id} value={group.id}>{group.name} · {group.nodes.length}</option>)}
        </select>
        <b>⌄</b>
      </div>
    </div>

    <div className="sidebar-rule" />

    <div className="node-list">
      {!browseGroup && <div className="empty-list"><strong>NO NODES</strong><span>ADD A SUBSCRIPTION IN SETTINGS.</span></div>}
      {browseGroup?.nodes.map(node => {
        const selected = activeGroup?.id === browseGroup.id && activeNode?.id === node.id;
        const running = vpn.phase === "connected" && vpn.node_name === node.name;
        return <button
          key={node.id}
          className={`node-row ${selected ? "selected" : ""}`}
          disabled={busy || vpn.phase === "stopping"}
          onClick={() => onChooseNode(browseGroup.id, node.id)}
        >
          <i className="node-marker" />
          <span className="node-copy">
            <span className="node-title"><strong>{node.name}</strong>{running && <em>LIVE</em>}</span>
            <small>{node.security} · {node.transport} · {node.host}:{node.port}</small>
          </span>
          <span className="node-latency">{testingNodeId === node.id ? "··" : latencies[node.id] ? `${latencies[node.id]} ms` : "—"}</span>
        </button>;
      })}
    </div>

    <div className="sidebar-footer">
      <button className="sidebar-action" disabled={!browseGroup || busy} onClick={onRefresh}><span>↻</span><strong>REFRESH</strong></button>
      <button className={`sidebar-action ${view === "settings" ? "active" : ""}`} onClick={onSettings}><span>⚙</span><strong>SETTINGS</strong></button>
    </div>
  </aside>;
}

function ConnectionPanel({ group, node, vpn, traffic, latency, testing, testFailed, onToggleVpn, onUrlTest }: {
  group?: GroupView;
  node?: NodeView;
  vpn: EngineSnapshot;
  traffic: TrafficSnapshot;
  latency?: number;
  testing: boolean;
  testFailed: boolean;
  onToggleVpn: () => void;
  onUrlTest: () => void;
}) {
  const connected = vpn.phase === "connected";
  const pending = vpn.phase === "starting" || vpn.phase === "stopping";
  const button = vpn.phase === "stopping" ? "STOPPING" : connected || vpn.phase === "starting" ? "DISCONNECT" : "CONNECT";
  const testResult = testing ? "TESTING…" : testFailed ? "FAILED" : latency ? `${latency} ms` : "READY";
  const displayNode = vpn.node_name ?? node?.name ?? "SELECT A NODE";

  return <div className="connection-view">
    <div className="view-kicker">CONNECTION</div>
    <div className="connection-center">
      <Wordmark hero connected={connected} pending={pending} error={vpn.phase === "error"} />
      <div className={`phase ${vpn.phase}`}>{vpn.phase.toUpperCase()}</div>
      <div className="active-node-name">{displayNode}</div>
      <div className="active-node-meta">{group?.name ?? "NO SUBSCRIPTION"}{node ? ` · ${node.security} · ${node.transport}` : ""}</div>

      <div className="connection-actions">
        <button className="connect-button" disabled={vpn.phase === "stopping" || (!node && vpn.phase === "offline")} onClick={onToggleVpn}>{button}</button>
        <button className={`url-test-button ${testFailed ? "failed" : ""}`} disabled={!node || pending || testing} onClick={onUrlTest}>
          <span>URL TEST</span><strong>{testResult}</strong>
        </button>
      </div>

      <div className="traffic-grid">
        <TrafficCell label="DOWNLOAD" value={`↓ ${formatRate(traffic.download_bytes_per_second)}`} total={formatBytes(traffic.session_download_bytes)} />
        <TrafficCell label="UPLOAD" value={`↑ ${formatRate(traffic.upload_bytes_per_second)}`} total={formatBytes(traffic.session_upload_bytes)} />
        <TrafficCell label="SESSION" value={formatTime(traffic.connected_seconds)} />
      </div>

      <div className="engine-message">{vpn.message ?? "VLESS · WINDOWS TUN"}</div>
    </div>
  </div>;
}

function SettingsPanel({ groups, prefs, busy, name, url, onName, onUrl, onTheme, onCloseToTray, onRefresh, onAddSubscription, onBack }: {
  groups: GroupView[];
  prefs: AppPreferences;
  busy: boolean;
  name: string;
  url: string;
  onName: (value: string) => void;
  onUrl: (value: string) => void;
  onTheme: (theme: AppTheme) => void;
  onCloseToTray: (enabled: boolean) => void;
  onRefresh: (group: GroupView) => void;
  onAddSubscription: (event: FormEvent) => void;
  onBack: () => void;
}) {
  return <div className="settings-view">
    <div className="settings-head"><div><div className="view-kicker">SETTINGS</div><h2>settings.</h2></div><button className="close-view" onClick={onBack}>×</button></div>

    <div className="settings-scroll">
      <SettingsSection title="APPEARANCE">
        <div className="theme-row">{(["amoled", "graphite", "matrix"] as AppTheme[]).map(theme => <button key={theme} className={prefs.theme === theme ? "choice selected" : "choice"} onClick={() => onTheme(theme)}>{theme.toUpperCase()}</button>)}</div>
      </SettingsSection>

      <SettingsSection title="WINDOW">
        <Toggle label="CLOSE TO TRAY" detail="KEEP VPN RUNNING WHEN THE WINDOW IS CLOSED" checked={prefs.close_to_tray} onChange={onCloseToTray} />
      </SettingsSection>

      <SettingsSection title="SUBSCRIPTIONS">
        <div className="subscription-list">
          {groups.map(group => <div className="subscription" key={group.id}><div><strong>{group.name}</strong><small>{group.nodes.length} NODES</small></div><button className="tiny" disabled={busy} onClick={() => onRefresh(group)}>↻</button></div>)}
        </div>
        <form onSubmit={onAddSubscription}>
          <label>GROUP<input value={name} onChange={event => onName(event.target.value)} placeholder="vpn1" /></label>
          <label>SUBSCRIPTION URL<input value={url} onChange={event => onUrl(event.target.value)} type="url" placeholder="https://…" /></label>
          <button className="quiet" disabled={busy || !name.trim() || !url.trim()} type="submit">ADD SUBSCRIPTION</button>
        </form>
      </SettingsSection>

      <SettingsSection title="ABOUT">
        <div className="about"><span>dot. Desktop</span><small>{VERSION}</small><span>Xray Core</span><small>{XRAY_VERSION}</small><span>protocol</span><small>VLESS / REALITY</small></div>
      </SettingsSection>
    </div>
  </div>;
}

function Wordmark({ compact = false, hero = false, connected = false, pending = false, error = false }: { compact?: boolean; hero?: boolean; connected?: boolean; pending?: boolean; error?: boolean }) {
  const state = error ? "error" : connected ? "connected" : pending ? "pending" : "";
  return <div className={`wordmark ${compact ? "compact" : ""} ${hero ? "hero" : ""} ${state}`}>dot<span>.</span></div>;
}

function TrafficCell({ label, value, total }: { label: string; value: string; total?: string }) {
  return <div className="traffic-cell"><small>{label}</small><strong>{value}</strong>{total && <em>{total}</em>}</div>;
}

function SettingsSection({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="panel"><div className="panel-title">{title}</div>{children}</section>;
}

function Toggle({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (value: boolean) => void }) {
  return <button className="toggle" onClick={() => onChange(!checked)}><div><strong>{label}</strong><small>{detail}</small></div><span className={checked ? "switch on" : "switch"}><i /></span></button>;
}

function formatRate(value: number) { return `${formatBytes(value)}/s`; }
function formatBytes(value: number) { if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GB`; if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`; if (value >= 1024) return `${(value / 1024).toFixed(0)} KB`; return `${value} B`; }
function formatTime(value: number) { const hours = Math.floor(value / 3600); const minutes = Math.floor((value % 3600) / 60); const seconds = value % 60; return [hours, minutes, seconds].map(part => String(part).padStart(2, "0")).join(":"); }
