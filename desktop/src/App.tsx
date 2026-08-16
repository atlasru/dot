import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import WorldMap, { type WorldMapMarker } from "./WorldMap";
import type {
  AppPreferences, AppTheme, EngineSnapshot, GeoPoint, GroupView, NodeSortMode, NodeView,
  SelectionView, SubscriptionRefreshResult, TrafficSnapshot, UrlTestResult,
} from "./types";

const APP_VERSION = "0.2.2-alpha.1";
const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, message: null };
const ZERO_TRAFFIC: TrafficSnapshot = { download_bytes_per_second: 0, upload_bytes_per_second: 0, session_download_bytes: 0, session_upload_bytes: 0, connected_seconds: 0 };
const DEFAULT_PREFS: AppPreferences = { theme: "amoled", close_to_tray: true };
const GEO_TTL = 7 * 24 * 60 * 60 * 1000;

type Page = "main" | "settings";
type ViewMode = "list" | "map";
type Latencies = Record<string, number>;
type CountryCluster = {
  code: string; name: string; latitude: number; longitude: number; nodes: NodeView[];
  cities: string[]; fallbackOnly: boolean;
};

export default function App() {
  const [page, setPage] = useState<Page>("main");
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [selection, setSelection] = useState<SelectionView>({ group_id: null, node_id: null });
  const [viewGroupId, setViewGroupId] = useState<string | null>(null);
  const [groupMenuOpen, setGroupMenuOpen] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>("list");
  const [vpn, setVpn] = useState<EngineSnapshot>(OFFLINE);
  const [traffic, setTraffic] = useState<TrafficSnapshot>(ZERO_TRAFFIC);
  const [prefs, setPrefs] = useState<AppPreferences>(DEFAULT_PREFS);
  const [name, setName] = useState("vpn1");
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [latencies, setLatencies] = useState<Latencies>({});
  const [failedIds, setFailedIds] = useState<Set<string>>(new Set());
  const [testingIds, setTestingIds] = useState<Set<string>>(new Set());
  const [testingGroupId, setTestingGroupId] = useState<string | null>(null);
  const [pendingDelayGroupId, setPendingDelayGroupId] = useState<string | null>(null);
  const [sortModes, setSortModes] = useState<Record<string, NodeSortMode>>({});
  const [refreshResult, setRefreshResult] = useState<SubscriptionRefreshResult | null>(null);

  const activeGroup = useMemo(() => groups.find(group => group.id === selection.group_id) ?? groups[0], [groups, selection.group_id]);
  const activeNode = useMemo(() => activeGroup?.nodes.find(node => node.id === selection.node_id) ?? activeGroup?.nodes[0], [activeGroup, selection.node_id]);
  const visibleGroup = useMemo(() => groups.find(group => group.id === viewGroupId) ?? activeGroup ?? groups[0], [groups, viewGroupId, activeGroup]);
  const visibleSort = visibleGroup ? (sortModes[visibleGroup.id] ?? readSortMode(visibleGroup.id)) : "origin";
  const visibleNodes = useMemo(() => visibleGroup ? sortNodes(visibleGroup.nodes, visibleSort, latencies, failedIds, pendingDelayGroupId === visibleGroup.id) : [], [visibleGroup, visibleSort, latencies, failedIds, pendingDelayGroupId]);

  async function reloadData() {
    const [nextGroups, nextSelection, nextPrefs] = await Promise.all([
      invoke<GroupView[]>("list_groups"), invoke<SelectionView>("selection"), invoke<AppPreferences>("preferences"),
    ]);
    setGroups(nextGroups); setSelection(nextSelection); setPrefs(nextPrefs);
    setViewGroupId(current => current ?? nextSelection.group_id ?? nextGroups[0]?.id ?? null);
  }

  useEffect(() => {
    reloadData().catch(reason => setError(String(reason)));
    const tick = () => Promise.all([
      invoke<EngineSnapshot>("vpn_status").then(setVpn), invoke<TrafficSnapshot>("traffic_status").then(setTraffic),
    ]).catch(() => undefined);
    tick();
    const timer = window.setInterval(tick, 1000);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (refreshResult) setRefreshResult(null);
      else if (groupMenuOpen) setGroupMenuOpen(false);
      else if (page === "settings") setPage("main");
    };
    window.addEventListener("keydown", onKeyDown);
    return () => { window.clearInterval(timer); window.removeEventListener("keydown", onKeyDown); };
  }, [page, groupMenuOpen, refreshResult]);

  useEffect(() => { document.documentElement.dataset.theme = prefs.theme; }, [prefs.theme]);
  useEffect(() => { setViewMode("list"); }, [viewGroupId]);

  async function chooseNode(groupId: string, nodeId: string) {
    if (busy || vpn.phase === "stopping") return;
    setBusy(true); setError(""); setViewGroupId(groupId);
    try {
      const switching = vpn.phase === "connected" || vpn.phase === "starting";
      if (switching) {
        setVpn({ phase: "stopping", node_name: vpn.node_name, message: "switching node" });
        const nextVpn = await invoke<EngineSnapshot>("switch_node", { groupId, nodeId });
        setSelection({ group_id: groupId, node_id: nodeId }); setVpn(nextVpn);
      } else {
        setSelection(await invoke<SelectionView>("select_node", { groupId, nodeId }));
      }
    } catch (reason) {
      setError(String(reason)); invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined); await reloadData().catch(() => undefined);
    } finally { setBusy(false); }
  }

  async function activateNode(group: GroupView, node: NodeView) {
    if (busy || vpn.phase === "starting" || vpn.phase === "stopping") return;
    if (vpn.phase === "connected") { await chooseNode(group.id, node.id); return; }
    setBusy(true); setError("");
    try {
      setSelection(await invoke<SelectionView>("select_node", { groupId: group.id, nodeId: node.id }));
      setVpn({ phase: "starting", node_name: node.name, message: "starting" });
      setVpn(await invoke<EngineSnapshot>("connect", { groupId: group.id, nodeId: node.id }));
    } catch (reason) {
      setError(String(reason)); invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    } finally { setBusy(false); }
  }

  async function toggleVpn() {
    if (vpn.phase === "stopping") return;
    setError("");
    if (vpn.phase === "connected" || vpn.phase === "starting") { setVpn(await invoke<EngineSnapshot>("disconnect")); return; }
    if (!activeGroup || !activeNode) { setError("select a node first"); return; }
    setVpn({ phase: "starting", node_name: activeNode.name, message: "starting" });
    try { setVpn(await invoke<EngineSnapshot>("connect", { groupId: activeGroup.id, nodeId: activeNode.id })); }
    catch (reason) { setError(String(reason)); invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined); }
  }

  async function testNode(group: GroupView, node: NodeView, quiet = false) {
    if (testingIds.has(node.id) || vpn.phase === "starting" || vpn.phase === "stopping") return false;
    setTestingIds(current => new Set(current).add(node.id));
    if (!quiet) setError("");
    try {
      const result = await invoke<UrlTestResult>("url_test", { groupId: group.id, nodeId: node.id });
      setLatencies(current => ({ ...current, [result.node_id]: result.latency_ms }));
      setFailedIds(current => { const next = new Set(current); next.delete(result.node_id); return next; });
      return true;
    } catch (reason) {
      setFailedIds(current => new Set(current).add(node.id));
      if (!quiet) setError(String(reason));
      return false;
    } finally {
      setTestingIds(current => { const next = new Set(current); next.delete(node.id); return next; });
    }
  }

  async function testAll(group: GroupView, forDelaySort = false) {
    if (testingGroupId) return;
    if (vpn.phase === "connected" || vpn.phase === "starting" || vpn.phase === "stopping") {
      setError("disconnect VPN before testing the whole group");
      if (forDelaySort) setPendingDelayGroupId(null);
      return;
    }
    setTestingGroupId(group.id); setError("");
    if (forDelaySort) setPendingDelayGroupId(group.id);
    try {
      for (const node of group.nodes) await testNode(group, node, true);
    } finally {
      setTestingGroupId(null);
      if (forDelaySort) setPendingDelayGroupId(null);
    }
  }

  async function changeSort(group: GroupView, mode: NodeSortMode) {
    localStorage.setItem(`dot.sort.${group.id}`, mode);
    setSortModes(current => ({ ...current, [group.id]: mode }));
    if (mode === "delay" && !group.nodes.some(node => latencies[node.id] != null || failedIds.has(node.id))) {
      await testAll(group, true);
    }
  }

  async function refresh(group = visibleGroup) {
    if (!group) return;
    setBusy(true); setError("");
    try {
      const result = await invoke<SubscriptionRefreshResult>("refresh_subscription", { groupId: group.id });
      setRefreshResult(result);
      if (result.success) {
        const replacements = new Map(result.id_replacements.map(item => [item.before_id, item.after_id]));
        setLatencies(current => {
          const next: Latencies = {};
          for (const [oldId, value] of Object.entries(current)) { const nextId = replacements.get(oldId); if (nextId) next[nextId] = value; }
          return next;
        });
        setFailedIds(current => {
          const next = new Set<string>();
          current.forEach(oldId => { const nextId = replacements.get(oldId); if (nextId) next.add(nextId); });
          return next;
        });
        await reloadData();
      }
    } catch (reason) { setError(String(reason)); }
    finally { setBusy(false); }
  }

  async function addSubscription(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { const created = await invoke<GroupView>("add_subscription", { name, url }); setUrl(""); await reloadData(); setViewGroupId(created.id); }
    catch (reason) { setError(String(reason)); } finally { setBusy(false); }
  }

  async function changeTheme(theme: AppTheme) { try { setPrefs(await invoke<AppPreferences>("set_theme", { theme })); } catch (reason) { setError(String(reason)); } }
  async function changeCloseToTray(enabled: boolean) { try { setPrefs(await invoke<AppPreferences>("set_close_to_tray", { enabled })); } catch (reason) { setError(String(reason)); } }

  return <main className="shell">
    {page === "main" ? <div className="main-page">
      <header className="topbar"><h1>dot.</h1><div className="topbar-actions"><small>v{APP_VERSION}</small><button className="icon-button" onClick={() => setPage("settings")} aria-label="settings">⚙</button></div></header>
      <div className="dashboard-layout">
        <section className="connection-pane">
          <PixelOrb phase={vpn.phase} onClick={toggleVpn} disabled={busy || vpn.phase === "stopping"} />
          <strong className={`status status-${vpn.phase}`}>{connectionLabel(vpn.phase)}</strong>
          <small className="current-node">{vpn.node_name ?? activeNode?.name ?? "select a node below"}</small>
          <small className="connection-hint">{vpn.phase === "offline" ? "tap orb to connect" : vpn.message ?? "VLESS · Windows TUN"}</small>
          {activeGroup && activeNode && <button className="connection-test" disabled={testingIds.has(activeNode.id) || vpn.phase === "starting" || vpn.phase === "stopping"} onClick={() => testNode(activeGroup, activeNode)}>
            <span>URL TEST</span><strong>{testingIds.has(activeNode.id) ? "TESTING…" : latencies[activeNode.id] != null ? `${latencies[activeNode.id]} ms` : failedIds.has(activeNode.id) ? "FAIL" : "READY"}</strong>
          </button>}
          <div className="traffic-grid"><TrafficCell label="download" arrow="↓" value={formatRate(traffic.download_bytes_per_second)} total={formatBytes(traffic.session_download_bytes)} /><TrafficCell label="upload" arrow="↑" value={formatRate(traffic.upload_bytes_per_second)} total={formatBytes(traffic.session_upload_bytes)} align="right" /></div>
          <div className="session-line"><span>SESSION</span><strong>{formatTime(traffic.connected_seconds)}</strong></div>
        </section>

        <section className="nodes-pane">
          <NodeToolbar groups={groups} group={visibleGroup} menuOpen={groupMenuOpen} busy={busy} viewMode={viewMode} sortMode={visibleSort} testingGroup={testingGroupId === visibleGroup?.id}
            onViewMode={setViewMode} onSort={mode => visibleGroup && changeSort(visibleGroup, mode)} onTestAll={() => visibleGroup && testAll(visibleGroup)}
            onToggleMenu={() => setGroupMenuOpen(value => !value)} onChooseGroup={groupId => { setViewGroupId(groupId); setGroupMenuOpen(false); }}
            onRefresh={() => refresh(visibleGroup)} onOpenSettings={() => { setGroupMenuOpen(false); setPage("settings"); }} />
          {!visibleGroup ? <EmptyNodes title="no subscriptions" detail="add a subscription in settings." action="OPEN SETTINGS" onAction={() => setPage("settings")} /> : visibleGroup.nodes.length === 0 ? <EmptyNodes title={`no nodes in ${visibleGroup.name}`} detail="refresh this subscription or check its URL." action="REFRESH" onAction={() => refresh(visibleGroup)} /> : viewMode === "map" ?
            <NodeMap group={visibleGroup} nodes={visibleNodes} activeNode={activeNode} runningNodeName={vpn.node_name} latencies={latencies} failedIds={failedIds} testingIds={testingIds} busy={busy}
              onActivate={activateNode} onError={setError} /> :
            <div className="node-list" role="list">{visibleNodes.map(node => <NodeRow key={node.id} node={node} selected={visibleGroup.id === activeGroup?.id && node.id === activeNode?.id} live={vpn.phase === "connected" && vpn.node_name === node.name} disabled={busy} latency={latencies[node.id]} failed={failedIds.has(node.id)} testing={testingIds.has(node.id)} onClick={() => chooseNode(visibleGroup.id, node.id)} onTest={() => testNode(visibleGroup, node)} />)}</div>}
        </section>
      </div>
    </div> : <div className="settings-page">
      <header className="topbar settings-topbar"><div><h1>settings.</h1><small>desktop preferences</small></div><button className="icon-button" onClick={() => setPage("main")} aria-label="close settings">×</button></header>
      <SettingsSection title="appearance"><div className="theme-row">{(["amoled", "graphite", "matrix"] as AppTheme[]).map(theme => <button key={theme} className={prefs.theme === theme ? "choice selected" : "choice"} onClick={() => changeTheme(theme)}>{theme}</button>)}</div></SettingsSection>
      <SettingsSection title="window"><Toggle label="close to tray" detail="keep dot. and the VPN alive after closing the window" checked={prefs.close_to_tray} onChange={changeCloseToTray} /></SettingsSection>
      <SettingsSection title="subscriptions">{groups.map(group => <div className="subscription" key={group.id}><button className="subscription-main" onClick={() => { setViewGroupId(group.id); setPage("main"); }}><strong>{group.name}</strong><small>{group.nodes.length} nodes · {readSortMode(group.id)}</small></button><button className="tiny" disabled={busy} onClick={() => refresh(group)}>↻</button></div>)}<form onSubmit={addSubscription}><label>group<input value={name} onChange={event => setName(event.target.value)} placeholder="vpn1" /></label><label>subscription url<input value={url} onChange={event => setUrl(event.target.value)} type="url" placeholder="https://…" /></label><button className="quiet" disabled={busy || !name.trim() || !url.trim()} type="submit">add subscription</button></form></SettingsSection>
      <SettingsSection title="about"><div className="about"><span>dot. Desktop</span><small>{APP_VERSION}</small><span>Xray Core</span><small>v26.7.28</small><span>protocol</span><small>VLESS / REALITY</small></div></SettingsSection>
    </div>}
    {error && <button className="error" onClick={() => setError("")}>{error}</button>}
    {refreshResult && <RefreshDialog result={refreshResult} onClose={() => setRefreshResult(null)} />}
  </main>;
}

