import React, { useEffect, useMemo, useRef, useState } from "react";

export type WorldMapMarker = {
  countryCode: string;
  latitude: number;
  longitude: number;
  nodeCount: number;
  active: boolean;
};

type GeoPoint = [number, number];
type CountryShape = { code: string | null; rings: GeoPoint[][] };
type Viewport = { zoom: number; x: number; y: number };

const MIN_ZOOM = 1.15;
const MAX_ZOOM = 6;
const DEFAULT_ZOOM = 1.82;
const CENTER_LON = 18;
const CENTER_LAT = 43;
const MERCATOR_LIMIT = 85.05112878;
const GEOJSON_URL = "https://raw.githubusercontent.com/datasets/geo-boundaries-world-110m/refs/heads/main/countries.geojson";
const CACHE_KEY = "dot.world-map.110m.v1";
const CACHE_TTL = 30 * 24 * 60 * 60 * 1000;

export default function WorldMap({ markers, selectedCountry, onMarkerClick }: {
  markers: WorldMapMarker[];
  selectedCountry: string | null;
  onMarkerClick: (countryCode: string) => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const [countries, setCountries] = useState<CountryShape[]>([]);
  const [viewport, setViewport] = useState<Viewport>({ zoom: DEFAULT_ZOOM, x: 0, y: 0 });
  const [size, setSize] = useState({ width: 1, height: 1, dpr: 1 });

  useEffect(() => {
    let cancelled = false;
    loadGeometry().then(value => { if (!cancelled) setCountries(value); }).catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!wrapRef.current) return;
    const observer = new ResizeObserver(entries => {
      const rect = entries[0]?.contentRect;
      if (!rect) return;
      setSize({ width: Math.max(1, rect.width), height: Math.max(1, rect.height), dpr: Math.max(1, window.devicePixelRatio || 1) });
    });
    observer.observe(wrapRef.current);
    return () => observer.disconnect();
  }, []);

  const markerCountries = useMemo(() => new Set(markers.map(marker => marker.countryCode)), [markers]);
  const activeCountry = markers.find(marker => marker.active)?.countryCode ?? null;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    canvas.width = Math.round(size.width * size.dpr);
    canvas.height = Math.round(size.height * size.dpr);
    canvas.style.width = `${size.width}px`;
    canvas.style.height = `${size.height}px`;
    const context = canvas.getContext("2d");
    if (!context) return;
    context.setTransform(size.dpr, 0, 0, size.dpr, 0, 0);
    draw(context, size.width, size.height, countries, markers, markerCountries, activeCountry, selectedCountry, viewport);
  }, [countries, markers, markerCountries, activeCountry, selectedCountry, viewport, size]);

  function clamp(next: Viewport): Viewport {
    const maxX = size.width * next.zoom * .46;
    const maxY = size.height * next.zoom * .46;
    return { ...next, x: Math.max(-maxX, Math.min(maxX, next.x)), y: Math.max(-maxY, Math.min(maxY, next.y)) };
  }

  function zoomAt(clientX: number, clientY: number, factor: number) {
    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;
    setViewport(old => {
      const nextZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, old.zoom * factor));
      const ratio = nextZoom / old.zoom;
      const cx = clientX - rect.left - rect.width / 2;
      const cy = clientY - rect.top - rect.height / 2;
      return clamp({ zoom: nextZoom, x: old.x + cx * (1 - ratio), y: old.y + cy * (1 - ratio) });
    });
  }

  function selectAt(clientX: number, clientY: number) {
    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;
    const x = clientX - rect.left;
    const y = clientY - rect.top;
    let best: { code: string; distance: number } | null = null;
    for (const marker of markers) {
      const point = viewportPoint(marker.longitude, marker.latitude, rect.width, rect.height, viewport);
      const distance = Math.hypot(x - point.x, y - point.y);
      if (!best || distance < best.distance) best = { code: marker.countryCode, distance };
    }
    if (best && best.distance <= 28) onMarkerClick(best.code);
  }

  return <div ref={wrapRef} className="world-map-wrap">
    <canvas
      ref={canvasRef}
      className="world-map-canvas"
      onWheel={event => { event.preventDefault(); zoomAt(event.clientX, event.clientY, event.deltaY < 0 ? 1.14 : .88); }}
      onDoubleClick={() => setViewport({ zoom: DEFAULT_ZOOM, x: 0, y: 0 })}
      onPointerDown={event => {
        event.currentTarget.setPointerCapture(event.pointerId);
        dragRef.current = { x: event.clientX, y: event.clientY, ox: viewport.x, oy: viewport.y };
      }}
      onPointerMove={event => {
        const drag = dragRef.current;
        if (!drag) return;
        setViewport(old => clamp({ ...old, x: drag.ox + event.clientX - drag.x, y: drag.oy + event.clientY - drag.y }));
      }}
      onPointerUp={event => {
        const drag = dragRef.current;
        dragRef.current = null;
        if (drag && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 5) selectAt(event.clientX, event.clientY);
      }}
      onPointerCancel={() => { dragRef.current = null; }}
    />
  </div>;
}

