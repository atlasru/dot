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
  const [subscriptionBusy, setSubscriptionBusy] = useState(false);
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
    setSubscriptionBusy(true);
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
      setSubscriptionBusy(false);
    }
  }

  async function refresh() {
    if (!activeGroup) return;
    setSubscriptionBusy(true);
    setError("");
    try {
      await invoke("refresh_subscription", { groupId: activeGroup.id });
      await reloadGroups();
    } catch (e) {
      setError(String(e));
    } finally {
      setSubscriptionBusy(false);
    }
  }

  async function toggleVpn() {
    if (vpn.phase === "stopping") return;
    setError("");

    if (vpn.phase === "connected" || vpn.phase === "starting") {
      try {
        setVpn(await invoke<EngineSnapshot>("disconnect"));
      } catch (e) {
        setError(String(e));
      }
      return;
    }

    if (!activeGroup || !activeNode) {
      setError("select a node first");
      return;
    }

    // Publish the intended state immediately so a second click can cancel the
    // startup while the backend is validating Xray/TUN/connectivity.
    setVpn({ phase: "starting", node_name: activeNode.name, message: "starting" });
    try {
      setVpn(
        await invoke<EngineSnapshot>("connect", {
          groupId: activeGroup.id,
          nodeId: activeNode.id,
        }),
      );
    } catch (e) {
      setError(String(e));
      invoke<EngineSnapshot>("vpn_status").then(setVpn).catch(() => undefined);
    }
  }

  const vpnButtonLabel =
    vpn.phase === "stopping"
      ? "STOPPING"
      : vpn.phase === "connected" || vpn.phase === "starting"
        ? "DISCONNECT"
        : "CONNECT";

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
        <button
          className="connect"
          disabled={vpn.phase === "stopping" || (!activeNode && vpn.phase !== "connected" && vpn.phase !== "starting")}
          onClick={toggleVpn}
        >
          {vpnButtonLabel}
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
          <button className="quiet" disabled={subscriptionBusy} onClick={refresh}>refresh {activeGroup?.name}</button>
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
          <button className="quiet" disabled={subscriptionBusy || !name.trim() || !url.trim()} type="submit">add / update</button>
        </form>
      </section>

      {error && <div className="error">{error}</div>}
    </main>
  );
}