function PixelOrb({ phase, onClick, disabled }: { phase: EngineSnapshot["phase"]; onClick: () => void; disabled: boolean }) {
  const pattern = ["00011111000","00110001100","01100000110","11001010011","10010101001","10001010001","10010101001","11001010011","01100000110","00110001100","00011111000"];
  const active = phase === "connected", pending = phase === "starting" || phase === "stopping", failed = phase === "error";
  return <button className={`orb ${active ? "active" : ""} ${pending ? "pending" : ""} ${failed ? "failed" : ""}`} onClick={onClick} disabled={disabled} aria-label={active ? "disconnect VPN" : "connect VPN"}><span className="orb-grid" aria-hidden="true">{pattern.flatMap((row, rowIndex) => row.split("").map((cell, columnIndex) => <i key={`${rowIndex}-${columnIndex}`} className={`${cell === "1" ? "lit" : ""} ${failed && (rowIndex === columnIndex || rowIndex + columnIndex === 10) ? "error-pixel" : ""}`} />))}</span><span className="orb-dot" aria-hidden="true" /></button>;
}

function NodeToolbar({ groups, group, menuOpen, busy, viewMode, sortMode, testingGroup, onViewMode, onSort, onTestAll, onToggleMenu, onChooseGroup, onRefresh, onOpenSettings }: {
  groups: GroupView[]; group?: GroupView; menuOpen: boolean; busy: boolean; viewMode: ViewMode; sortMode: NodeSortMode; testingGroup: boolean;
  onViewMode: (mode: ViewMode) => void; onSort: (mode: NodeSortMode) => void; onTestAll: () => void; onToggleMenu: () => void; onChooseGroup: (id: string) => void; onRefresh: () => void; onOpenSettings: () => void;
}) {
  return <>
    <div className="node-toolbar"><div className="group-picker"><button className="group-button" onClick={onToggleMenu} aria-expanded={menuOpen}><strong>{group?.name ?? "subscriptions"}</strong><span>⌄</span></button>{menuOpen && <div className="group-menu"><div className="group-menu-title">subscriptions.</div>{groups.map(item => <button key={item.id} className={item.id === group?.id ? "group-menu-item selected" : "group-menu-item"} onClick={() => onChooseGroup(item.id)}><span>{item.name}</span><small>{item.nodes.length}</small></button>)}<button className="group-menu-item add" onClick={onOpenSettings}>+ add / manage</button></div>}</div><div className="node-tools"><small>{group?.nodes.length ?? 0} nodes</small><button className="refresh-button" disabled={!group || busy} onClick={onRefresh} aria-label="refresh subscription">↻</button></div></div>
    {group && group.nodes.length > 0 && <div className="node-controlbar">
      <select value={sortMode} onChange={event => onSort(event.target.value as NodeSortMode)} aria-label="node sort"><option value="origin">ORIGIN</option><option value="delay">DELAY</option><option value="name">NAME</option></select>
      <button disabled={testingGroup || busy} onClick={onTestAll}>{testingGroup ? "TESTING…" : "TEST ALL"}</button>
      <span className="control-spacer" />
      <button className={viewMode === "list" ? "active" : ""} onClick={() => onViewMode("list")}>LIST</button><button className={viewMode === "map" ? "active" : ""} onClick={() => onViewMode("map")}>MAP</button>
    </div>}
  </>;
}