function draw(ctx: CanvasRenderingContext2D, width: number, height: number, countries: CountryShape[], markers: WorldMapMarker[], nodeCountries: Set<string>, activeCountry: string | null, selectedCountry: string | null, viewport: Viewport) {
  const gradient = ctx.createLinearGradient(0, 0, 0, height);
  gradient.addColorStop(0, "#050607"); gradient.addColorStop(.5, "#080b0d"); gradient.addColorStop(1, "#050607");
  ctx.fillStyle = gradient; ctx.fillRect(0, 0, width, height);

  for (let lon = -150; lon <= 150; lon += 30) {
    const a = viewportPoint(lon, -80, width, height, viewport), b = viewportPoint(lon, 80, width, height, viewport);
    ctx.strokeStyle = lon === 0 ? "#171d21" : "#12171a"; ctx.lineWidth = lon === 0 ? .85 : .45;
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
  }
  for (let lat = -60; lat <= 60; lat += 20) {
    const a = viewportPoint(-180, lat, width, height, viewport), b = viewportPoint(180, lat, width, height, viewport);
    ctx.strokeStyle = lat === 0 ? "#171d21" : "#12171a"; ctx.lineWidth = lat === 0 ? .85 : .45;
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
  }

  countries.forEach((country, index) => {
    const code = country.code;
    const hasNodes = !!code && nodeCountries.has(code);
    const active = !!code && code === activeCountry;
    const selected = !!code && code === selectedCountry;
    const variant = Math.abs(hash(code ?? String(index))) % 3;
    ctx.fillStyle = active ? "#281316" : selected ? "#21171a" : hasNodes ? "#1b2226" : ["#12171a", "#14191c", "#101518"][variant];
    ctx.strokeStyle = active ? "#8d3438" : selected ? "#6d4548" : hasNodes ? "#505a60" : "#343c42";
    ctx.lineWidth = active ? 1.25 : hasNodes || selected ? 1 : .72;
    for (const ring of country.rings) {
      if (ring.length < 3) continue;
      ctx.beginPath();
      ring.forEach(([lon, lat], pointIndex) => {
        const p = viewportPoint(lon, lat, width, height, viewport);
        if (pointIndex === 0) ctx.moveTo(p.x, p.y); else ctx.lineTo(p.x, p.y);
      });
      ctx.closePath(); ctx.fill(); ctx.stroke();
    }
  });

  ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.font = "bold 9px 'Courier New', monospace";
  markers.forEach(marker => {
    const p = viewportPoint(marker.longitude, marker.latitude, width, height, viewport);
    const radius = marker.nodeCount > 1 ? 9 : 6;
    ctx.beginPath(); ctx.fillStyle = "rgba(0,0,0,.6)"; ctx.arc(p.x, p.y, radius + 4, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.fillStyle = "#030405"; ctx.arc(p.x, p.y, radius + 2, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.fillStyle = marker.active ? "#ff2d2d" : "#e8e8e8"; ctx.arc(p.x, p.y, radius, 0, Math.PI * 2); ctx.fill();
    if (marker.nodeCount > 1) { ctx.fillStyle = marker.active ? "#fff" : "#000"; ctx.fillText(String(Math.min(99, marker.nodeCount)), p.x, p.y + .5); }
  });
}

function viewportPoint(longitude: number, latitude: number, width: number, height: number, viewport: Viewport) {
  const base = project(longitude, latitude, width, height);
  const focus = project(CENTER_LON, CENTER_LAT, width, height);
  return { x: width / 2 + (base.x - focus.x) * viewport.zoom + viewport.x, y: height / 2 + (base.y - focus.y) * viewport.zoom + viewport.y };
}

function project(longitude: number, latitude: number, width: number, height: number) {
  const worldSize = width;
  const top = (height - worldSize) / 2;
  const x = Math.max(0, Math.min(1, (longitude + 180) / 360));
  const safe = Math.max(-MERCATOR_LIMIT, Math.min(MERCATOR_LIMIT, latitude));
  const radians = safe * Math.PI / 180;
  const y = (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2;
  return { x: x * worldSize, y: top + y * worldSize };
}

async function loadGeometry(): Promise<CountryShape[]> {
  const cached = readCache();
  if (cached && Date.now() - cached.storedAt < CACHE_TTL) return parseGeoJson(cached.text);
  try {
    const response = await fetch(GEOJSON_URL, { cache: "no-cache" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const text = await response.text();
    localStorage.setItem(CACHE_KEY, JSON.stringify({ storedAt: Date.now(), text }));
    return parseGeoJson(text);
  } catch (error) {
    if (cached) return parseGeoJson(cached.text);
    throw error;
  }
}

function readCache(): { storedAt: number; text: string } | null {
  try { const value = JSON.parse(localStorage.getItem(CACHE_KEY) || "null"); return value?.text ? value : null; } catch { return null; }
}

function parseGeoJson(text: string): CountryShape[] {
  const json = JSON.parse(text);
  const result: CountryShape[] = [];
  for (const feature of json.features ?? []) {
    const geometry = feature.geometry;
    if (!geometry) continue;
    const codeRaw = String(feature.properties?.iso_a2 || feature.properties?.postal || "").toUpperCase();
    const code = codeRaw.length === 2 && codeRaw !== "-99" ? codeRaw : null;
    const rings: GeoPoint[][] = [];
    if (geometry.type === "Polygon") appendPolygon(geometry.coordinates, rings);
    else if (geometry.type === "MultiPolygon") for (const polygon of geometry.coordinates ?? []) appendPolygon(polygon, rings);
    if (rings.length) result.push({ code, rings });
  }
  return result;
}

function appendPolygon(polygon: unknown, target: GeoPoint[][]) {
  if (!Array.isArray(polygon)) return;
  for (const ring of polygon) {
    if (!Array.isArray(ring)) continue;
    const points = ring.filter(pair => Array.isArray(pair) && Number.isFinite(pair[0]) && Number.isFinite(pair[1])).map(pair => [Number(pair[0]), Number(pair[1])] as GeoPoint);
    if (points.length >= 3) target.push(points);
  }
}

function hash(value: string) { let result = 0; for (let i = 0; i < value.length; i++) result = ((result << 5) - result + value.charCodeAt(i)) | 0; return result; }
