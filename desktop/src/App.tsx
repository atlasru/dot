import NodeBrowser from "./NodeBrowser";
import SubscriptionManager from "./SubscriptionManager";
import { getVersion } from "@tauri-apps/api/app";
import React, { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type {
  RefreshNotice,
  AppPreferences,
  AppTheme,
  EngineSnapshot,
  GroupUrlTestResult,
  GroupView,
  NodeSortMode,
  NodeView,
  SelectionView,
  SubscriptionRefreshResult,
  TrafficSnapshot,
  UrlTestResult,
} from "./types";

type View = "home" | "nodes" | "settings";

const XRAY_VERSION = "v26.7.28";
const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, node_id: null, message: null };
const ZERO_TRAFFIC: TrafficSnapshot = { download_bytes_per_second: 0, upload_bytes_per_second: 0, session_download_bytes: 0, session_upload_bytes: 0, connected_seconds: 0 };
const DEFAULT_PREFS: AppPreferences = { theme: "amoled", close_to_tray: true, refresh_on_start: false, refresh_interval_hours: 0 };

export default function App() {
  const [version, setVersion] = useState("");
  const [notices, setNotices] = useState<RefreshNotice[]>([]);
  const [view, setView] = useState<View>("home");
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [selection, setSelection] = useState<SelectionView>({ group_id: null, node_id: null });
  const [browseGroupId, setBrowseGroupId] = useState<string | null>(null);
  const [vpn, setVpn] = useState<EngineSnapshot>(OFFLINE);
  const [traffic, setTraffic] = useState<TrafficSnapshot>(ZERO_TRAFFIC);
  const [prefs, setPrefs] = useState<AppPreferences>(DEFAULT_PREFS);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [testingNodeId, setTestingNodeId] = useState<string | null>(null);
  const [groupTestingId, setGroupTestingId] = useState<string | null>(null);
  const [groupTestResult, setGroupTestResult] = useState<GroupUrlTestResult | null>(null);

  const activeGroup = useMemo(() => groups.find(group => group.id === selection.group_id) ?? groups[0], [groups, selection.group_id]);
  const activeNode = useMemo(() => activeGroup?.nodes.find(node => node.id === selection.node_id) ?? activeGroup?.nodes[0], [activeGroup, selection.node_id]);
  const browseGroup = useMemo(() => groups.find(group => group.id === browseGroupId) ?? activeGroup ?? groups[0], [groups, browseGroupId, activeGroup]);

  async function reloadData() {
    const [nextGroups, nextSelection, nextPrefs, nextNotices] = await Promise.all([
      invoke<GroupView[]>("list_groups"),
      invoke<SelectionView>("selection"),
      invoke<AppPreferences>("preferences"),
      invoke<RefreshNotice[]>("refresh_notices"),
    ]);
    setGroups(nextGroups);
    setSelection(nextSelection);
    setPrefs(nextPrefs);
    setNotices(nextNotices);
    setBrowseGroupId(current => {
      if (current && nextGroups.some(group => group.id === current)) return current;
      return nextSelection.group_id ?? nextGroups[0]?.id ?? null;
    });
  }

  useEffect(() => {
    getVersion().then(setVersion).catch(error => setError(String(error)));
    reloadData().catch(error => setError(String(error)));
    const dataTimer = window.setInterval(() => reloadData().catch(() => undefined), 5000);
    const tick = () => Promise.all([
      invoke<EngineSnapshot>("vpn_status").then(setVpn),
      invoke<TrafficSnapshot>("traffic_status").then(setTraffic),
    ]).catch(() => undefined);
    tick();
    const timer = window.setInterval(tick, 1000);
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") setView("home"); };
    window.addEventListener("keydown", escape);
    return () => { window.clearInterval(timer); window.clearInterval(dataTimer); window.removeEventListener("keydown", escape); };
  }, []);

  useEffect(() => { document.documentElement.dataset.theme = prefs.theme; }, [prefs.theme]);

  async function chooseNode(groupId: string, nodeId: string) {
    if (busy || testingNodeId || groupTestingId || vpn.phase === "stopping") return;
    setBusy(true);
    setError("");
    try {
      const switching = vpn.phase === "connected" || vpn.phase === "starting";
      if (switching) {
        setVpn({ phase: "stopping", node_name: vpn.node_name, node_id: vpn.node_id, message: "switching node" });
        const nextVpn = await invoke<EngineSnapshot>("switch_node", { groupId, nodeId });
        setSelection({ group_id: groupId, node_id: nodeId });
        setVpn(nextVpn);
      } else {
        setSelection(await invoke<SelectionView>("select_node", { groupId, nodeId }));
      }
      setBrowseGroupId(groupId);
      await reloadData();
    } catch (caught) {
      setError(String(caught));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
      await reloadData().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  async function toggleVpn() {
    if (vpn.phase === "stopping" || testingNodeId || groupTestingId) return;
    setError("");
    if (vpn.phase === "connected" || vpn.phase === "starting") {
      try { setVpn(await invoke<EngineSnapshot>("disconnect")); } catch (e) { setError(String(e)); }
      return;
    }
    if (!activeGroup || !activeNode) {
      setError("select a node first");
      return;
    }
    setVpn({ phase: "starting", node_name: activeNode.name, node_id: activeNode.id, message: "starting" });
    try {
      setVpn(await invoke<EngineSnapshot>("connect", { groupId: activeGroup.id, nodeId: activeNode.id }));
    } catch (caught) {
      setError(String(caught));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    }
  }

  async function runUrlTest(group = activeGroup, node = activeNode) {
    if (!group || !node || testingNodeId || groupTestingId || busy || vpn.phase === "starting" || vpn.phase === "stopping") return;
    setTestingNodeId(node.id);
    setError("");
    try {
      await invoke<UrlTestResult>("url_test", { groupId: group.id, nodeId: node.id });
    } catch (caught) {
      setError(String(caught));
    } finally {
      setTestingNodeId(null);
      await reloadData().catch(() => undefined);
    }
  }

  async function runGroupTest(group = browseGroup) {
    if (!group || groupTestingId || testingNodeId || busy || !["offline", "error"].includes(vpn.phase)) return;
    setGroupTestingId(group.id);
    setGroupTestResult(null);
    setError("");
    try {
      setGroupTestResult(await invoke<GroupUrlTestResult>("url_test_group", { groupId: group.id }));
    } catch (caught) {
      setError(String(caught));
    } finally {
      setGroupTestingId(null);
      await reloadData().catch(() => undefined);
    }
  }

  async function refresh(group = browseGroup) {
    if (!group) return;
    setBusy(true);
    setError("");
    try {
      await invoke<SubscriptionRefreshResult>("refresh_subscription", { groupId: group.id });
      await reloadData();
    } catch (caught) {
      setError(String(caught));
    } finally {
      setBusy(false);
    }
  }

  async function changeSort(group: GroupView, mode: NodeSortMode) {
    if (busy || groupTestingId) return;
    const needsDelayTest = mode === "delay" && !group.nodes.some(node => node.latency_ms !== null || node.latency_failed);
    setBusy(true);
    setError("");
    try {
      await invoke<GroupView>("set_node_sort", { groupId: group.id, mode });
      await reloadData();
    } catch (caught) {
      setError(String(caught));
      return;
    } finally {
      setBusy(false);
    }
    if (needsDelayTest && ["offline", "error"].includes(vpn.phase)) await runGroupTest(group);
  }

  async function changeTheme(theme: AppTheme) {
    try { setPrefs(await invoke<AppPreferences>("set_theme", { theme })); }
    catch (caught) { setError(String(caught)); }
  }

  async function changeCloseToTray(enabled: boolean) {
    try { setPrefs(await invoke<AppPreferences>("set_close_to_tray", { enabled })); }
    catch (caught) { setError(String(caught)); }
  }

  return <main className="desktop-shell">
    <Sidebar
      version={version}
      view={view}
      vpn={vpn}
      activeNode={activeNode}
      groupCount={groups.length}
      onView={setView}
    />

    <section className="content-pane">
      {view === "home" && <ConnectionPanel
        group={activeGroup}
        node={activeNode}
        vpn={vpn}
        traffic={traffic}
        testing={!!testingNodeId || !!groupTestingId}
        onToggleVpn={toggleVpn}
        onUrlTest={() => runUrlTest()}
        onOpenNodes={() => setView("nodes")}
      />}

      {view === "nodes" && groupTestResult?.group_id === browseGroup?.id && groupTestResult && <div className="test-summary">URL TEST · {groupTestResult.succeeded} OK · {groupTestResult.failed} FAILED</div>}
      {view === "nodes" && <NodeBrowser
        groups={groups}
        group={browseGroup}
        activeGroup={activeGroup}
        activeNode={activeNode}
        vpn={vpn}
        busy={busy}
        testingNodeId={testingNodeId}
        groupTesting={!!browseGroup && groupTestingId === browseGroup.id}
        notices={notices}
        onChange={reloadData}
        onBrowseGroup={setBrowseGroupId}
        onChooseNode={chooseNode}
        onRefresh={() => refresh()}
        onTestNode={runUrlTest}
        onTestGroup={() => runGroupTest()}
        onSort={changeSort}
      />}

      {view === "settings" && <SettingsPanel
        version={version}
        vpn={vpn}
        notices={notices}
        onChange={reloadData}
        groups={groups}
        prefs={prefs}
        busy={busy}
        onTheme={changeTheme}
        onCloseToTray={changeCloseToTray}
      />}
    </section>

    {error && <button className="error-toast" onClick={() => setError("")}>{error}</button>}
  </main>;
}

function Sidebar({ version, view, vpn, activeNode, groupCount, onView }: {
  version: string;
  view: View;
  vpn: EngineSnapshot;
  activeNode?: NodeView;
  groupCount: number;
  onView: (view: View) => void;
}) {
  return <aside className="sidebar">
    <div className="sidebar-head">
      <Wordmark compact />
      <span>{version}</span>
    </div>

    <nav className="sidebar-nav" aria-label="Primary">
      <NavButton label="HOME" glyph="●" active={view === "home"} onClick={() => onView("home")} />
      <NavButton label="NODES" glyph="⌁" detail={String(groupCount)} active={view === "nodes"} onClick={() => onView("nodes")} />
      <NavButton label="SETTINGS" glyph="⚙" active={view === "settings"} onClick={() => onView("settings")} />
    </nav>

    <div className="sidebar-spacer" />
    <div className={`sidebar-status ${vpn.phase}`}>
      <span className="status-dot" />
      <div><small>{vpn.phase.toUpperCase()}</small><strong>{vpn.node_name ?? activeNode?.name ?? "NO NODE"}</strong></div>
    </div>
  </aside>;
}

function NavButton({ label, glyph, detail, active, onClick }: { label: string; glyph: string; detail?: string; active: boolean; onClick: () => void }) {
  return <button className={`nav-button ${active ? "active" : ""}`} onClick={onClick}>
    <span>{glyph}</span><strong>{label}</strong>{detail && <em>{detail}</em>}
  </button>;
}

function ConnectionPanel({ group, node, vpn, traffic, testing, onToggleVpn, onUrlTest, onOpenNodes }: {
  group?: GroupView;
  node?: NodeView;
  vpn: EngineSnapshot;
  traffic: TrafficSnapshot;
  testing: boolean;
  onToggleVpn: () => void;
  onUrlTest: () => void;
  onOpenNodes: () => void;
}) {
  const connected = vpn.phase === "connected";
  const pending = vpn.phase === "starting" || vpn.phase === "stopping";
  const button = vpn.phase === "stopping" ? "STOPPING" : connected || vpn.phase === "starting" ? "DISCONNECT" : "CONNECT";
  const currentNode = vpn.phase !== "connected" || vpn.node_id === node?.id;
  const testResult = testing ? "TESTING…" : node?.latency_failed ? "FAILED" : node?.latency_ms ? `${node.latency_ms} ms` : "READY";
  const displayNode = vpn.node_name ?? node?.name ?? "SELECT A NODE";

  return <div className="connection-view">
    <div className="view-kicker">HOME</div>
    <div className="connection-center">
      <Wordmark hero connected={connected} pending={pending} error={vpn.phase === "error"} />
      <div className={`phase ${vpn.phase}`}>{vpn.phase.toUpperCase()}</div>
      <button className="active-node-button" onClick={onOpenNodes}>{displayNode}</button>
      <div className="active-node-meta">{group?.name ?? "NO SUBSCRIPTION"}{node ? ` · ${node.security} · ${node.transport}` : ""}</div>

      <div className="connection-actions">
        <button className="connect-button" disabled={vpn.phase === "stopping" || (!node && vpn.phase === "offline")} onClick={onToggleVpn}>{button}</button>
        <button className={`url-test-button ${node?.latency_failed ? "failed" : ""}`} disabled={!node || pending || testing || !currentNode} onClick={onUrlTest}>
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

function SettingsPanel({ version, vpn, notices, onChange, groups, prefs, busy, onTheme, onCloseToTray }: {
  version: string;
  vpn: EngineSnapshot;
  notices: RefreshNotice[];
  onChange: () => Promise<void>;
  groups: GroupView[];
  prefs: AppPreferences;
  busy: boolean;
  onTheme: (theme: AppTheme) => void;
  onCloseToTray: (enabled: boolean) => void;
}) {
  return <div className="settings-view">
    <div className="workspace-head"><div><div className="view-kicker">SETTINGS</div><h2>settings.</h2></div></div>

    <div className="settings-scroll">
      <SettingsSection title="APPEARANCE">
        <div className="theme-row">{(["amoled", "graphite", "matrix"] as AppTheme[]).map(theme => <button key={theme} className={prefs.theme === theme ? "choice selected" : "choice"} onClick={() => onTheme(theme)}>{theme.toUpperCase()}</button>)}</div>
      </SettingsSection>

      <SettingsSection title="WINDOW">
        <Toggle label="CLOSE TO TRAY" detail="KEEP VPN RUNNING WHEN THE WINDOW IS CLOSED" checked={prefs.close_to_tray} onChange={onCloseToTray} />
      </SettingsSection>

      <SubscriptionManager groups={groups} prefs={prefs} vpn={vpn} notices={notices} onChange={onChange} />

      <SettingsSection title="ABOUT">
        <div className="about"><span>dot. Desktop</span><small>{version}</small><span>Xray Core</span><small>{XRAY_VERSION}</small><span>protocol</span><small>VLESS / REALITY</small></div>
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
function formatUpdated(value: number) { if (!value) return "NEVER"; const delta = Math.max(0, Date.now() - value); if (delta < 60_000) return "UPDATED NOW"; if (delta < 3_600_000) return `UPDATED ${Math.floor(delta / 60_000)}M AGO`; if (delta < 86_400_000) return `UPDATED ${Math.floor(delta / 3_600_000)}H AGO`; return `UPDATED ${Math.floor(delta / 86_400_000)}D AGO`; }
