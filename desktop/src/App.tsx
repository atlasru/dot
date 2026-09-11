import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type {
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
const VERSION = "0.1.0-alpha.3";
const XRAY_VERSION = "v26.7.28";
const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, message: null };
const ZERO_TRAFFIC: TrafficSnapshot = { download_bytes_per_second: 0, upload_bytes_per_second: 0, session_download_bytes: 0, session_upload_bytes: 0, connected_seconds: 0 };
const DEFAULT_PREFS: AppPreferences = { theme: "amoled", close_to_tray: true };

export default function App() {
  const [view, setView] = useState<View>("home");
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
  const [testingNodeId, setTestingNodeId] = useState<string | null>(null);
  const [groupTestingId, setGroupTestingId] = useState<string | null>(null);
  const [refreshResult, setRefreshResult] = useState<SubscriptionRefreshResult | null>(null);
  const [groupTestResult, setGroupTestResult] = useState<GroupUrlTestResult | null>(null);

  const activeGroup = useMemo(() => groups.find(group => group.id === selection.group_id) ?? groups[0], [groups, selection.group_id]);
  const activeNode = useMemo(() => activeGroup?.nodes.find(node => node.id === selection.node_id) ?? activeGroup?.nodes[0], [activeGroup, selection.node_id]);
  const browseGroup = useMemo(() => groups.find(group => group.id === browseGroupId) ?? activeGroup ?? groups[0], [groups, browseGroupId, activeGroup]);

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
    reloadData().catch(error => setError(String(error)));
    const tick = () => Promise.all([
      invoke<EngineSnapshot>("vpn_status").then(setVpn),
      invoke<TrafficSnapshot>("traffic_status").then(setTraffic),
    ]).catch(() => undefined);
    tick();
    const timer = window.setInterval(tick, 1000);
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") setView("home"); };
    window.addEventListener("keydown", escape);
    return () => { window.clearInterval(timer); window.removeEventListener("keydown", escape); };
  }, []);

  useEffect(() => { document.documentElement.dataset.theme = prefs.theme; }, [prefs.theme]);

  async function chooseNode(groupId: string, nodeId: string) {
    if (busy || vpn.phase === "stopping") return;
    setBusy(true);
    setError("");
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
    if (vpn.phase === "stopping") return;
    setError("");
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
    } catch (caught) {
      setError(String(caught));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    }
  }

  async function runUrlTest(group = activeGroup, node = activeNode) {
    if (!group || !node || testingNodeId || vpn.phase === "starting" || vpn.phase === "stopping") return;
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
    if (!group || groupTestingId || vpn.phase !== "offline") return;
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
    setRefreshResult(null);
    try {
      const result = await invoke<SubscriptionRefreshResult>("refresh_subscription", { groupId: group.id });
      setRefreshResult(result);
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
    if (needsDelayTest && vpn.phase === "offline") await runGroupTest(group);
  }

  async function addSubscription(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await invoke("add_subscription", { name, url });
      setUrl("");
      await reloadData();
      setView("nodes");
    } catch (caught) {
      setError(String(caught));
    } finally {
      setBusy(false);
    }
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
        testing={!!activeNode && testingNodeId === activeNode.id}
        onToggleVpn={toggleVpn}
        onUrlTest={() => runUrlTest()}
        onOpenNodes={() => setView("nodes")}
      />}

      {view === "nodes" && <NodesPanel
        groups={groups}
        group={browseGroup}
        activeGroup={activeGroup}
        activeNode={activeNode}
        vpn={vpn}
        busy={busy}
        testingNodeId={testingNodeId}
        groupTesting={!!browseGroup && groupTestingId === browseGroup.id}
        refreshResult={refreshResult}
        groupTestResult={groupTestResult}
        onBrowseGroup={setBrowseGroupId}
        onChooseNode={chooseNode}
        onRefresh={() => refresh()}
        onTestNode={runUrlTest}
        onTestGroup={() => runGroupTest()}
        onSort={changeSort}
        onDismissRefresh={() => setRefreshResult(null)}
      />}

      {view === "settings" && <SettingsPanel
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
      />}
    </section>

    {error && <button className="error-toast" onClick={() => setError("")}>{error}</button>}
  </main>;
}

