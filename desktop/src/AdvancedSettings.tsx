import { useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { AdvancedSettings, AppPreferences, GroupView, PingSettings } from "./types";

export const DEFAULT_ADVANCED: AdvancedSettings = {
  schema_version: 1, ping: { method: "proxy_get", url: "https://cp.cloudflare.com/generate_204", timeout_seconds: 5, attempts: 3, aggregation: "median", parallelism: 4, on_start: false, repeat_minutes: 0, selected_only: false },
  auto_connect: false, auto_node: false, auto_max_latency_ms: 0, auto_excluded: [], compact_nodes: false, show_latency: true, show_transport: true,
  dns: "1.1.1.1", bypass_lan: false, log_level: "warning", subscription_timeout_seconds: 20, subscription_user_agent: "",
};
export default function AdvancedSettingsPanel({ prefs, groups, onChange }: { prefs: AppPreferences; groups: GroupView[]; onChange: () => Promise<void> }) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const s = prefs.advanced;
  async function action(work: () => Promise<unknown>) {
    setBusy(true); setError(""); setNotice("");
    try { await work(); await onChange(); setNotice("Saved. DNS, routing and core logging changes apply after reconnect."); }
    catch (e) { setError(String(e)); } finally { setBusy(false); }
  }
  const save = (value: Partial<AdvancedSettings>) => action(() => invoke("set_advanced", { settings: { ...s, ...value } }));
  const ping = (value: Partial<PingSettings>) => save({ ping: { ...s.ping, ...value } });
  function download(name: string, data: unknown) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
    const a = document.createElement("a"); a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <div className="advanced-settings">
    <details className="panel"><summary>GENERAL</summary><Check label="Connect on app launch" checked={s.auto_connect} disabled={busy} change={auto_connect => save({ auto_connect })} />
      <p className="muted">Disconnect stops the tunnel. Close to tray is configured below. Windows network recovery and OS startup integration are unavailable in this version.</p></details>
    <details className="panel"><summary>PING & DIAGNOSTICS</summary>
      <Pick label="Method" value={s.ping.method} options={[["proxy_get", "Via Proxy GET"], ["proxy_head", "Via Proxy HEAD"], ["tcp", "TCP Ping"], ["icmp", "ICMP Ping"]]} disabled={busy} change={method => ping({ method: method as PingSettings["method"] })} />
      <p className="muted">GET transfers the complete response; HEAD measures response headers. HTTP 405 means HEAD is unsupported by the test URL. TCP only checks the server port and is unavailable for HY2. ICMP uses the Windows ICMP API; the server may block it. TCP/ICMP never confirm proxy health.</p>
      {s.ping.method.startsWith("proxy_") && <TextSetting label="Test URL (HTTP/HTTPS)" value={s.ping.url} disabled={busy} save={url => ping({ url })} />}
      <Pick label="Timeout (seconds)" value={s.ping.timeout_seconds} options={[3, 5, 10, 15]} disabled={busy} change={v => ping({ timeout_seconds: +v })} />
      <Pick label="Attempts" value={s.ping.attempts} options={[1, 2, 3, 5]} disabled={busy} change={v => ping({ attempts: +v })} />
      <Pick label="Aggregation" value={s.ping.aggregation} options={["minimum", "average", "median", "last"]} disabled={busy} change={v => ping({ aggregation: v as PingSettings["aggregation"] })} />
      <Pick label="Parallel tests" value={s.ping.parallelism} options={[1, 2, 4, 8]} disabled={busy} change={v => ping({ parallelism: +v })} />
      <Check label="Test on launch" checked={s.ping.on_start} disabled={busy} change={on_start => ping({ on_start })} />
      <Pick label="Repeat (minutes; 0 = off)" value={s.ping.repeat_minutes} options={[0, 1, 5, 15, 30, 60]} disabled={busy} change={v => ping({ repeat_minutes: +v })} />
      <Check label="Scheduled tests: selected node only" checked={s.ping.selected_only} disabled={busy} change={selected_only => ping({ selected_only })} />
      <p className="muted">Scheduled tests run while the app is running, when disconnected. While connected, test only the current tunnel. Config preparation, core startup and DNS resolution for direct TCP/ICMP are excluded from measured time. Each HTTP attempt opens a new connection. All attempts must succeed to confirm health.</p>
      <button className="quiet" onClick={() => invoke("cancel_diagnostics").catch(e => setError(String(e)))}>CANCEL TESTS</button>
      <button className="quiet" disabled={busy} onClick={() => action(() => invoke("clear_diagnostics"))}>CLEAR DIAGNOSTICS</button>
    </details>
    <details className="panel"><summary>DNS & ROUTING</summary>
      <TextSetting label="DNS resolver (IPv4/IPv6 address)" value={s.dns} disabled={busy} save={dns => save({ dns })} />
      <Check label="Bypass LAN / private addresses" checked={s.bypass_lan} disabled={busy} change={bypass_lan => save({ bypass_lan })} />
      <p className="muted">Applies after reconnect. Domain/IP rules, DoH/DoT presets and app split tunneling are not exposed on Windows.</p>
    </details>
    <details className="panel"><summary>SUBSCRIPTION REQUESTS</summary>
      <Pick label="Timeout (seconds)" value={s.subscription_timeout_seconds} options={[5, 10, 20, 30, 60]} disabled={busy} change={v => save({ subscription_timeout_seconds: +v })} />
      <TextSetting label="User-Agent (empty = dot default)" value={s.subscription_user_agent} disabled={busy} save={subscription_user_agent => save({ subscription_user_agent })} />
      <button className="quiet" disabled={busy} onClick={() => action(async () => { const results = await Promise.allSettled(groups.filter(g => g.remote).map(g => invoke("refresh_subscription", { groupId: g.id }))); const failures = results.filter(r => r.status === "rejected"); if (failures.length) throw new Error(`${failures.length} subscription refreshes failed; previous nodes retained. See subscription notices.`); })}>REFRESH ALL</button>
      <p className="muted">Startup and interval refresh are configured in subscription management below. Failed refreshes retain saved nodes. Selection follows the same node when it remains present.</p>
    </details>
    <details className="panel"><summary>NODES & AUTO</summary>
      <p className="muted">AUTO chooses the lowest latency proxy verified by GET/HEAD in the last 15 minutes. Manual selection disables AUTO. Current mode: {s.auto_node ? "AUTO" : "MANUAL"}.</p>
      <Pick label="Maximum latency (ms; 0 = unlimited)" value={s.auto_max_latency_ms} options={[0, 100, 300, 500, 1000]} disabled={busy} change={v => save({ auto_max_latency_ms: +v })} />
      <button className="quiet" disabled={busy} onClick={() => action(async () => { const selection = await invoke<{ group_id: string | null }>("selection"); if (!selection.group_id) throw new Error("Select a subscription first"); await invoke("auto_select", { groupId: selection.group_id }); })}>ACTIVATE AUTO</button>
      {s.auto_node && <button className="quiet" disabled={busy} onClick={() => save({ auto_node: false })}>MANUAL MODE</button>}
      <label>EXCLUDE FROM AUTO<select value="" disabled={busy} onChange={e => { if (e.target.value) save({ auto_excluded: [...s.auto_excluded, e.target.value] }); }}><option value="">Choose a node…</option>{groups.flatMap(g => g.nodes).filter((n, i, all) => all.findIndex(x => x.id === n.id) === i && !s.auto_excluded.includes(n.id)).map(n => <option key={n.id} value={n.id}>{n.name}</option>)}</select></label>
      {s.auto_excluded.map(id => <button className="quiet" key={id} disabled={busy} onClick={() => save({ auto_excluded: s.auto_excluded.filter(x => x !== id) })}>INCLUDE {groups.flatMap(g => g.nodes).find(n => n.id === id)?.name ?? "removed node"}</button>)}
    </details>
    <details className="panel"><summary>LIST APPEARANCE</summary>
      <Check label="Compact nodes" checked={s.compact_nodes} disabled={busy} change={compact_nodes => save({ compact_nodes })} />
      <Check label="Show latency" checked={s.show_latency} disabled={busy} change={show_latency => save({ show_latency })} />
      <Check label="Show protocol and transport" checked={s.show_transport} disabled={busy} change={show_transport => save({ show_transport })} />
    </details>
    <details className="panel"><summary>ADVANCED</summary>
      <Pick label="Core log level" value={s.log_level} options={["none", "error", "warning"]} disabled={busy} change={log_level => save({ log_level })} />
      <p className="muted">Verbose core logs are omitted to protect credentials. Export contains diagnostic metrics only; node URIs, addresses, credentials, errors and test URLs are omitted.</p>
      <button className="quiet" onClick={() => download("dot-settings-0.5.0.json", s)}>EXPORT SETTINGS</button>
      <label>IMPORT SETTINGS<input type="file" accept="application/json,.json" disabled={busy} onChange={e => { const file = e.target.files?.[0]; e.target.value = ""; if (file) action(async () => { if (file.size > 1_048_576) throw new Error("Settings file exceeds 1 MiB"); const settings = JSON.parse(await file.text()); await invoke("set_advanced", { settings }); }); }} /></label>
      <button className="quiet" onClick={() => download("dot-diagnostics.json", groups.flatMap(g => g.nodes).flatMap(n => n.diagnostic ? [{ method: n.diagnostic.method, latency_ms: n.diagnostic.latency_ms, tested_at_ms: n.diagnostic.tested_at_ms, success: n.diagnostic.success, samples: n.diagnostic.samples }] : []))}>EXPORT REDACTED DIAGNOSTICS</button>
      <button className="quiet" disabled={busy} onClick={() => { if (window.confirm("Reset advanced settings? Subscriptions and credentials are retained.")) save(DEFAULT_ADVANCED); }}>RESET SETTINGS</button>
    </details>
    {notice && <p role="status" className="muted">{notice}</p>}{error && <p role="alert" className="inline-error">{error}</p>}
  </div>;
}
function Pick({ label, value, options, disabled, change }: { label: string; value: string | number; options: (string | number | [string, string])[]; disabled: boolean; change: (s: string) => void }) { return <label>{label}<select value={value} disabled={disabled} onChange={e => change(e.target.value)}>{options.map(o => { const [v, text] = Array.isArray(o) ? o : [o, o]; return <option key={v} value={v}>{text}</option>; })}</select></label>; }
function Check({ label, checked, disabled, change }: { label: string; checked: boolean; disabled: boolean; change: (v: boolean) => void }) { return <label className="advanced-check"><input type="checkbox" checked={checked} disabled={disabled} onChange={e => change(e.target.checked)} />{label}</label>; }
function TextSetting({ label, value, disabled, save }: { label: string; value: string; disabled: boolean; save: (v: string) => void }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  return <form onSubmit={e => { e.preventDefault(); save(text.trim()); }}><label>{label}<input value={text} disabled={disabled} onChange={e => setText(e.target.value)} /></label><button className="quiet" disabled={disabled}>SAVE</button></form>;
}
