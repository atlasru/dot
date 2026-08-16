const repo = 'atlasru/dot';
const releasePage = `https://github.com/${repo}/releases/latest`;

const $ = (id) => document.getElementById(id);
const formatBytes = (bytes) => {
  if (!Number.isFinite(bytes)) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit >= 2 ? 1 : 0)} ${units[unit]}`;
};

const detectPlatform = () => {
  const ua = navigator.userAgent || '';
  const platform = navigator.userAgentData?.platform || navigator.platform || '';
  if (/Android/i.test(ua)) return 'android';
  if (/iPhone|iPad|iPod/i.test(ua)) return 'ios';
  if (/Win/i.test(platform) || /Windows/i.test(ua)) return 'windows';
  if (/Mac/i.test(platform) || /Macintosh/i.test(ua)) return 'mac';
  if (/Linux/i.test(platform) || /Linux/i.test(ua)) return 'linux';
  return 'other';
};

let releaseAssets = { android: null, desktop: null };

const applyPlatform = () => {
  const kind = detectPlatform();
  const primary = $('primaryDownload');
  const lead = $('heroLead');
  const note = $('platformNote');

  const setPrimary = (label, href) => {
    primary.textContent = label;
    primary.href = href || releasePage;
  };

  if (kind === 'android') {
    lead.textContent = 'Minimal VLESS client for Android.';
    note.textContent = 'Android detected · APK build available';
    setPrimary('download for android', releaseAssets.android?.browser_download_url);
    return;
  }

  if (kind === 'windows') {
    lead.textContent = 'Minimal VLESS client for Windows.';
    note.textContent = 'Windows detected · portable x64 build available';
    setPrimary('download for windows', releaseAssets.desktop?.browser_download_url);
    return;
  }

  if (kind === 'ios') {
    lead.textContent = 'Minimal VLESS client for Android & Windows.';
    note.textContent = 'No iOS build currently available';
    setPrimary('view releases', releasePage);
    return;
  }

  if (kind === 'mac') {
    lead.textContent = 'Minimal VLESS client for Android & Windows.';
    note.textContent = 'Current desktop build targets Windows x64';
    setPrimary('view releases', releasePage);
    return;
  }

  if (kind === 'linux') {
    lead.textContent = 'Minimal VLESS client for Android & Windows.';
    note.textContent = 'Current packaged desktop build targets Windows x64';
    setPrimary('view releases', releasePage);
    return;
  }

  lead.textContent = 'Minimal VLESS client for Android & Windows.';
  note.textContent = 'Android APK and Windows x64 builds available';
  setPrimary('view releases', releasePage);
};

const parseVersionFromName = (name, prefix, suffix) => {
  if (!name?.startsWith(prefix) || !name.endsWith(suffix)) return null;
  return name.slice(prefix.length, -suffix.length);
};

const loadRelease = async () => {
  try {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' }
    });
    if (!response.ok) throw new Error(`GitHub ${response.status}`);
    const release = await response.json();
    const assets = Array.isArray(release.assets) ? release.assets : [];
    releaseAssets.android = assets.find((asset) => /^dot-android-.+\.apk$/i.test(asset.name)) || null;
    releaseAssets.desktop = assets.find((asset) => /^dot-desktop-.+-windows-x64\.zip$/i.test(asset.name)) || null;

    if (releaseAssets.android) {
      const version = parseVersionFromName(releaseAssets.android.name, 'dot-android-', '.apk');
      if (version) $('androidVersion').textContent = `v${version}`;
      $('androidDownload').href = releaseAssets.android.browser_download_url;
      $('androidSize').textContent = formatBytes(releaseAssets.android.size) || 'APK';
      if (releaseAssets.android.digest) $('androidHash').textContent = releaseAssets.android.digest.replace(/^sha256:/i, 'SHA-256: ');
    }

    if (releaseAssets.desktop) {
      const version = parseVersionFromName(releaseAssets.desktop.name, 'dot-desktop-', '-windows-x64.zip');
      if (version) $('desktopVersion').textContent = `v${version}`;
      $('desktopDownload').href = releaseAssets.desktop.browser_download_url;
      $('desktopSize').textContent = formatBytes(releaseAssets.desktop.size) || 'ZIP';
      if (releaseAssets.desktop.digest) $('desktopHash').textContent = releaseAssets.desktop.digest.replace(/^sha256:/i, 'SHA-256: ');
    }

    $('allReleases').href = release.html_url || releasePage;
  } catch {
    $('androidDownload').href = releasePage;
    $('desktopDownload').href = releasePage;
  } finally {
    applyPlatform();
  }
};

let rawIp = '';
let ipVisible = false;

const maskIp = (ip) => {
  if (!ip) return 'unavailable';
  if (ip.includes(':')) {
    const chunks = ip.split(':').filter(Boolean);
    return `${chunks.slice(0, 2).join(':')}:••••:••••`;
  }
  const parts = ip.split('.');
  if (parts.length !== 4) return ip;
  return `${parts[0]}.${parts[1]}.•••.•••`;
};

const setNetworkUnavailable = () => {
  rawIp = '';
  ipVisible = false;
  $('networkState').textContent = 'UNAVAILABLE';
  $('networkIp').textContent = 'unavailable';
  $('networkLocation').textContent = 'unavailable';
  $('networkProvider').textContent = 'unavailable';
  $('revealIp').disabled = true;
  $('revealIp').textContent = 'reveal ip';
};

const loadNetwork = async () => {
  $('networkState').textContent = 'CHECKING…';
  $('refreshNetwork').disabled = true;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 7000);
    const response = await fetch('https://ipwho.is/', { signal: controller.signal, cache: 'no-store' });
    clearTimeout(timer);
    if (!response.ok) throw new Error(`IP lookup ${response.status}`);
    const data = await response.json();
    if (data.success === false || !data.ip) throw new Error('IP lookup failed');

    rawIp = data.ip;
    ipVisible = false;
    $('networkState').textContent = 'ROUTE FOUND';
    $('networkIp').textContent = maskIp(rawIp);
    const place = [data.city, data.country_code].filter(Boolean).join(', ');
    $('networkLocation').textContent = place || data.country || 'available';
    const provider = data.connection?.isp || data.connection?.org || (data.connection?.asn ? `AS${data.connection.asn}` : 'available');
    $('networkProvider').textContent = provider;
    $('networkProvider').title = provider;
    $('revealIp').disabled = false;
    $('revealIp').textContent = 'reveal ip';
  } catch {
    setNetworkUnavailable();
  } finally {
    $('refreshNetwork').disabled = false;
  }
};

$('revealIp')?.addEventListener('click', () => {
  if (!rawIp) return;
  ipVisible = !ipVisible;
  $('networkIp').textContent = ipVisible ? rawIp : maskIp(rawIp);
  $('revealIp').textContent = ipVisible ? 'hide ip' : 'reveal ip';
});

$('refreshNetwork')?.addEventListener('click', loadNetwork);

const observer = new IntersectionObserver((entries) => {
  for (const entry of entries) {
    if (!entry.isIntersecting) continue;
    const delay = Number(entry.target.dataset.delay || 0);
    entry.target.style.transitionDelay = `${delay}ms`;
    entry.target.classList.add('visible');
    observer.unobserve(entry.target);
  }
}, { threshold: 0.08 });

document.querySelectorAll('.reveal').forEach((element) => observer.observe(element));

applyPlatform();
loadRelease();
loadNetwork();
