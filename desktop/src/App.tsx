import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { EngineSnapshot, GroupView } from "./types";

const OFFLINE: EngineSnapshot = { phase: "offline", node_name: null, message: null };

export default function App() {
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [groupId, setGroupId] = useState<string>("");
  const [nodeId, setNodeId] = useState<string>("");
  const [vpn, setVpn] = useState<EngineSnapshot>(OFFLINE);
  const [name, setName] = useState("vpn1");
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>("");

  const activeGroup = useMemo(
    () => groups.find((group) => group.id === groupId) ?? groups[0],
    [groups, groupId],
  );
  const activeNode = useMemo(
    () => activeGroup?.nodes.find((node) => node.id === nodeId) ?? activeGroup?.nodes[0],
    [activeGroup, nodeId],
  );

  async function reloadGroups() {
    const next = await invoke<GroupView[]>("list_groups");
    setGroups(next);
    if (!groupId && next[0]) setGroupId(next[0].id);
  }

  useEffect(() => {
    reloadGroups().catch((e) => setError(String(e)));
    invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    const timer = window.setInterval(() => {
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (activeGroup && !activeGroup.nodes.some((node) => node.id === nodeId)) {
      setNodeId(activeGroup.nodes[0]?.id ?? "");
    }
  }, [activeGroup, nodeId]);

  async function addSubscription(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const group = await invoke<GroupView>("add_subscription", { name, url });
      await reloadGroups();
      setGroupId(group.id);
      setNodeId(group.nodes[0]?.id ?? "");
      setUrl("");
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function refresh() {
    if (!activeGroup) return;
    setBusy(true);
    setError("");
    try {
      await invoke("refresh_subscription", { groupId: activeGroup.id });
      await reloadGroups();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleVpn() {
    setBusy(true);
    setError("");
    try {
      if (vpn.phase === "connected" || vpn.phase === "starting") {
        setVpn(await invoke<EngineSnapshot>("disconnect"));
      } else {
        if (!activeGroup || !activeNode) throw new Error("select a node first");
        setVpn(
          await invoke<EngineSnapshot>("connect", {
            groupId: activeGroup.id,
            nodeId: activeNode.id,
          }),
        );
      }
    } catch (e) {
      setError(String(e));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="shell">
      <header>
        <div>
          <h1>dot.</h1>
          <p>desktop</p>
        </div>
        <span className={`phase phase-${vpn.phase}`}>{vpn.phase}</span>
      </header>

      <section className="hero">
        <div className={`pixel-shield ${vpn.phase === "connected" ? "active" : ""}`} aria-hidden="true">
          <i /><i /><i /><i /><i /><i /><i /><i /><i />
        </div>
        <strong>{vpn.node_name ?? activeNode?.name ?? "no node selected"}</strong>
        <small>{vpn.message ?? (activeNode ? `${activeNode.security} · ${activeNode.transport}` : "add a subscription")}</small>
        <button className="connect" disabled={busy || !activeNode} onClick={toggleVpn}>
          {vpn.phase === "connected" || vpn.phase === "starting" ? "DISCONNECT" : "CONNECT"}
        </button>
      </section>

      {groups.length > 0 && (
        <section className="panel">
          <div className="panel-title">
            <span>nodes.</span>
            <select value={activeGroup?.id ?? ""} onChange={(e) => setGroupId(e.target.value)}>
              {groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}
            </select>
          </div>
          <div className="nodes">
            {activeGroup?.nodes.map((node) => (
              <button
                key={node.id}
                className={node.id === activeNode?.id ? "node selected" : "node"}
                onClick={() => setNodeId(node.id)}
              >
                <span>{node.name}</span>
                <small>{node.security} · {node.transport}</small>
              </button>
            ))}
          </div>
          <button className="quiet" disabled={busy} onClick={refresh}>refresh {activeGroup?.name}</button>
        </section>
      )}

      <section className="panel">
        <div className="panel-title"><span>subscriptions.</span></div>
        <form onSubmit={addSubscription}>
          <label>
            group
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="vpn1" />
          </label>
          <label>
            url
            <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…" type="url" />
          </label>
          <button className="quiet" disabled={busy || !name.trim() || !url.trim()} type="submit">add / update</button>
        </form>
      </section>

      {error && <div className="error">{error}</div>}
    </main>
  );
}
