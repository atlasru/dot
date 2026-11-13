import React, { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import WorldMap, { type WorldMapMarker } from "./WorldMap";
import type { DiagnosticBatch, EngineSnapshot, GroupView, NodeView, NodeSortMode, RefreshNotice } from "./types";

type GeoPoint = { latitude: number; longitude: number; country_code: string; country: string; source: string };
const geoCache = new Map<string, GeoPoint>();

export default function NodeBrowser({ groups, group, activeGroup, activeNode, vpn, busy, testingNodeId, diagnosticsBusy, batch,
  onBrowseGroup, onChooseNode, onRefresh, onTestNode, onTestGroup, onCancelDiagnostics, onSort, onChange, notices }: {
  groups: GroupView[]; group?: GroupView; activeGroup?: GroupView; activeNode?: NodeView; vpn: EngineSnapshot;
  busy: boolean; testingNodeId: string | null; diagnosticsBusy: boolean; batch: DiagnosticBatch | null; notices: RefreshNotice[];
  onBrowseGroup: (id: string) => void; onChooseNode: (groupId: string, nodeId: string) => void;
  onRefresh: () => void; onTestNode: (group: GroupView, node: NodeView) => void; onTestGroup: () => void;
  onCancelDiagnostics: () => void;
  onSort: (group: GroupView, mode: NodeSortMode) => void; onChange: () => Promise<void>;
}) {
  const [query, setQuery] = useState("");
  const [favorites, setFavorites] = useState(false);
  const [map, setMap] = useState(false);
  const [country, setCountry] = useState<string | null>(null);
  const [geo, setGeo] = useState<Record<string, GeoPoint>>({});
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const nodesKey = JSON.stringify(group?.nodes.map(n => [n.id, n.host, n.name]).sort(([left], [right]) => left.localeCompare(right)) ?? []);
  useEffect(() => { setCountry(null); }, [group?.id, map]);
  useEffect(() => {
    if (!map) return;
    let cancelled = false;
    setGeo({});
    setLocating(true);
    const nodes: [string, string, string][] = JSON.parse(nodesKey);
    let cursor = 0;
    const found: Record<string, GeoPoint> = {};
    async function worker() {
      while (!cancelled && cursor < nodes.length) {
        const [id, host, name] = nodes[cursor++];
        const key = JSON.stringify([host, name]);
        try {
          const point = geoCache.get(key) ?? await invoke<GeoPoint>("node_geo", { host, name });
          if (cancelled) return;
          if (geoCache.size > 2000) geoCache.clear();
          geoCache.set(key, point);
          found[id] = point;
          setGeo({ ...found });
        } catch { /* Unlocated nodes remain available in the list. */ }
      }
    }
    Promise.all(Array.from({ length: Math.min(4, nodes.length) }, worker)).finally(() => { if (!cancelled) setLocating(false); });
    return () => { cancelled = true; };
  }, [map, nodesKey]);
  const searched = useMemo(() => (group?.nodes ?? []).filter(n => (!favorites || n.favorite)
    && `${n.name} ${n.host} ${n.port} ${n.security} ${n.transport}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())), [group, query, favorites]);
  const filtered = searched.filter(n => !map || !country || geo[n.id]?.country_code === country);
  const markers = useMemo(() => {
    const result = new Map<string, WorldMapMarker>();
    for (const node of searched) {
      const point = geo[node.id];
      if (!point) continue;
      const marker = result.get(point.country_code) ?? { countryCode: point.country_code, latitude: point.latitude, longitude: point.longitude, nodeCount: 0, active: false };
      marker.nodeCount++;
      marker.active ||= vpn.phase === "connected" && vpn.node_id === node.id;
      result.set(point.country_code, marker);
    }
    return [...result.values()];
  }, [searched, geo, vpn]);
  const notice = notices.find(n => n.group_id === group?.id);
  const groupBatch = group && batch?.group_id === group.id ? batch : null;
  const batchActive = batch?.phase === "running" || batch?.phase === "cancelling";
  const pendingNodes = useMemo(() => new Set(groupBatch?.pending_node_ids), [groupBatch]);
  async function favorite(node: NodeView) {
    setSaving(true); setError("");
    try { await invoke("set_favorite", { nodeId: node.id, enabled: !node.favorite }); await onChange(); }
    catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  }
  return <div className="nodes-view">
    <div className="workspace-head"><div><div className="view-kicker">NODES</div><h2>servers.</h2></div>
      <div className="workspace-actions">
        <button className="quiet compact-action" disabled={!group?.remote || busy} onClick={onRefresh}>↻ REFRESH</button>
        <button className="quiet compact-action" disabled={!group?.nodes.length || busy || diagnosticsBusy} onClick={onTestGroup}>TEST ALL</button>
      </div>
    </div>
    {batch && <div className="test-summary">
      <span role="status">{!groupBatch && `${groups.find(item => item.id === batch.group_id)?.name ?? "SUBSCRIPTION"} · `}
        {{ running: "TESTING", cancelling: "CANCELLING", completed: "COMPLETE", cancelled: "CANCELLED", failed: "INTERRUPTED" }[batch.phase]}
        { ` · ${batch.checked}/${batch.total} CHECKED · ${batch.succeeded} OK · ${batch.failed} FAILED · ${batch.unavailable} UNAVAILABLE` }
      </span>
      {batchActive && batch.total > 0 && <progress aria-label="Nodes checked" value={batch.checked} max={batch.total} />}
      {batchActive && <button className="quiet compact-action" disabled={batch.phase === "cancelling"} onClick={onCancelDiagnostics}>CANCEL</button>}
    </div>}
    <div className="nodes-toolbar">
      <label className="grow">SUBSCRIPTION<select value={group?.id ?? ""} onChange={e => onBrowseGroup(e.target.value)}>
        {!groups.length && <option value="">NO SUBSCRIPTIONS</option>}
        {groups.map(g => <option key={g.id} value={g.id}>{g.name} · {g.nodes.length}</option>)}
      </select></label>
      {group && <label>SORT<select value={group.sort_mode} disabled={busy} onChange={e => onSort(group, e.target.value as NodeSortMode)}>
        <option value="origin">ORIGINAL</option><option value="delay">DELAY</option><option value="name">NAME</option>
      </select></label>}
    </div>
    <div className="filter-row">
      <input className="grow" aria-label="Search nodes" placeholder="Search name, host or protocol" value={query} onChange={e => setQuery(e.target.value)} />
      <button className={favorites ? "segment active" : "segment"} aria-pressed={favorites} onClick={() => setFavorites(!favorites)}>★ FAVORITES</button>
      <button className={map ? "segment active" : "segment"} aria-pressed={map} onClick={() => setMap(!map)}>{map ? "LIST" : "MAP"}</button>
    </div>
    {notice?.error && <p className="inline-error" role="alert">Refresh failed: {notice.error}. Previous nodes retained.</p>}
    {notice?.result && <details className="refresh-details"><summary>Last update: {notice.result.added.length} added · {notice.result.edited.length} edited · {notice.result.deleted.length} removed</summary>
      {notice.result.selected_node_removed && <p>Selected node was removed. Next connection uses the current selection. Existing VPN sessions stay connected until you disconnect.</p>}
      {notice.result.added.map(n => <p key={`a${n.id}`}>+ {n.name}</p>)}
      {notice.result.deleted.map(n => <p key={`d${n.id}`}>− {n.name}</p>)}
      {notice.result.edited.map(n => <p key={`e${n.before.id}`}>{n.before.name} → {n.after.name}: {n.changed_fields.join(", ")}</p>)}
    </details>}
    {map && <div className="map-panel">
      <p className="muted">Map uses external GeoIP services. Country-name fallback is approximate.</p>
      <WorldMap markers={markers} selectedCountry={country} onMarkerClick={code => setCountry(country === code ? null : code)} />
      <div className="filter-row"><small>{locating ? "LOCATING…" : `${Object.keys(geo).length} / ${group?.nodes.length ?? 0} LOCATED`}</small>
        {country && <button className="segment" onClick={() => setCountry(null)}>CLEAR {country}</button>}</div>
    </div>}
    {error && <p className="inline-error" role="alert">{error}</p>}
    <div className="nodes-list large">
      {!filtered.length && <div className="empty-list"><strong>{groups.length ? "NO MATCHING NODES" : "NO NODES"}</strong><span>{groups.length ? "CHANGE SEARCH OR FILTERS." : "ADD A SUBSCRIPTION OR IMPORT LINKS IN SETTINGS."}</span></div>}
      {filtered.map(node => {
        const selected = group?.id === activeGroup?.id && node.id === activeNode?.id;
        const running = vpn.phase === "connected" && vpn.node_id === node.id;
        const testing = testingNodeId === node.id || (batchActive && pendingNodes.has(node.id));
        const skipped = !testing && pendingNodes.has(node.id) && !node.diagnostic;
        const unavailable = node.diagnostic !== null && !node.diagnostic.success && !node.latency_failed;
        return <div className={`node-row-card ${selected ? "selected" : ""}`} key={node.id}>
          <button className="favorite-button" disabled={saving} aria-label={`${node.favorite ? "Remove" : "Add"} ${node.name} ${node.favorite ? "from" : "to"} favorites`} aria-pressed={node.favorite} onClick={() => favorite(node)}>{node.favorite ? "★" : "☆"}</button>
          <button className="node-main" disabled={busy || !!testingNodeId || vpn.phase === "stopping"} onClick={() => group && onChooseNode(group.id, node.id)}>
            <span className="node-copy"><span className="node-title"><strong>{node.name}</strong>{running && <em>LIVE</em>}</span>
            <small className="node-transport">{node.protocol} · {node.security} · {node.transport} · {node.host}:{node.port}{map && geo[node.id] ? ` · ${geo[node.id].country}${geo[node.id].source === "name_fallback" ? " (approx.)" : ""}` : ""}</small></span>
          </button>
          <button title={node.diagnostic ? `${node.diagnostic.method} · ${new Date(node.diagnostic.tested_at_ms).toLocaleString()} · ${node.diagnostic.samples.length}/${node.diagnostic.settings.attempts} attempts${node.diagnostic.error ? ` · ${node.diagnostic.error}` : ""}` : "No diagnostics for this method"} className={`node-test ${node.latency_failed && !testing ? "failed" : ""} ${groupBatch ? "batch-result" : ""}`} aria-label={`Test ${node.name}${node.diagnostic?.error ? ` · ${node.diagnostic.error}` : ""}`} disabled={busy || diagnosticsBusy || ["starting", "stopping"].includes(vpn.phase)} onClick={() => group && onTestNode(group, node)}>
            {testing ? "…" : skipped ? "SKIPPED" : unavailable ? "UNAVAILABLE" : node.latency_failed ? "FAIL" : node.latency_ms !== null ? `${node.latency_ms} ms` : "TEST"}
          </button>
        </div>;
      })}
    </div>
  </div>;
}
