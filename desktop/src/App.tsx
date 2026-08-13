import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { AppPreferences, AppTheme, EngineSnapshot, GroupView, NodeView, SelectionView, TrafficSnapshot } from "./types";

type Page = "main" | "settings";

const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, message: null };
const ZERO_TRAFFIC: TrafficSnapshot = {
  download_bytes_per_second: 0,
  upload_bytes_per_second: 0,
  session_download_bytes: 0,
  session_upload_bytes: 0,
  connected_seconds: 0,
};
const DEFAULT_PREFS: AppPreferences = { theme: "amoled", close_to_tray: true };

export default function App() {
  const [page, setPage] = useState<Page>("main");
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [selection, setSelection] = useState<SelectionView>({ group_id: null, node_id: null });
  const [viewGroupId, setViewGroupId] = useState<string | null>(null);
  const [groupMenuOpen, setGroupMenuOpen] = useState(false);
  const [vpn, setVpn] = useState<EngineSnapshot>(OFFLINE);
  const [traffic, setTraffic] = useState<TrafficSnapshot>(ZERO_TRAFFIC);
  const [prefs, setPrefs] = useState<AppPreferences>(DEFAULT_PREFS);
  const [name, setName] = useState("vpn1");
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const activeGroup = useMemo(
    () => groups.find(group => group.id === selection.group_id) ?? groups[0],
    [groups, selection.group_id],
  );
  const activeNode = useMemo(
    () => activeGroup?.nodes.find(node => node.id === selection.node_id) ?? activeGroup?.nodes[0],
    [activeGroup, selection.node_id],
  );
  const visibleGroup = useMemo(
    () => groups.find(group => group.id === viewGroupId) ?? activeGroup ?? groups[0],
    [groups, viewGroupId, activeGroup],
  );

  async function reloadData() {
    const [nextGroups, nextSelection, nextPrefs] = await Promise.all([
      invoke<GroupView[]>("list_groups"),
      invoke<SelectionView>("selection"),
      invoke<AppPreferences>("preferences"),
    ]);
    setGroups(nextGroups);
    setSelection(nextSelection);
    setPrefs(nextPrefs);
    setViewGroupId(current => current ?? nextSelection.group_id ?? nextGroups[0]?.id ?? null);
  }

  useEffect(() => {
    reloadData().catch(reason => setError(String(reason)));

    const tick = () => Promise.all([
      invoke<EngineSnapshot>("vpn_status").then(setVpn),
      invoke<TrafficSnapshot>("traffic_status").then(setTraffic),
    ]).catch(() => undefined);

    tick();
    const timer = window.setInterval(tick, 1000);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (groupMenuOpen) setGroupMenuOpen(false);
      else if (page === "settings") setPage("main");
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [page, groupMenuOpen]);

  useEffect(() => {
    document.documentElement.dataset.theme = prefs.theme;
  }, [prefs.theme]);

  async function chooseNode(groupId: string, nodeId: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    setViewGroupId(groupId);

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
    } catch (reason) {
      setError(String(reason));
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
    } catch (reason) {
      setError(String(reason));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    }
  }

  async function refresh(group = visibleGroup) {
    if (!group) return;
    setBusy(true);
    setError("");
    try {
      await invoke("refresh_subscription", { groupId: group.id });
      await reloadData();
    } catch (reason) {
      setError(String(reason));
    } finally {
      setBusy(false);
    }
  }

  async function addSubscription(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const created = await invoke<GroupView>("add_subscription", { name, url });
      setUrl("");
      await reloadData();
      setViewGroupId(created.id);
    } catch (reason) {
      setError(String(reason));
    } finally {
      setBusy(false);
    }
  }

  async function changeTheme(theme: AppTheme) {
    try {
      setPrefs(await invoke<AppPreferences>("set_theme", { theme }));
    } catch (reason) {
      setError(String(reason));
    }
  }

  async function changeCloseToTray(enabled: boolean) {
    try {
      setPrefs(await invoke<AppPreferences>("set_close_to_tray", { enabled }));
    } catch (reason) {
      setError(String(reason));
    }
  }

  return (
    <main className="shell">
      {page === "main" ? (
        <div className="main-page">
          <header className="topbar">
            <h1>dot.</h1>
            <div className="topbar-actions">
              <small>v0.2.0-alpha.1</small>
              <button className="icon-button" onClick={() => setPage("settings")} aria-label="settings">⚙</button>
            </div>
          </header>

          <div className="dashboard-layout">
            <section className="connection-pane">
              <PixelOrb phase={vpn.phase} onClick={toggleVpn} disabled={busy || vpn.phase === "stopping"} />
              <strong className={`status status-${vpn.phase}`}>{connectionLabel(vpn.phase)}</strong>
              <small className="current-node">
                {vpn.node_name ?? activeNode?.name ?? "select a node below"}
              </small>
              <small className="connection-hint">
                {vpn.phase === "offline" ? "tap orb to connect" : vpn.message ?? "VLESS · Windows TUN"}
              </small>

              <div className="traffic-grid">
                <TrafficCell
                  label="download"
                  arrow="↓"
                  value={formatRate(traffic.download_bytes_per_second)}
                  total={formatBytes(traffic.session_download_bytes)}
                />
                <TrafficCell
                  label="upload"
                  arrow="↑"
                  value={formatRate(traffic.upload_bytes_per_second)}
                  total={formatBytes(traffic.session_upload_bytes)}
                  align="right"
                />
              </div>
              <div className="session-line">
                <span>SESSION</span>
                <strong>{formatTime(traffic.connected_seconds)}</strong>
              </div>
            </section>

            <section className="nodes-pane">
              <NodeToolbar
                groups={groups}
                group={visibleGroup}
                menuOpen={groupMenuOpen}
                busy={busy}
                onToggleMenu={() => setGroupMenuOpen(value => !value)}
                onChooseGroup={groupId => {
                  setViewGroupId(groupId);
                  setGroupMenuOpen(false);
                }}
                onRefresh={() => refresh(visibleGroup)}
                onOpenSettings={() => {
                  setGroupMenuOpen(false);
                  setPage("settings");
                }}
              />

              <div className="node-list" role="list">
                {!visibleGroup ? (
                  <EmptyNodes
                    title="no subscriptions"
                    detail="add a subscription in settings."
                    action="OPEN SETTINGS"
                    onAction={() => setPage("settings")}
                  />
                ) : visibleGroup.nodes.length === 0 ? (
                  <EmptyNodes
                    title={`no nodes in ${visibleGroup.name}`}
                    detail="refresh this subscription or check its URL."
                    action="REFRESH"
                    onAction={() => refresh(visibleGroup)}
                  />
                ) : (
                  visibleGroup.nodes.map(node => (
                    <NodeRow
                      key={node.id}
                      node={node}
                      selected={visibleGroup.id === activeGroup?.id && node.id === activeNode?.id}
                      live={vpn.phase === "connected" && visibleGroup.id === activeGroup?.id && node.id === activeNode?.id}
                      switching={busy && visibleGroup.id === activeGroup?.id && node.id === activeNode?.id && vpn.phase === "stopping"}
                      disabled={busy}
                      onClick={() => chooseNode(visibleGroup.id, node.id)}
                    />
                  ))
                )}
              </div>
            </section>
          </div>
        </div>
      ) : (
        <div className="settings-page">
          <header className="topbar settings-topbar">
            <div>
              <h1>settings.</h1>
              <small>desktop preferences</small>
            </div>
            <button className="icon-button" onClick={() => setPage("main")} aria-label="close settings">×</button>
          </header>

          <SettingsSection title="appearance">
            <div className="theme-row">
              {(["amoled", "graphite", "matrix"] as AppTheme[]).map(theme => (
                <button
                  key={theme}
                  className={prefs.theme === theme ? "choice selected" : "choice"}
                  onClick={() => changeTheme(theme)}
                >
                  {theme}
                </button>
              ))}
            </div>
          </SettingsSection>

          <SettingsSection title="window">
            <Toggle
              label="close to tray"
              detail="keep dot. and the VPN alive after closing the window"
              checked={prefs.close_to_tray}
              onChange={changeCloseToTray}
            />
          </SettingsSection>

          <SettingsSection title="subscriptions">
            {groups.map(group => (
              <div className="subscription" key={group.id}>
                <button
                  className="subscription-main"
                  onClick={() => {
                    setViewGroupId(group.id);
                    setPage("main");
                  }}
                >
                  <strong>{group.name}</strong>
                  <small>{group.nodes.length} nodes</small>
                </button>
                <button className="tiny" disabled={busy} onClick={() => refresh(group)}>↻</button>
              </div>
            ))}
            <form onSubmit={addSubscription}>
              <label>
                group
                <input value={name} onChange={event => setName(event.target.value)} placeholder="vpn1" />
              </label>
              <label>
                subscription url
                <input value={url} onChange={event => setUrl(event.target.value)} type="url" placeholder="https://…" />
              </label>
              <button className="quiet" disabled={busy || !name.trim() || !url.trim()} type="submit">
                add subscription
              </button>
            </form>
          </SettingsSection>

          <SettingsSection title="about">
            <div className="about">
              <span>dot. Desktop</span><small>0.2.0-alpha.1</small>
              <span>Xray Core</span><small>v26.7.28</small>
              <span>protocol</span><small>VLESS / REALITY</small>
            </div>
          </SettingsSection>
        </div>
      )}

      {error && <button className="error" onClick={() => setError("")}>{error}</button>}
    </main>
  );
}

