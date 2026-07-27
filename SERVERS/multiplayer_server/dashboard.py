"""Admin dashboard for Michi's Adventure multiplayer server.

Serves a password-protected HTTP page on a configurable LAN port (default 8888).
Runs inside the same asyncio event loop as the game server.

Config keys (mp_config.json):
  dashboard_port  — HTTP port (default 8888)
  admin_password  — shared password; empty string disables the dashboard
  saves_db_path   — path to save_server/saves.db for username lookup
                    (empty = auto-detect ../save_server/saves.db)
"""
from __future__ import annotations

import asyncio
import datetime
import hmac
import json
import logging
import secrets
import sqlite3
import time
import urllib.parse
from pathlib import Path
from typing import Optional

log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
MAX_BODY = 65_536


_DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Michi Admin</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, sans-serif; background: #0f0f13; color: #e0e0e0; min-height: 100vh; }
  header { display: flex; align-items: center; justify-content: space-between;
           padding: 14px 24px; background: #1a1a24; border-bottom: 1px solid #2d2d3d; }
  header h1 { font-size: 1.2rem; letter-spacing: .05em; color: #a78bfa; }
  .logout-btn { background: transparent; color: #888; border: 1px solid #444;
                padding: 5px 14px; border-radius: 6px; cursor: pointer; font-size: .85rem; }
  .logout-btn:hover { color: #e0e0e0; border-color: #888; }
  #status-bar { padding: 6px 24px; font-size: .8rem; color: #666; background: #0f0f13; }
  #players { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
             gap: 16px; padding: 24px; }
  .card { background: #1a1a24; border: 1px solid #2d2d3d; border-radius: 10px;
          padding: 16px; display: flex; flex-direction: column; gap: 10px; }
  .card-header { display: flex; align-items: center; gap: 10px; }
  .avatar { width: 38px; height: 38px; border-radius: 50%; background: #2d2d3d;
            display: flex; align-items: center; justify-content: center;
            font-size: 1.1rem; font-weight: bold; color: #a78bfa; flex-shrink: 0; }
  .card-name { font-weight: 600; font-size: 1rem; }
  .card-name.flagged { color: #f87171; }
  .card.flagged { border-color: #7f1d1d; }
  .flag-banner { font-size: .75rem; color: #f87171; background: #2a1414;
                 border: 1px solid #5c2626; border-radius: 6px; padding: 6px 8px; }
  .flag-banner ul { margin: 4px 0 0 16px; }
  .card-class { font-size: .78rem; color: #888; }
  .card-map  { font-size: .75rem; color: #666; }
  .btn-row { display: flex; gap: 6px; flex-wrap: wrap; }
  .btn { border: none; border-radius: 6px; padding: 5px 12px; font-size: .8rem;
         cursor: pointer; font-weight: 500; transition: opacity .15s; }
  .btn:hover { opacity: .8; }
  .btn-info     { background: #3b3b5c; color: #c4b5fd; }
  .btn-kick     { background: #3b2020; color: #fca5a5; }
  .btn-ban      { background: #3b1a1a; color: #f87171; }
  .btn-tp       { background: #1a2d3b; color: #7dd3fc; }
  .empty { color: #555; text-align: center; padding: 60px 24px; width: 100%; grid-column: 1/-1; }

  /* Modal */
  .overlay { position: fixed; inset: 0; background: rgba(0,0,0,.65); display: flex;
             align-items: center; justify-content: center; z-index: 100; }
  .overlay[hidden] { display: none; }
  .modal { background: #1a1a24; border: 1px solid #3d3d5c; border-radius: 12px;
           width: min(540px, 95vw); max-height: 88vh; overflow-y: auto; padding: 24px; }
  .modal h2 { font-size: 1.1rem; margin-bottom: 16px; color: #a78bfa; }
  .modal h2.flagged { color: #f87171; }
  .close-btn { float: right; background: transparent; border: none; color: #888;
               font-size: 1.4rem; cursor: pointer; line-height: 1; }
  .close-btn:hover { color: #e0e0e0; }
  .info-row { display: flex; justify-content: space-between; padding: 6px 0;
              border-bottom: 1px solid #2d2d3d; font-size: .88rem; }
  .info-row:last-child { border-bottom: none; }
  .info-label { color: #888; }
  .info-val   { color: #e0e0e0; font-family: monospace; }
  .bar-wrap { display: flex; align-items: center; gap: 8px; margin: 4px 0; }
  .bar-bg { flex: 1; height: 8px; background: #2d2d3d; border-radius: 4px; overflow: hidden; }
  .bar-fill { height: 100%; border-radius: 4px; transition: width .3s; }
  .bar-life { background: #4ade80; }
  .bar-mana { background: #60a5fa; }
  .bar-label { font-size: .75rem; color: #888; width: 3.5rem; text-align: right; }
  .stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; margin-top: 12px; }
  .stat-item { display: flex; justify-content: space-between; font-size: .85rem;
               padding: 4px 0; border-bottom: 1px solid #222; }
  .stat-name { color: #888; }
  .stat-val  { color: #e0e0e0; }
  .section-title { font-size: .75rem; text-transform: uppercase; letter-spacing: .08em;
                   color: #666; margin: 16px 0 6px; }
  textarea.notes { width: 100%; background: #13131c; color: #e0e0e0; border: 1px solid #2d2d3d;
                   border-radius: 6px; padding: 8px; font-size: .85rem; resize: vertical;
                   min-height: 70px; font-family: inherit; }
  textarea.notes:focus { outline: none; border-color: #5b5b8c; }
  .note-hint { font-size: .72rem; color: #555; margin-top: 4px; }
  .modal-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 18px; }

  /* ── Live telemetry wall ─────────────────────────────────────────── */
  .layout { display: grid; grid-template-columns: 1fr 420px; gap: 0; align-items: start; }
  @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
  aside { border-left: 1px solid #2d2d3d; min-height: calc(100vh - 92px);
          display: flex; flex-direction: column; }
  @media (max-width: 1100px) { aside { border-left: none; border-top: 1px solid #2d2d3d; } }
  .panel-head { display: flex; align-items: center; justify-content: space-between;
                padding: 12px 18px; border-bottom: 1px solid #2d2d3d; background: #16161f; }
  .panel-head h2 { font-size: .82rem; text-transform: uppercase; letter-spacing: .09em;
                   color: #888; font-weight: 600; }
  .live-dot { width: 8px; height: 8px; border-radius: 50%; background: #4ade80;
              box-shadow: 0 0 0 0 rgba(74,222,128,.7); animation: pulse 2s infinite; }
  .live-dot.dead { background: #f87171; animation: none; }
  @keyframes pulse {
    0%   { box-shadow: 0 0 0 0 rgba(74,222,128,.6); }
    70%  { box-shadow: 0 0 0 7px rgba(74,222,128,0); }
    100% { box-shadow: 0 0 0 0 rgba(74,222,128,0); }
  }
  .live-label { display: flex; align-items: center; gap: 7px; font-size: .78rem; color: #666; }
  #feed { flex: 1; overflow-y: auto; max-height: calc(100vh - 150px);
          padding: 6px 0; font-family: ui-monospace, "Cascadia Code", monospace; }
  .ev { display: grid; grid-template-columns: 62px 1fr; gap: 8px; padding: 6px 16px;
        font-size: .76rem; line-height: 1.45; border-left: 3px solid transparent;
        animation: slidein .28s ease-out; }
  @keyframes slidein { from { opacity: 0; transform: translateX(14px); } to { opacity: 1; transform: none; } }
  .ev-time { color: #4b4b5c; }
  .ev-msg  { color: #b8b8c8; word-break: break-word; }
  .ev.info  { border-left-color: #3b3b5c; }
  .ev.warn  { border-left-color: #f59e0b; background: rgba(245,158,11,.05); }
  .ev.warn  .ev-msg { color: #fcd34d; }
  .ev.alert { border-left-color: #ef4444; background: rgba(239,68,68,.09); }
  .ev.alert .ev-msg { color: #fca5a5; font-weight: 600; }
  .ev-kind { display: inline-block; font-size: .64rem; text-transform: uppercase;
             letter-spacing: .06em; color: #55556b; margin-right: 6px; }
  .feed-controls { display: flex; gap: 6px; padding: 8px 16px; border-top: 1px solid #2d2d3d;
                   background: #16161f; }
  .chip { background: #22222e; border: 1px solid #33334a; color: #777; font-size: .7rem;
          padding: 3px 9px; border-radius: 999px; cursor: pointer; }
  .chip.on { background: #3b3b5c; color: #c4b5fd; border-color: #55557a; }

  /* ── World map + replay ──────────────────────────────────────────── */
  .map-wrap { padding: 10px 16px 14px; border-bottom: 1px solid #2d2d3d; }
  #worldmap { width: 100%; background: #0b0b10; border: 1px solid #2d2d3d;
              border-radius: 8px; display: block; }
  .map-legend { display: flex; gap: 14px; font-size: .68rem; color: #666; margin-top: 6px; }
  .map-legend span::before { content: '●'; margin-right: 4px; }
  .lg-ok::before   { color: #4ade80; }
  .lg-bad::before  { color: #f87171; }
  #replay { width: 100%; background: #0b0b10; border: 1px solid #3d2020;
            border-radius: 8px; display: block; margin-top: 6px; }
  .replay-legend { display: flex; gap: 14px; font-size: .7rem; color: #777; margin-top: 8px; }
  .replay-legend b { font-weight: 600; }
  .lg-claim { color: #f87171; }
  .lg-allow { color: #4ade80; }
</style>
</head>
<body>
<header>
  <h1>&#9876; Michi Admin</h1>
  <form method="POST" action="/logout" style="display:inline">
    <button class="logout-btn" type="submit">Logout</button>
  </form>
</header>
<div id="status-bar">Connecting&hellip;</div>
<div class="layout">
  <main><div id="players"></div></main>
  <aside>
    <div class="panel-head">
      <h2>Live world</h2>
      <span class="live-label" id="map-label">&mdash;</span>
    </div>
    <div class="map-wrap">
      <canvas id="worldmap" width="400" height="260"></canvas>
      <div class="map-legend">
        <span class="lg-ok">normal</span>
        <span class="lg-bad">flagged</span>
      </div>
    </div>
    <div class="panel-head">
      <h2>Telemetry</h2>
      <span class="live-label"><span class="live-dot" id="live-dot"></span><span id="live-text">live</span></span>
    </div>
    <div id="feed"></div>
    <div class="feed-controls">
      <button class="chip on" data-sev="info"  onclick="toggleSev(this)">info</button>
      <button class="chip on" data-sev="warn"  onclick="toggleSev(this)">warn</button>
      <button class="chip on" data-sev="alert" onclick="toggleSev(this)">alert</button>
      <button class="chip" onclick="clearFeed()" style="margin-left:auto">clear</button>
    </div>
  </aside>
</div>

<div class="overlay" id="modal" hidden>
  <div class="modal" id="modal-content">
    <button class="close-btn" onclick="closeModal()">&times;</button>
    <h2 id="modal-title"></h2>
    <div id="modal-body"></div>
  </div>
</div>

<script>
let _players = [];
let _refreshTimer = null;

function fmt_uptime(s) {
  if (s < 60) return s + 's';
  if (s < 3600) return Math.floor(s/60) + 'm ' + (s%60) + 's';
  return Math.floor(s/3600) + 'h ' + Math.floor((s%3600)/60) + 'm';
}

function bar(val, max, cls) {
  const pct = max > 0 ? Math.min(100, Math.round(val/max*100)) : 0;
  return `<div class="bar-wrap">
    <div class="bar-bg"><div class="bar-fill ${cls}" style="width:${pct}%"></div></div>
    <span class="bar-label">${val}/${max}</span>
  </div>`;
}

function renderCards(players) {
  const el = document.getElementById('players');
  if (!players.length) {
    el.innerHTML = '<p class="empty">No players connected.</p>';
    return;
  }
  el.innerHTML = players.map(p => `
    <div class="card${p.flagged ? ' flagged' : ''}" data-id="${p.id}">
      <div class="card-header">
        <div class="avatar">${(p.name||'?')[0].toUpperCase()}</div>
        <div>
          <div class="card-name${p.flagged ? ' flagged' : ''}" title="${p.flagged ? esc(p.flag_reasons.join('; ')) : ''}">${esc(p.name)}${p.flagged ? ' &#9888;' : ''}</div>
          <div class="card-class">${esc(p.class)} &bull; Lv ${p.stats.level}</div>
          <div class="card-map">${esc(p.map_id)}</div>
        </div>
      </div>
      ${p.flagged ? `<div class="flag-banner">Flagged for review:<ul>${p.flag_reasons.map(r => '<li>' + esc(r) + '</li>').join('')}</ul></div>` : ''}
      ${bar(p.stats.life, p.stats.maxLife, 'bar-life')}
      ${bar(p.stats.mana, p.stats.maxMana, 'bar-mana')}
      <div class="btn-row">
        <button class="btn btn-info" onclick="openInfo(${p.id})">Info</button>
        <button class="btn btn-kick" onclick="doKick(${p.id})">Kick</button>
        <button class="btn btn-ban"  onclick="doBan(${p.id})">Ban</button>
        <button class="btn btn-tp"   onclick="doTeleport(${p.id})">Teleport</button>
      </div>
    </div>`).join('');
}

function esc(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

async function refresh() {
  try {
    const r = await fetch('/api/players');
    if (r.status === 401 || r.status === 302) { location.href = '/login'; return; }
    const data = await r.json();
    _players = data.players || [];
    renderCards(_players);
    document.getElementById('status-bar').textContent =
      `${_players.length} player(s) connected — updated ${new Date().toLocaleTimeString()}`;
  } catch(e) {
    document.getElementById('status-bar').textContent = 'Error reaching server.';
  }
}

function getPlayer(id) { return _players.find(p => p.id === id); }

function openInfo(id) {
  const p = getPlayer(id);
  if (!p) return;
  document.getElementById('modal-title').textContent = p.name;
  document.getElementById('modal-title').className = p.flagged ? 'flagged' : '';
  document.getElementById('modal-body').innerHTML = `
    ${p.flagged ? `<div class="flag-banner">Flagged for review:<ul>${p.flag_reasons.map(r => '<li>' + esc(r) + '</li>').join('')}</ul>${p.flagged_at ? '<p class="note-hint">Since: ' + esc(p.flagged_at) + '</p>' : ''}</div>` : ''}
    <div class="info-row"><span class="info-label">Username (registry)</span><span class="info-val">${esc(p.username||'—')}</span></div>
    <div class="info-row"><span class="info-label">License key</span><span class="info-val">${esc(p.license_key)}</span></div>
    <div class="info-row"><span class="info-label">Class</span><span class="info-val">${esc(p.class)}</span></div>
    <div class="info-row"><span class="info-label">Map</span><span class="info-val">${esc(p.map_id)}</span></div>
    <div class="info-row"><span class="info-label">Uptime</span><span class="info-val" id="uptime-val">${fmt_uptime(p.uptime_s)}</span></div>

    <p class="section-title">Stats</p>
    ${bar(p.stats.life, p.stats.maxLife, 'bar-life')}
    ${bar(p.stats.mana, p.stats.maxMana, 'bar-mana')}
    <div class="stats-grid">
      <div class="stat-item"><span class="stat-name">Level</span><span class="stat-val">${p.stats.level}</span></div>
      <div class="stat-item"><span class="stat-name">EXP</span><span class="stat-val">${p.stats.exp} / ${p.stats.nextLevelExp}</span></div>
      <div class="stat-item"><span class="stat-name">Strength</span><span class="stat-val">${p.stats.strength}</span></div>
      <div class="stat-item"><span class="stat-name">Dexterity</span><span class="stat-val">${p.stats.dexterity}</span></div>
      <div class="stat-item"><span class="stat-name">Skill pts</span><span class="stat-val">${p.stats.skillPoints}</span></div>
      <div class="stat-item"><span class="stat-name">Coins</span><span class="stat-val">${p.stats.coin}</span></div>
    </div>

    <p class="section-title">Movement replay &mdash; claimed vs. allowed</p>
    <div id="replay-host"><p class="note-hint">Loading trace&hellip;</p></div>

    <p class="section-title">Admin notes</p>
    <textarea class="notes" id="note-area" placeholder="No notes yet…">${esc(p.note||'')}</textarea>
    <p class="note-hint">Auto-saved when you click away. Persists across sessions.</p>
    ${p.note_updated_at ? '<p class="note-hint">Last updated: ' + esc(p.note_updated_at) + '</p>' : ''}

    <div class="modal-actions">
      <button class="btn btn-kick" onclick="closeModal();doKick(${p.id})">Kick</button>
      <button class="btn btn-ban"  onclick="closeModal();doBan(${p.id})">Ban</button>
      <button class="btn btn-tp"   onclick="closeModal();doTeleport(${p.id})">Teleport</button>
    </div>`;

  document.getElementById('note-area').addEventListener('blur', () => saveNote(p.license_key));
  document.getElementById('modal').hidden = false;
  openReplay(p.license_key);
}

function closeModal() { document.getElementById('modal').hidden = true; }
document.getElementById('modal').addEventListener('click', e => { if (e.target === e.currentTarget) closeModal(); });

async function saveNote(license_key) {
  const note = document.getElementById('note-area').value;
  await fetch('/api/notes', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({license_key, note})
  });
}

async function action(payload) {
  const r = await fetch('/api/action', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(payload)
  });
  const data = await r.json();
  if (data.error) { alert('Error: ' + data.error); }
  else { alert(data.result); refresh(); }
}

function doKick(id) {
  const p = getPlayer(id); if (!p) return;
  const reason = prompt('Kick reason:', 'Kicked by admin');
  if (reason === null) return;
  action({action: 'kick', target: p.name, reason});
}

function doBan(id) {
  const p = getPlayer(id); if (!p) return;
  const dur = prompt('Ban duration in seconds (0 = permanent):', '3600');
  if (dur === null) return;
  action({action: 'ban', target: p.name, duration_seconds: parseInt(dur, 10) || 0});
}

function doTeleport(id) {
  const p = getPlayer(id); if (!p) return;
  const col = prompt('Destination column (tile X):', '0');
  if (col === null) return;
  const row = prompt('Destination row (tile Y):', '0');
  if (row === null) return;
  action({action: 'teleport', target: p.name, col: parseInt(col, 10), row: parseInt(row, 10)});
}

/* ── Live telemetry stream (SSE) ──────────────────────────────────────── */
const MAX_FEED_ROWS = 300;
let _sevOn = {info: true, warn: true, alert: true};
let _es = null;

function toggleSev(btn) {
  const sev = btn.dataset.sev;
  _sevOn[sev] = !_sevOn[sev];
  btn.classList.toggle('on', _sevOn[sev]);
  document.querySelectorAll('#feed .ev.' + sev)
    .forEach(el => { el.style.display = _sevOn[sev] ? '' : 'none'; });
}

function clearFeed() { document.getElementById('feed').innerHTML = ''; }

function setLive(ok) {
  document.getElementById('live-dot').classList.toggle('dead', !ok);
  document.getElementById('live-text').textContent = ok ? 'live' : 'reconnecting…';
}

function appendEvent(ev) {
  const feed = document.getElementById('feed');
  // Stick to the bottom only if the operator hasn't scrolled up to read history.
  const atBottom = feed.scrollHeight - feed.scrollTop - feed.clientHeight < 40;

  const row = document.createElement('div');
  row.className = 'ev ' + (ev.severity || 'info');
  if (!_sevOn[ev.severity || 'info']) row.style.display = 'none';
  row.innerHTML = `<span class="ev-time">${esc(ev.time || '')}</span>` +
                  `<span class="ev-msg"><span class="ev-kind">${esc(ev.kind||'')}</span>${esc(ev.message||'')}</span>`;
  feed.appendChild(row);

  while (feed.childElementCount > MAX_FEED_ROWS) feed.removeChild(feed.firstElementChild);
  if (atBottom) feed.scrollTop = feed.scrollHeight;

  // An anomaly flag is the headline event — pull the player list forward so the
  // offending card turns red at the same moment the alert lands.
  if (ev.kind === 'anomaly_flag' || ev.kind === 'anomaly_cleared') refresh();
}

function connectStream() {
  if (_es) _es.close();
  _es = new EventSource('/api/stream');
  _es.onopen = () => setLive(true);
  _es.onmessage = e => {
    try { appendEvent(JSON.parse(e.data)); } catch (_) {}
  };
  _es.onerror = () => {
    setLive(false);
    // EventSource retries on its own; this only covers a hard close.
    if (_es.readyState === EventSource.CLOSED) setTimeout(connectStream, 3000);
  };
}

/* ── Live world map ───────────────────────────────────────────────────── */
async function drawWorld() {
  let w;
  try {
    const r = await fetch('/api/world');
    if (!r.ok) return;
    w = await r.json();
  } catch (_) { return; }
  if (w.error) return;

  const cv = document.getElementById('worldmap');
  const ctx = cv.getContext('2d');
  const pxW = w.width * w.tilewidth, pxH = w.height * w.tileheight;

  // Fit the map to the panel width, preserving aspect ratio.
  const cssW = cv.clientWidth || 400;
  const scale = cssW / pxW;
  cv.width = cssW;
  cv.height = Math.max(80, Math.round(pxH * scale));

  ctx.fillStyle = '#0b0b10';
  ctx.fillRect(0, 0, cv.width, cv.height);

  // Tile grid, drawn sparsely so a large map doesn't turn into a solid block.
  ctx.strokeStyle = '#1c1c28';
  ctx.lineWidth = 1;
  const step = Math.max(1, Math.round(8 / (w.tilewidth * scale))) * w.tilewidth * 8;
  for (let x = 0; x < pxW; x += step) {
    ctx.beginPath(); ctx.moveTo(x*scale, 0); ctx.lineTo(x*scale, cv.height); ctx.stroke();
  }
  for (let y = 0; y < pxH; y += step) {
    ctx.beginPath(); ctx.moveTo(0, y*scale); ctx.lineTo(cv.width, y*scale); ctx.stroke();
  }

  for (const p of (w.players || [])) {
    const cx = p.x * scale, cy = p.y * scale;
    ctx.beginPath();
    ctx.arc(cx, cy, p.flagged ? 6 : 4, 0, Math.PI * 2);
    ctx.fillStyle = p.flagged ? '#f87171' : '#4ade80';
    ctx.fill();
    if (p.flagged) {                       // halo so a cheater is unmissable
      ctx.beginPath();
      ctx.arc(cx, cy, 11, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(248,113,113,.55)';
      ctx.lineWidth = 2; ctx.stroke();
    }
    ctx.fillStyle = '#8a8a9e';
    ctx.font = '10px system-ui';
    ctx.fillText(p.name, cx + 8, cy + 3);
  }

  document.getElementById('map-label').textContent =
    `${w.map_id} · ${w.width}×${w.height}`;
}

/* ── Anomaly replay: claimed path vs. server-allowed path ─────────────── */
async function openReplay(license_key) {
  let d;
  try {
    const r = await fetch('/api/trace?license_key=' + encodeURIComponent(license_key));
    d = await r.json();
  } catch (_) { return; }

  const host = document.getElementById('replay-host');
  if (!host) return;
  const pts = d.trace || [];
  if (!pts.length) {
    host.innerHTML = '<p class="note-hint">No movement recorded yet.</p>';
    return;
  }

  host.innerHTML = `<canvas id="replay" width="480" height="260"></canvas>
    <div class="replay-legend">
      <span class="lg-claim"><b>—</b> claimed by client</span>
      <span class="lg-allow"><b>—</b> allowed by server</span>
      <span style="color:#666">${pts.length} moves · ${d.violations} violation(s) · ${esc(d.source)}</span>
    </div>`;

  const cv = document.getElementById('replay');
  const ctx = cv.getContext('2d');
  const cssW = cv.clientWidth || 480;
  cv.width = cssW; cv.height = 260;

  // Fit both paths into the canvas with a small margin.
  const xs = pts.flatMap(p => [p.cx, p.ax]), ys = pts.flatMap(p => [p.cy, p.ay]);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  const pad = 24;
  const spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
  const s = Math.min((cv.width - pad*2) / spanX, (cv.height - pad*2) / spanY);
  const tx = v => pad + (v - minX) * s;
  const ty = v => pad + (v - minY) * s;

  ctx.fillStyle = '#0b0b10';
  ctx.fillRect(0, 0, cv.width, cv.height);

  const path = (key_x, key_y, color, width, dash) => {
    ctx.beginPath();
    ctx.setLineDash(dash);
    pts.forEach((p, i) => {
      const X = tx(p[key_x]), Y = ty(p[key_y]);
      i ? ctx.lineTo(X, Y) : ctx.moveTo(X, Y);
    });
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke();
    ctx.setLineDash([]);
  };

  path('cx', 'cy', '#f87171', 2, [5, 4]);   // what the client asked for
  path('ax', 'ay', '#4ade80', 2, []);       // what the server permitted

  // Mark every point the server had to correct.
  pts.forEach(p => {
    if (!p.v) return;
    ctx.beginPath();
    ctx.arc(tx(p.cx), ty(p.cy), 3.5, 0, Math.PI * 2);
    ctx.fillStyle = '#fca5a5'; ctx.fill();
  });
}

refresh();
setInterval(refresh, 3000);
connectStream();
drawWorld();
setInterval(drawWorld, 1000);
</script>
</body>
</html>"""

_LOGIN_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Michi Admin — Login</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, sans-serif; background: #0f0f13; color: #e0e0e0;
         display: flex; align-items: center; justify-content: center; min-height: 100vh; }
  .box { background: #1a1a24; border: 1px solid #2d2d3d; border-radius: 12px;
         padding: 36px 32px; width: min(360px, 95vw); }
  h1 { color: #a78bfa; font-size: 1.2rem; margin-bottom: 24px; text-align: center; }
  label { font-size: .85rem; color: #888; display: block; margin-bottom: 6px; }
  input[type=password] { width: 100%; background: #13131c; color: #e0e0e0;
                         border: 1px solid #2d2d3d; border-radius: 6px;
                         padding: 9px 12px; font-size: .95rem; margin-bottom: 16px; }
  input[type=password]:focus { outline: none; border-color: #5b5b8c; }
  button { width: 100%; background: #5b5b8c; color: #e0e0e0; border: none;
           border-radius: 6px; padding: 10px; font-size: .95rem; cursor: pointer; }
  button:hover { background: #7070a8; }
  .err { color: #f87171; font-size: .82rem; margin-bottom: 12px; text-align: center; }
</style>
</head>
<body>
<div class="box">
  <h1>&#9876; Michi Admin</h1>
  {error}
  <form method="POST" action="/login">
    <label for="pw">Password</label>
    <input type="password" id="pw" name="password" autofocus required>
    <button type="submit">Sign in</button>
  </form>
</div>
</body>
</html>"""


class AdminDashboard:
    def __init__(self, server, cfg: dict) -> None:
        self._server = server
        self._password: str = cfg.get("admin_password", "")
        self._port: int = int(cfg.get("dashboard_port", 8888))
        self._tokens: set[str] = set()
        self._notes_path: Path = BASE_DIR / "admin_notes.json"
        self._notes: dict[str, dict] = self._load_notes()
        self._notes_lock = asyncio.Lock()
        self._saves_db_path: Path = self._resolve_saves_db(cfg)

    async def start(self) -> None:
        host = getattr(self._server, "host", "0.0.0.0") or "0.0.0.0"
        srv = await asyncio.start_server(self._handle_connection, host, self._port)
        display_host = "127.0.0.1" if host in ("0.0.0.0", "") else host
        log.info("Admin dashboard listening on http://%s:%d/", display_host, self._port)
        asyncio.create_task(self._serve(srv))

    async def _serve(self, srv) -> None:
        async with srv:
            await srv.serve_forever()

    def _load_notes(self) -> dict:
        try:
            return json.loads(self._notes_path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    async def _save_notes(self) -> None:
        tmp = self._notes_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(self._notes, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.rename(self._notes_path)

    def _resolve_saves_db(self, cfg: dict) -> Path:
        p = cfg.get("saves_db_path", "")
        if p:
            return Path(p)
        return BASE_DIR / ".." / "save_server" / "saves.db"

    def _lookup_username(self, license_key: str) -> Optional[str]:
        if not self._saves_db_path.exists():
            return None
        try:
            uri = self._saves_db_path.resolve().as_posix()
            con = sqlite3.connect(f"file:{uri}?mode=ro", uri=True, timeout=1.0)
            row = con.execute(
                "SELECT username FROM usernames WHERE license_key = ?", (license_key,)
            ).fetchone()
            con.close()
            return row[0] if row else None
        except Exception:
            return None

    async def _handle_connection(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        try:
            # No blanket timeout: /api/stream is a long-lived SSE connection. The
            # request head is read under its own timeout inside _process_request.
            await self._process_request(reader, writer)
        except (asyncio.TimeoutError, ConnectionError, BrokenPipeError):
            pass
        except Exception as exc:
            log.debug("Dashboard request error: %s", exc)
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def _process_request(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        # Reading the request head is bounded; the response that follows may not be
        # (SSE streams stay open), so the timeout wraps only this part.
        async def _read_head():
            line = await reader.readline()
            if not line:
                return None
            parts = line.decode("utf-8", errors="replace").rstrip("\r\n").split(" ")
            if len(parts) < 2:
                return None
            method = parts[0].upper()
            raw_path = parts[1]

            headers: dict[str, str] = {}
            while True:
                hline = await reader.readline()
                if hline in (b"\r\n", b"\n", b""):
                    break
                if b":" in hline:
                    key, _, val = hline.decode("utf-8", errors="replace").partition(":")
                    headers[key.strip().lower()] = val.strip().rstrip("\r\n")

            body = b""
            content_length = int(headers.get("content-length", 0) or 0)
            if content_length > 0:
                body = await reader.readexactly(min(content_length, MAX_BODY))
            return method, raw_path, headers, body

        head = await asyncio.wait_for(_read_head(), timeout=10.0)
        if head is None:
            return
        method, raw_path, headers, body = head

        await self._route(method, raw_path, headers, body, writer, reader)

    async def _route(
        self,
        method: str,
        raw_path: str,
        headers: dict,
        body: bytes,
        writer: asyncio.StreamWriter,
        reader: asyncio.StreamReader,
    ) -> None:
        path = raw_path.split("?")[0].rstrip("/") or "/"

        if path == "/login":
            if method == "GET":
                await self._serve_login(writer, error=False)
            elif method == "POST":
                await self._handle_login_post(body, writer)
            else:
                await self._send_text(writer, 405, "Method Not Allowed")
            return

        token = self._extract_token(headers)
        if token not in self._tokens:
            await self._send_redirect(writer, "/login")
            return

        if path == "/" and method == "GET":
            await self._send_html(writer, 200, _DASHBOARD_HTML)
        elif path == "/logout" and method == "POST":
            self._tokens.discard(token)
            await self._send_redirect(writer, "/login")
        elif path == "/api/players" and method == "GET":
            await self._api_players(writer)
        elif path == "/api/action" and method == "POST":
            await self._api_action(body, writer)
        elif path == "/api/notes" and method == "POST":
            await self._api_notes(body, writer)
        elif path == "/api/stream" and method == "GET":
            await self._api_stream(reader, writer)
        elif path == "/api/trace" and method == "GET":
            await self._api_trace(raw_path, writer)
        elif path == "/api/world" and method == "GET":
            await self._api_world(writer)
        else:
            await self._send_text(writer, 404, "Not found")

    def _extract_token(self, headers: dict) -> str:
        cookie_header = headers.get("cookie", "")
        for part in cookie_header.split(";"):
            k, _, v = part.strip().partition("=")
            if k.strip() == "admin_token":
                return v.strip()
        return ""

    async def _serve_login(self, writer: asyncio.StreamWriter, *, error: bool) -> None:
        err_html = '<p class="err">Incorrect password.</p>' if error else ""
        html = _LOGIN_HTML.replace("{error}", err_html)
        await self._send_html(writer, 200, html)

    async def _handle_login_post(self, body: bytes, writer: asyncio.StreamWriter) -> None:
        params = urllib.parse.parse_qs(body.decode("utf-8", errors="replace"))
        submitted = params.get("password", [""])[0]
        if self._password and hmac.compare_digest(submitted, self._password):
            token = secrets.token_hex(32)
            self._tokens.add(token)
            await self._send_response(
                writer, 302, "text/html", b"",
                extra_headers={
                    "Location": "/",
                    "Set-Cookie": f"admin_token={token}; HttpOnly; SameSite=Strict; Path=/",
                },
            )
        else:
            await self._serve_login(writer, error=True)

    async def _api_players(self, writer: asyncio.StreamWriter) -> None:
        now = time.time()
        anomaly_monitor = getattr(self._server, "anomaly_monitor", None)
        players = []
        for pid, client in list(self._server.clients.items()):
            p = client.player
            note_entry = self._notes.get(p.license_key, {})
            flag = anomaly_monitor.flags.get(p.license_key) if anomaly_monitor else None
            players.append({
                "id": pid,
                "name": p.name,
                "class": p.player_class,
                "license_key": p.license_key,
                "username": self._lookup_username(p.license_key),
                "map_id": p.map_id,
                "uptime_s": int(now - p.connect_time),
                "stats": p.stats_to_dict(),
                "note": note_entry.get("note", ""),
                "note_updated_at": note_entry.get("updated_at", ""),
                "flagged": flag is not None,
                "flag_reasons": flag["reasons"] if flag else [],
                "flagged_at": flag["flagged_at"] if flag else "",
            })
        await self._send_json(writer, {"players": players})

    async def _api_action(self, body: bytes, writer: asyncio.StreamWriter) -> None:
        try:
            req = json.loads(body)
        except json.JSONDecodeError:
            await self._send_json(writer, {"error": "invalid JSON"}, status=400)
            return

        action = str(req.get("action", ""))
        target = str(req.get("target", ""))

        try:
            if action == "kick":
                reason = str(req.get("reason", "Kicked by admin"))[:200]
                result = await self._server.admin_kick(target, reason)
            elif action == "ban":
                duration = int(req.get("duration_seconds", 0))
                result = await self._server.admin_ban(target, duration)
            elif action == "teleport":
                col = int(req.get("col", 0))
                row = int(req.get("row", 0))
                result = self._server.admin_teleport(target, col, row)
            else:
                await self._send_json(writer, {"error": f"unknown action: {action}"}, status=400)
                return
        except Exception as exc:
            await self._send_json(writer, {"error": str(exc)}, status=500)
            return

        await self._send_json(writer, {"result": result})

    async def _api_notes(self, body: bytes, writer: asyncio.StreamWriter) -> None:
        try:
            req = json.loads(body)
        except json.JSONDecodeError:
            await self._send_json(writer, {"error": "invalid JSON"}, status=400)
            return

        license_key = str(req.get("license_key", ""))
        note = str(req.get("note", ""))[:2000]

        if not license_key:
            await self._send_json(writer, {"error": "missing license_key"}, status=400)
            return

        async with self._notes_lock:
            self._notes[license_key] = {
                "note": note,
                "updated_at": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
            await self._save_notes()

        await self._send_json(writer, {"ok": True})

    async def _api_stream(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        """Server-Sent Events feed of live telemetry.

        Held open for the life of the browser tab. Recent history is replayed first
        so a dashboard opened mid-session isn't staring at a blank wall, then new
        events are pushed as they happen. A periodic comment frame keeps proxies
        from timing the connection out.
        """
        bus = getattr(self._server, "event_bus", None)
        if bus is None:
            await self._send_text(writer, 503, "event bus unavailable")
            return

        writer.write(
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Type: text/event-stream; charset=utf-8\r\n"
            b"Cache-Control: no-store\r\n"
            b"Connection: keep-alive\r\n"
            b"X-Accel-Buffering: no\r\n"
            b"\r\n"
        )
        await writer.drain()

        queue = bus.subscribe()
        # A browser that closes the tab half-closes the socket without sending
        # anything more. Waiting only on the event queue would leave this task —
        # and its subscription — alive until the next write happened to fail, so
        # race the queue against an EOF read and tear down as soon as either wins.
        eof_task = asyncio.create_task(reader.read(1))
        try:
            for event in bus.history(limit=60):
                await self._send_sse(writer, event)

            while True:
                get_task = asyncio.create_task(queue.get())
                done, _ = await asyncio.wait(
                    {get_task, eof_task},
                    timeout=20.0,
                    return_when=asyncio.FIRST_COMPLETED,
                )

                if eof_task in done:                    # peer closed the connection
                    get_task.cancel()
                    break

                if not done:                            # idle: keep proxies from timing out
                    get_task.cancel()
                    writer.write(b": keepalive\n\n")    # comment frame, ignored by EventSource
                    await writer.drain()
                    continue

                await self._send_sse(writer, get_task.result())
        except (ConnectionError, BrokenPipeError, ConnectionResetError):
            pass                                        # browser went away mid-write
        finally:
            eof_task.cancel()
            bus.unsubscribe(queue)

    async def _send_sse(self, writer: asyncio.StreamWriter, event: dict) -> None:
        payload = json.dumps(event, ensure_ascii=False)
        writer.write(f"id: {event.get('seq', 0)}\ndata: {payload}\n\n".encode("utf-8"))
        await writer.drain()

    async def _api_trace(self, raw_path: str, writer: asyncio.StreamWriter) -> None:
        """Claimed-vs-allowed movement trace for one player, for the replay panel.

        Prefers the snapshot frozen at flag time (so evidence survives the player
        going still) and falls back to their live rolling trace.
        """
        query = urllib.parse.parse_qs(raw_path.partition("?")[2])
        license_key = (query.get("license_key") or [""])[0]
        if not license_key:
            await self._send_json(writer, {"error": "missing license_key"}, status=400)
            return

        monitor = getattr(self._server, "anomaly_monitor", None)
        flag = monitor.flags.get(license_key) if monitor else None
        trace = list(flag.get("trace") or []) if flag else []
        source = "flag_snapshot"

        if not trace:
            for client in list(self._server.clients.values()):
                if client.player.license_key == license_key:
                    trace = [
                        {"ts": ts, "cx": cx, "cy": cy, "ax": ax, "ay": ay, "v": kind}
                        for (ts, cx, cy, ax, ay, kind) in list(client.player.move_trace)
                    ]
                    source = "live"
                    break

        world = getattr(self._server, "world", None)
        await self._send_json(writer, {
            "license_key": license_key,
            "source": source,
            "trace": trace,
            "violations": sum(1 for p in trace if p.get("v")),
            "tilewidth": getattr(world, "tilewidth", 32),
            "tileheight": getattr(world, "tileheight", 32),
        })

    async def _api_world(self, writer: asyncio.StreamWriter) -> None:
        """Map dimensions plus every player's current tile, for the live world map."""
        world = getattr(self._server, "world", None)
        monitor = getattr(self._server, "anomaly_monitor", None)
        if world is None:
            await self._send_json(writer, {"error": "no active world"}, status=503)
            return

        dots = []
        for pid, client in list(self._server.clients.items()):
            p = client.player
            dots.append({
                "id": pid,
                "name": p.name,
                "x": p.x,
                "y": p.y,
                "flagged": bool(monitor and p.license_key in monitor.flags),
            })

        await self._send_json(writer, {
            "map_id": getattr(world, "map_id", ""),
            "width": world.width,
            "height": world.height,
            "tilewidth": world.tilewidth,
            "tileheight": world.tileheight,
            "players": dots,
        })

    async def _send_response(
        self,
        writer: asyncio.StreamWriter,
        status: int,
        content_type: str,
        body: bytes,
        extra_headers: Optional[dict] = None,
    ) -> None:
        status_text = {200: "OK", 302: "Found", 400: "Bad Request",
                       401: "Unauthorized", 404: "Not Found",
                       405: "Method Not Allowed", 500: "Internal Server Error"}.get(status, "OK")
        headers = (
            f"HTTP/1.1 {status} {status_text}\r\n"
            f"Content-Type: {content_type}; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n"
            "Cache-Control: no-store\r\n"
        )
        if extra_headers:
            for k, v in extra_headers.items():
                headers += f"{k}: {v}\r\n"
        headers += "\r\n"
        writer.write(headers.encode("utf-8") + body)
        await writer.drain()

    async def _send_html(self, writer: asyncio.StreamWriter, status: int, html: str) -> None:
        await self._send_response(writer, status, "text/html", html.encode("utf-8"))

    async def _send_json(
        self, writer: asyncio.StreamWriter, data: dict, status: int = 200
    ) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        await self._send_response(writer, status, "application/json", body)

    async def _send_text(self, writer: asyncio.StreamWriter, status: int, msg: str) -> None:
        await self._send_response(writer, status, "text/plain", msg.encode("utf-8"))

    async def _send_redirect(self, writer: asyncio.StreamWriter, location: str) -> None:
        await self._send_response(
            writer, 302, "text/html", b"",
            extra_headers={"Location": location},
        )
