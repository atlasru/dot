import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { AppPreferences, EngineSnapshot, GroupView, RefreshNotice } from "./types";

export default function SubscriptionManager({ groups, prefs, vpn, notices, onChange }: {
  groups: GroupView[]; prefs: AppPreferences; vpn: EngineSnapshot; notices: RefreshNotice[]; onChange: () => Promise<void>;
}) {
  const [mode, setMode] = useState<"url" | "text">("url");
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [text, setText] = useState("");
  const [edit, setEdit] = useState<{ id: string; name: string; url: string } | null>(null);
  const [removing, setRemoving] = useState<GroupView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function action(work: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError("");
    try { await work(); await onChange(); }
    catch (e) { setError(String(e)); }
    finally { setBusy(false); }
  }
  async function loadFile(file?: File) {
    if (!file) return;
    if (file.size > 4 * 1024 * 1024) { setError("Import file exceeds 4 MB"); return; }
    await action(async () => { setText(await file.text()); setMode("text"); });
  }
  return <>
    <section className="panel"><div className="panel-title">SUBSCRIPTIONS</div>
      <p className="muted">Changing a URL validates the new source before replacing nodes. Leave it empty to keep a local group.</p>
      <div className="subscription-list">{groups.map(group => {
        const notice = notices.find(n => n.group_id === group.id);
        return <div className="subscription-item" key={group.id}>
          <div className="subscription"><div className="grow"><strong>{group.name}</strong><small>{group.nodes.length} NODES · {group.remote ? "SUBSCRIPTION" : "LOCAL"}</small>
            <small>{group.updated_at_ms ? `Last success: ${new Date(group.updated_at_ms).toLocaleString()}` : "NOT UPDATED"}</small></div>
            {group.remote && <button className="segment" disabled={busy} onClick={() => action(async () => { await invoke("refresh_subscription", { groupId: group.id }); })}>↻</button>}
            <button className="segment" disabled={busy} onClick={() => action(async () => { const source = await invoke<string>("subscription_url", { groupId: group.id }); setEdit({ id: group.id, name: group.name, url: source }); setRemoving(null); })}>EDIT</button>
            <button className="segment" disabled={busy || !["offline", "error"].includes(vpn.phase)} onClick={() => { setRemoving(group); setEdit(null); }}>DELETE</button>
          </div>
          {notice?.error && <p className="inline-error">{notice.error} · Previous nodes retained</p>}
          {edit?.id === group.id && <form className="edit-form" onSubmit={e => { e.preventDefault(); action(async () => { await invoke("edit_subscription", { groupId: edit.id, name: edit.name, url: edit.url }); setEdit(null); }); }}>
            <label>NAME<input required value={edit.name} onChange={e => setEdit({ ...edit, name: e.target.value })} /></label>
            <label>SUBSCRIPTION URL<input type="url" value={edit.url} autoComplete="off" spellCheck={false} onChange={e => setEdit({ ...edit, url: e.target.value })} /></label>
            <div className="filter-row"><button className="quiet" disabled={busy || !edit.name.trim()}>SAVE</button><button type="button" className="segment" disabled={busy} onClick={() => setEdit(null)}>CANCEL</button></div>
          </form>}
          {removing?.id === group.id && <div className="delete-confirm" role="alert"><p>Delete “{group.name}” and its {group.nodes.length} nodes?</p>
            <button className="segment" disabled={busy} onClick={() => action(async () => { await invoke("remove_subscription", { groupId: group.id }); setRemoving(null); })}>DELETE GROUP</button>
            <button className="segment" disabled={busy} onClick={() => setRemoving(null)}>CANCEL</button></div>}
        </div>;
      })}</div>
      {!["offline", "error"].includes(vpn.phase) && <p className="muted">Disconnect to delete subscriptions.</p>}
      <form onSubmit={e => { e.preventDefault(); action(async () => {
        if (mode === "url") await invoke("add_subscription", { name, url });
        else await invoke("import_nodes", { name, text });
        setName(""); setUrl(""); setText("");
      }); }}>
        <div className="filter-row"><button type="button" className={mode === "url" ? "segment active" : "segment"} onClick={() => setMode("url")}>SUBSCRIPTION URL</button><button type="button" className={mode === "text" ? "segment active" : "segment"} onClick={() => setMode("text")}>IMPORT LINKS</button></div>
        <label>GROUP NAME<input required value={name} onChange={e => setName(e.target.value)} placeholder="My servers" /></label>
        {mode === "url" ? <label>URL<input required type="url" autoComplete="off" spellCheck={false} value={url} onChange={e => setUrl(e.target.value)} placeholder="https://…" /></label> : <>
          <label>VLESS LINKS OR BASE64<textarea required value={text} onChange={e => setText(e.target.value)} placeholder="Paste one or more vless:// links" spellCheck={false} /></label>
          <label>IMPORT FROM FILE<input type="file" accept=".txt,.conf,.list" disabled={busy} onChange={e => { loadFile(e.target.files?.[0]); e.target.value = ""; }} /></label>
        </>}
        <button className="quiet" disabled={busy || !name.trim() || !(mode === "url" ? url.trim() : text.trim())}>{busy ? "WORKING…" : mode === "url" ? "ADD SUBSCRIPTION" : "IMPORT NODES"}</button>
      </form>
      {error && <p className="inline-error" role="alert">{error}</p>}
    </section>
    <section className="panel"><div className="panel-title">AUTOMATIC REFRESH</div>
      <label className="checkbox-label"><input type="checkbox" disabled={busy} checked={prefs.refresh_on_start} onChange={e => { const enabled = e.target.checked; action(async () => { await invoke("set_refresh_policy", { onStart: enabled, hours: prefs.refresh_interval_hours }); }); }} /> Refresh subscriptions when dot. starts</label>
      <label htmlFor="refresh-interval">INTERVAL</label><select id="refresh-interval" disabled={busy} value={prefs.refresh_interval_hours} onChange={e => { const hours = Number(e.target.value); action(async () => { await invoke("set_refresh_policy", { onStart: prefs.refresh_on_start, hours }); }); }}>
        <option value={0}>OFF</option>{[1, 6, 12, 24].map(h => <option key={h} value={h}>EVERY {h} {h === 1 ? "HOUR" : "HOURS"}</option>)}
      </select>
      <p className="muted">Works while dot. is running, including in the tray. Local imports are skipped. Existing VPN sessions are not restarted.</p>
    </section>
  </>;
}