function PixelOrb({ phase, onClick, disabled }: { phase: EngineSnapshot["phase"]; onClick: () => void; disabled: boolean }) {
  const pattern = [
    "00011111000",
    "00110001100",
    "01100000110",
    "11001010011",
    "10010101001",
    "10001010001",
    "10010101001",
    "11001010011",
    "01100000110",
    "00110001100",
    "00011111000",
  ];

  const active = phase === "connected";
  const pending = phase === "starting" || phase === "stopping";
  const failed = phase === "error";

  return (
    <button
      className={`orb ${active ? "active" : ""} ${pending ? "pending" : ""} ${failed ? "failed" : ""}`}
      onClick={onClick}
      disabled={disabled}
      aria-label={active ? "disconnect VPN" : "connect VPN"}
    >
      <span className="orb-grid" aria-hidden="true">
        {pattern.flatMap((row, rowIndex) => row.split("").map((cell, columnIndex) => {
          const cross = failed && (rowIndex === columnIndex || rowIndex + columnIndex === 10);
          return (
            <i
              key={`${rowIndex}-${columnIndex}`}
              className={`${cell === "1" ? "lit" : ""} ${cross ? "error-pixel" : ""}`}
            />
          );
        }))}
      </span>
      <span className="orb-dot" aria-hidden="true" />
    </button>
  );
}