function NodeRow({ node, selected, live, disabled, latency, failed, testing, onClick, onTest }: { node: NodeView; selected: boolean; live: boolean; disabled: boolean; latency?: number; failed: boolean; testing: boolean; onClick: () => void; onTest: () => void }) {
  return <div className={`node-row parity-node-row ${selected ? "selected" : ""} ${live ? "live" : ""}`} role="listitem">
    <button className="node-main" disabled={disabled} onClick={onClick}><span className="node-marker"/><span className="node-copy"><span className="node-name">{node.name}</span><small>{node.security} · {node.transport} · {node.host}:{node.port}</small></span><span className="node-state"><em>{live ? "LIVE" : selected ? "SELECTED" : ""}</em></span></button>
    <button className={`node-test ${failed ? "failed" : ""}`} disabled={disabled || testing} onClick={onTest}>{testing ? "··" : latency != null ? `${latency} ms` : failed ? "FAIL" : "TEST"}</button>
  </div>;
}

function NodeMap({ group, nodes, activeNode, runningNodeName, latencies, failedIds, testingIds, busy, onActivate, onError }: {
  group: GroupView; nodes: NodeView[]; activeNode?: NodeView; runningNodeName: string | null; latencies: Latencies; failedIds: Set<string>; testingIds: Set<string>; busy: boolean;
  onActivate: (group: GroupView, node: NodeView) => void; onError: (error: string) => void;
}) {
  const [locations, setLocations] = useState<Record<string, GeoPoint>>({});
  const [completed, setCompleted] = useState(0);
  const [selectedCountry, setSelectedCountry] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLocations({}); setCompleted(0); setSelectedCountry(null);
    const queue = [...group.nodes];
    async function worker() {
      while (!cancelled) {
        const node = queue.shift(); if (!node) return;
        try {
          const location = await resolveGeo(node);
          if (!cancelled && location) setLocations(current => ({ ...current, [node.id]: location }));
        } catch { /* fallback failure is represented by missing location */ }
        finally { if (!cancelled) setCompleted(value => value + 1); }
      }
    }
    Promise.all(Array.from({ length: Math.min(4, queue.length) }, worker)).catch(reason => onError(String(reason)));
    return () => { cancelled = true; };
  }, [group.id, group.nodes.map(node => `${node.id}:${node.host}:${node.name}`).join("|")]);

  const clusters = useMemo<CountryCluster[]>(() => {
    const byCode = new Map<string, { node: NodeView; geo: GeoPoint }[]>();
    for (const node of group.nodes) {
      const geo = locations[node.id]; if (!geo) continue;
      const code = geo.country_code.toUpperCase();
      const list = byCode.get(code) ?? []; list.push({ node, geo }); byCode.set(code, list);
    }
    return [...byCode.entries()].map(([code, entries]) => {
      const exact = entries.filter(entry => entry.geo.source === "geo_ip");
      const anchors = exact.length ? exact : entries;
      return {
        code, name: entries[0].geo.country, latitude: anchors.reduce((sum, item) => sum + item.geo.latitude, 0) / anchors.length,
        longitude: anchors.reduce((sum, item) => sum + item.geo.longitude, 0) / anchors.length,
        nodes: nodes.filter(node => entries.some(entry => entry.node.id === node.id)),
        cities: [...new Set(exact.map(entry => entry.geo.city).filter(Boolean) as string[])].sort(), fallbackOnly: exact.length === 0,
      };
    }).sort((a, b) => a.name.localeCompare(b.name));
  }, [locations, group.nodes, nodes]);

  const markers: WorldMapMarker[] = clusters.map(cluster => ({ countryCode: cluster.code, latitude: cluster.latitude, longitude: cluster.longitude, nodeCount: cluster.nodes.length, active: cluster.nodes.some(node => node.name === runningNodeName) }));
  const cluster = clusters.find(item => item.code === selectedCountry) ?? null;
  const missing = Math.max(0, completed - Object.keys(locations).length);

  return <div className="parity-map">
    <div className="map-stage"><WorldMap markers={markers} selectedCountry={selectedCountry} onMarkerClick={setSelectedCountry}/><div className="map-status">{completed < group.nodes.length ? `LOCATING ${completed}/${group.nodes.length}` : missing ? `${Object.keys(locations).length}/${group.nodes.length} LOCATED` : `${group.nodes.length} NODES · ${clusters.length} COUNTRIES`}</div></div>
    {cluster ? <CountrySheet cluster={cluster} activeNode={activeNode} runningNodeName={runningNodeName} latencies={latencies} failedIds={failedIds} testingIds={testingIds} busy={busy} onActivate={node => onActivate(group, node)} /> : <small className="map-hint">{clusters.length === 0 && completed === group.nodes.length ? "no node locations available" : missing > 0 && completed === group.nodes.length ? `${missing} node(s) could not be located` : "click a country marker to show nodes · double-click map to reset"}</small>}
  </div>;
}