function Sidebar({ view, vpn, activeNode, groupCount, onView }: {
  view: View;
  vpn: EngineSnapshot;
  activeNode?: NodeView;
  groupCount: number;
  onView: (view: View) => void;
}) {
  return <aside className="sidebar">
    <div className="sidebar-head">
      <Wordmark compact />
      <span>{VERSION}</span>
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
        <button className={`url-test-button ${node?.latency_failed ? "failed" : ""}`} disabled={!node || pending || testing} onClick={onUrlTest}>
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

function NodesPanel({
  groups, group, activeGroup, activeNode, vpn, busy, testingNodeId, groupTesting, refreshResult, groupTestResult,
  onBrowseGroup, onChooseNode, onRefresh, onTestNode, onTestGroup, onSort, onDismissRefresh,
}: {
  groups: GroupView[];
  group?: GroupView;
  activeGroup?: GroupView;
  activeNode?: NodeView;
  vpn: EngineSnapshot;
  busy: boolean;
  testingNodeId: string | null;
  groupTesting: boolean;
  refreshResult: SubscriptionRefreshResult | null;
  groupTestResult: GroupUrlTestResult | null;
  onBrowseGroup: (id: string) => void;
  onChooseNode: (groupId: string, nodeId: string) => void;
  onRefresh: () => void;
  onTestNode: (group?: GroupView, node?: NodeView) => void;
  onTestGroup: () => void;
  onSort: (group: GroupView, mode: NodeSortMode) => void;
  onDismissRefresh: () => void;
}) {
  return <div className="nodes-view">
    <div className="workspace-head">
      <div><div className="view-kicker">NODES</div><h2>servers.</h2></div>
      <div className="workspace-actions">
        <button className="quiet compact-action" disabled={!group || busy || groupTesting} onClick={onRefresh}>↻ REFRESH</button>
        <button className="quiet compact-action" disabled={!group || groupTesting || vpn.phase !== "offline"} onClick={onTestGroup}>{groupTesting ? "TESTING…" : "TEST ALL"}</button>
      </div>
    </div>

    <div className="nodes-toolbar">
      <div className="group-picker wide">
        <label>SUBSCRIPTION</label>
        <div className="select-shell">
          <select value={group?.id ?? ""} onChange={event => onBrowseGroup(event.target.value)} disabled={!groups.length}>
            {!groups.length && <option value="">NO SUBSCRIPTIONS</option>}
            {groups.map(item => <option key={item.id} value={item.id}>{item.name} · {item.nodes.length}</option>)}
          </select>
          <b>⌄</b>
        </div>
      </div>

      {group && <div className="sort-picker">
        <label>SORT</label>
        <div className="segment-row">
          {(["origin", "delay", "name"] as NodeSortMode[]).map(mode => <button key={mode} className={group.sort_mode === mode ? "segment active" : "segment"} disabled={busy || groupTesting} onClick={() => onSort(group, mode)}>{mode.toUpperCase()}</button>)}
        </div>
      </div>}
    </div>

    {refreshResult && refreshResult.group.id === group?.id && <RefreshSummary result={refreshResult} onClose={onDismissRefresh} />}
    {groupTestResult && groupTestResult.group_id === group?.id && <div className="test-summary">URL TEST · {groupTestResult.succeeded} OK · {groupTestResult.failed} FAILED</div>}

    <div className="nodes-list large">
      {!group && <div className="empty-list"><strong>NO NODES</strong><span>ADD A SUBSCRIPTION IN SETTINGS.</span></div>}
      {group?.nodes.map(node => {
        const selected = activeGroup?.id === group.id && activeNode?.id === node.id;
        const running = vpn.phase === "connected" && vpn.node_name === node.name;
        const testing = testingNodeId === node.id;
        const latency = testing ? "··" : node.latency_failed ? "FAIL" : node.latency_ms !== null ? `${node.latency_ms} ms` : "—";
        return <div key={node.id} className={`node-row-card ${selected ? "selected" : ""}`}>
          <button className="node-main" disabled={busy || vpn.phase === "stopping"} onClick={() => onChooseNode(group.id, node.id)}>
            <i className="node-marker" />
            <span className="node-copy">
              <span className="node-title"><strong>{node.name}</strong>{running && <em>LIVE</em>}</span>
              <small>{node.security} · {node.transport} · {node.host}:{node.port}</small>
            </span>
          </button>
          <button className={`node-test ${node.latency_failed ? "failed" : ""}`} disabled={testing || vpn.phase === "starting" || vpn.phase === "stopping" || (vpn.phase === "connected" && !running)} onClick={() => onTestNode(group, node)}>{latency}</button>
        </div>;
      })}
    </div>
  </div>;
}

function RefreshSummary({ result, onClose }: { result: SubscriptionRefreshResult; onClose: () => void }) {
  const changed = result.added.length + result.deleted.length + result.edited.length;
  return <div className="refresh-summary">
    <div><strong>{changed ? "SUBSCRIPTION UPDATED" : "NO CHANGES"}</strong><small>{result.added.length} ADDED · {result.edited.length} EDITED · {result.deleted.length} DELETED · {result.unchanged} UNCHANGED</small></div>
    <button onClick={onClose}>×</button>
    {!!result.edited.length && <div className="refresh-edits">{result.edited.slice(0, 3).map(edit => <span key={`${edit.before.id}-${edit.after.id}`}>{edit.before.name} → {edit.after.name} · {edit.changed_fields.join(", ")}</span>)}</div>}
  </div>;
}

function SettingsPanel({ groups, prefs, busy, name, url, onName, onUrl, onTheme, onCloseToTray, onRefresh, onAddSubscription }: {
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

      <SettingsSection title="SUBSCRIPTIONS">
        <div className="subscription-list">
          {groups.map(group => <div className="subscription" key={group.id}><div><strong>{group.name}</strong><small>{group.nodes.length} NODES · {formatUpdated(group.updated_at_ms)}</small></div><button className="tiny" disabled={busy} onClick={() => onRefresh(group)}>↻</button></div>)}
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
function formatUpdated(value: number) { if (!value) return "NEVER"; const delta = Math.max(0, Date.now() - value); if (delta < 60_000) return "UPDATED NOW"; if (delta < 3_600_000) return `UPDATED ${Math.floor(delta / 60_000)}M AGO`; if (delta < 86_400_000) return `UPDATED ${Math.floor(delta / 3_600_000)}H AGO`; return `UPDATED ${Math.floor(delta / 86_400_000)}D AGO`; }