function NodeToolbar({
  groups,
  group,
  menuOpen,
  busy,
  onToggleMenu,
  onChooseGroup,
  onRefresh,
  onOpenSettings,
}: {
  groups: GroupView[];
  group: GroupView | undefined;
  menuOpen: boolean;
  busy: boolean;
  onToggleMenu: () => void;
  onChooseGroup: (groupId: string) => void;
  onRefresh: () => void;
  onOpenSettings: () => void;
}) {
  return (
    <div className="node-toolbar">
      <div className="group-picker">
        <button className="group-button" onClick={onToggleMenu} aria-expanded={menuOpen}>
          <strong>{group?.name ?? "subscriptions"}</strong>
          <span>⌄</span>
        </button>
        {menuOpen && (
          <div className="group-menu">
            <div className="group-menu-title">subscriptions.</div>
            {groups.map(item => (
              <button
                key={item.id}
                className={item.id === group?.id ? "group-menu-item selected" : "group-menu-item"}
                onClick={() => onChooseGroup(item.id)}
              >
                <span>{item.name}</span>
                <small>{item.nodes.length}</small>
              </button>
            ))}
            <button className="group-menu-item add" onClick={onOpenSettings}>+ add / manage</button>
          </div>
        )}
      </div>

      <div className="node-tools">
        <small>{group?.nodes.length ?? 0} nodes</small>
        <button className="refresh-button" disabled={!group || busy} onClick={onRefresh} aria-label="refresh subscription">↻</button>
      </div>
    </div>
  );
}

function NodeRow({
  node,
  selected,
  live,
  switching,
  disabled,
  onClick,
}: {
  node: NodeView;
  selected: boolean;
  live: boolean;
  switching: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={`node-row ${selected ? "selected" : ""} ${live ? "live" : ""}`}
      disabled={disabled}
      onClick={onClick}
      role="listitem"
    >
      <span className="node-marker" />
      <span className="node-copy">
        <span className="node-name">{node.name}</span>
        <small>{node.security} · {node.transport} · {node.host}:{node.port}</small>
      </span>
      <span className="node-state">
        {switching ? <em>SWITCHING</em> : live ? <em>LIVE</em> : selected ? <em>SELECTED</em> : <span>›</span>}
      </span>
    </button>
  );
}

function EmptyNodes({ title, detail, action, onAction }: { title: string; detail: string; action: string; onAction: () => void }) {
  return (
    <div className="empty-nodes">
      <strong>{title}</strong>
      <small>{detail}</small>
      <button className="quiet" onClick={onAction}>{action}</button>
    </div>
  );
}

function TrafficCell({
  label,
  arrow,
  value,
  total,
  align = "left",
}: {
  label: string;
  arrow: string;
  value: string;
  total: string;
  align?: "left" | "right";
}) {
  return (
    <div className={`traffic-cell ${align === "right" ? "align-right" : ""}`}>
      <small>{label}</small>
      <strong>{arrow} {value}</strong>
      <em>{total}</em>
    </div>
  );
}

function SettingsSection({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="panel"><div className="panel-title">{title}.</div>{children}</section>;
}

function Toggle({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (value: boolean) => void }) {
  return (
    <button className="toggle" onClick={() => onChange(!checked)}>
      <div><strong>{label}</strong><small>{detail}</small></div>
      <span className={checked ? "switch on" : "switch"}><i /></span>
    </button>
  );
}

function connectionLabel(phase: EngineSnapshot["phase"]) {
  switch (phase) {
    case "starting": return "connecting";
    case "stopping": return "disconnecting";
    default: return phase;
  }
}

function formatRate(value: number) {
  return `${formatBytes(value)}/s`;
}

function formatBytes(value: number) {
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(0)} KB`;
  return `${value} B`;
}

function formatTime(value: number) {
  const hours = Math.floor(value / 3600);
  const minutes = Math.floor((value % 3600) / 60);
  const seconds = value % 60;
  return [hours, minutes, seconds].map(part => String(part).padStart(2, "0")).join(":");
}