function CountrySheet({ cluster, activeNode, runningNodeName, latencies, failedIds, testingIds, busy, onActivate }: { cluster: CountryCluster; activeNode?: NodeView; runningNodeName: string | null; latencies: Latencies; failedIds: Set<string>; testingIds: Set<string>; busy: boolean; onActivate: (node: NodeView) => void }) {
  return <div className="country-sheet"><div className="country-head"><div><strong>{countryFlag(cluster.code)} {cluster.name}</strong><small>{cluster.cities.length ? cluster.cities.join(" · ") : cluster.fallbackOnly ? "country location · name fallback" : `${cluster.nodes.length} nodes`}</small></div><span>{cluster.nodes.length}</span></div><div className="country-nodes">{cluster.nodes.map(node => {
    const running = node.name === runningNodeName;
    return <button key={node.id} disabled={busy || running} onClick={() => onActivate(node)}><i className={running ? "live" : ""}/><span>{node.name}</span><em className={failedIds.has(node.id) ? "failed" : running ? "live" : ""}>{testingIds.has(node.id) ? "··" : latencies[node.id] != null ? `${latencies[node.id]} ms` : failedIds.has(node.id) ? "FAIL" : running ? "LIVE" : node.id === activeNode?.id ? "CONNECT" : "CONNECT"}</em></button>;
  })}</div></div>;
}

function RefreshDialog({ result, onClose }: { result: SubscriptionRefreshResult; onClose: () => void }) {
  const [details, setDetails] = useState(false); const [raw, setRaw] = useState(false);
  const hasChanges = result.added.length + result.edited.length + result.deleted.length > 0;
  return <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}><div className="refresh-dialog">
    {result.success ? <>
      <h2>SUBSCRIPTION UPDATED</h2><small>{result.subscription_name}</small><div className="refresh-summary">{!hasChanges ? <strong>NO CHANGES</strong> : <><ChangeLine symbol="+" label="ADDED" count={result.added.length}/><ChangeLine symbol="~" label="EDITED" count={result.edited.length}/><ChangeLine symbol="−" label="DELETED" count={result.deleted.length} deleted/></>}<em>{result.total_nodes} NODES TOTAL</em></div>
      {details && hasChanges && <div className="refresh-details">{result.added.length > 0 && <><b>ADDED</b>{result.added.map(node => <span key={`a:${node.id}`}>+ {node.name}</span>)}</>}{result.edited.length > 0 && <><b>EDITED</b>{result.edited.map(node => <span key={`e:${node.id}`}>~ {node.name}<small>{node.changed_fields.join(", ")}</small></span>)}</>}{result.deleted.length > 0 && <><b>DELETED</b>{result.deleted.map(node => <span className="deleted" key={`d:${node.id}`}>− {node.name}</span>)}</>}</div>}
      <div className="dialog-actions">{hasChanges && <button onClick={() => setDetails(value => !value)}>{details ? "HIDE DETAILS" : "DETAILS"}</button>}<button className="primary" onClick={onClose}>OK</button></div>
    </> : <>
      <h2 className="dialog-error-title">UPDATE FAILED</h2><small>{result.subscription_name}</small>{raw ? <div className="raw-error"><b>RAW ERROR</b><pre>{result.raw_error}</pre></div> : <div className="friendly-error"><strong>{result.user_message}</strong><small>Your existing nodes were kept.</small></div>}
      <div className="dialog-actions"><button onClick={() => setRaw(value => !value)}>{raw ? "BACK" : "VIEW RAW ERROR"}</button><button className="primary" onClick={onClose}>OK</button></div>
    </>}
  </div></div>;
}

function ChangeLine({ symbol, label, count, deleted = false }: { symbol: string; label: string; count: number; deleted?: boolean }) { return <div className={deleted && count ? "change-line deleted" : "change-line"}><span>{symbol} {label}</span><strong>{count}</strong></div>; }
function TrafficCell({ label, arrow, value, total, align }: { label: string; arrow: string; value: string; total: string; align?: "right" }) { return <div className={`traffic-cell ${align === "right" ? "align-right" : ""}`}><small>{label}</small><strong>{arrow} {value}</strong><em>{total}</em></div>; }
function SettingsSection({ title, children }: { title: string; children: React.ReactNode }) { return <section className="panel"><div className="panel-title">{title}.</div>{children}</section>; }
function Toggle({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (value: boolean) => void }) { return <label className="toggle"><span><strong>{label}</strong><small>{detail}</small></span><input type="checkbox" checked={checked} onChange={event => onChange(event.target.checked)}/></label>; }
function EmptyNodes({ title, detail, action, onAction }: { title: string; detail: string; action: string; onAction: () => void }) { return <div className="empty-nodes"><strong>{title}</strong><small>{detail}</small><button className="quiet" onClick={onAction}>{action}</button></div>; }

function connectionLabel(phase: EngineSnapshot["phase"]) { return phase === "starting" ? "connecting" : phase === "stopping" ? "disconnecting" : phase; }
function formatRate(bytes: number) { return `${formatBytes(bytes)}/s`; }
function formatBytes(bytes: number) { if (bytes < 1024) return `${bytes} B`; if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`; if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`; return `${(bytes / 1024 ** 3).toFixed(2)} GB`; }
function formatTime(seconds: number) { const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60; return [h,m,s].map(v => String(v).padStart(2,"0")).join(":"); }
function readSortMode(groupId: string): NodeSortMode { const value = localStorage.getItem(`dot.sort.${groupId}`); return value === "delay" || value === "name" ? value : "origin"; }
function sortNodes(nodes: NodeView[], mode: NodeSortMode, latencies: Latencies, failed: Set<string>, pending: boolean) {
  if (mode === "origin" || pending) return nodes;
  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });
  if (mode === "name") return [...nodes].sort((a,b) => collator.compare(a.name,b.name));
  const hasResults = nodes.some(node => latencies[node.id] != null || failed.has(node.id)); if (!hasResults) return nodes;
  return [...nodes].sort((a,b) => { const al = latencies[a.id], bl = latencies[b.id]; const ar = al != null ? 0 : failed.has(a.id) ? 1 : 2; const br = bl != null ? 0 : failed.has(b.id) ? 1 : 2; if (ar !== br) return ar - br; if (al != null && bl != null && al !== bl) return al - bl; return collator.compare(a.name,b.name); });
}
function countryFlag(code: string) { if (!/^[A-Z]{2}$/.test(code)) return ""; return String.fromCodePoint(...[...code].map(char => 0x1f1e6 + char.charCodeAt(0) - 65)); }
async function resolveGeo(node: NodeView): Promise<GeoPoint | null> {
  const key = `dot.geo.${node.host.toLowerCase()}:${node.name}`;
  try { const cached = JSON.parse(localStorage.getItem(key) || "null"); if (cached?.storedAt && Date.now() - cached.storedAt < GEO_TTL && cached.value) return cached.value; } catch { /* ignore */ }
  try { const value = await invoke<GeoPoint>("node_geo", { host: node.host, name: node.name }); localStorage.setItem(key, JSON.stringify({ storedAt: Date.now(), value })); return value; } catch { return null; }
}
