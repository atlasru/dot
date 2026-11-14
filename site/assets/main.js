const repo = 'atlasru/dot';
const releasePage = `https://github.com/${repo}/releases/latest`;

const $ = (id) => document.getElementById(id);
const formatBytes = (bytes) => {
  const units = ['B', 'KiB', 'MiB', 'GiB'];
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
    lead.textContent = 'Minimal VLESS and Hysteria 2 client for Android.';
    note.textContent = 'Android detected · debug APK available';
    setPrimary('download for android', $('androidDownload').href);
    return;
  }

  if (kind === 'windows') {
    lead.textContent = 'Minimal VLESS and Hysteria 2 client for Windows.';
    note.textContent = 'Windows detected · portable x64 build available';
    setPrimary('download for windows', $('desktopDownload').href);
    return;
  }

  if (kind === 'ios') {
    lead.textContent = 'Minimal VLESS and Hysteria 2 client for Android & Windows.';
    note.textContent = 'No iOS build currently available';
    setPrimary('view releases', releasePage);
    return;
  }

  if (kind === 'mac') {
    lead.textContent = 'Minimal VLESS and Hysteria 2 client for Android & Windows.';
    note.textContent = 'Current desktop build targets Windows x64';
    setPrimary('view releases', releasePage);
    return;
  }

  if (kind === 'linux') {
    lead.textContent = 'Minimal VLESS and Hysteria 2 client for Android & Windows.';
    note.textContent = 'Current packaged desktop build targets Windows x64';
    setPrimary('view releases', releasePage);
    return;
  }

  lead.textContent = 'Minimal VLESS and Hysteria 2 client for Android & Windows.';
  note.textContent = 'Android APK and Windows x64 builds available';
  setPrimary('view releases', releasePage);
};

const parseTag = (tag) => {
  const match = /^v(\d+)\.(\d+)\.(\d+)$/.exec(tag);
  return match ? match.slice(1).map(Number) : null;
};

const loadRelease = async () => {
  try {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' }
    });
    if (!response.ok) throw new Error(`GitHub ${response.status}`);
    const release = await response.json();
    const assets = Array.isArray(release.assets) ? release.assets : [];
    const tag = release.tag_name;
    const version = parseTag(tag);
    if (!version || release.draft !== false || release.prerelease !== false) return;
    const fallbackVersion = parseTag($('androidVersion').textContent.trim());
    const differingPart = version.findIndex((part, index) => part !== fallbackVersion[index]);
    if (differingPart >= 0 && version[differingPart] < fallbackVersion[differingPart]) return;

    const base = `https://github.com/${repo}/releases`;
    const assetFor = (name) => assets.find((asset) => asset?.name === name &&
      asset.browser_download_url === `${base}/download/${tag}/${name}` &&
      Number.isSafeInteger(asset.size) && asset.size > 0);
    const android = assetFor(`dot-android-${tag.slice(1)}-dev-debug.apk`);
    const desktop = assetFor(`dot-desktop-${tag.slice(1)}-windows-x64.zip`);
    const checksums = assetFor('SHA256SUMS');
    if (!android || !desktop || !checksums) return;

    const updateCard = (platform, asset) => {
      $(`${platform}Version`).textContent = tag;
      $(`${platform}Download`).href = asset.browser_download_url;
      $(`${platform}Size`).textContent = formatBytes(asset.size);
      const hash = $(`${platform}Hash`);
      if (/^sha256:[a-f0-9]{64}$/i.test(asset.digest || '')) {
        hash.textContent = asset.digest.replace(/^sha256:/i, 'SHA-256: ');
      } else if (differingPart >= 0) {
        hash.textContent = 'See SHA256SUMS for this release';
      }
    };
    updateCard('android', android);
    updateCard('desktop', desktop);
    $('selectedRelease').href = `${base}/tag/${tag}`;
    $('releaseChecksums').href = checksums.browser_download_url;
  } catch {
    // The HTML already contains a complete set of verified download links.
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

if ('IntersectionObserver' in window) {
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const delay = Number(entry.target.dataset.delay || 0);
      entry.target.style.transitionDelay = `${delay}ms`;
      entry.target.classList.add('visible');
      observer.unobserve(entry.target);
    }
  }, { threshold: 0.08 });
  document.documentElement.classList.add('animate-reveals');
  document.querySelectorAll('.reveal').forEach((element) => observer.observe(element));
}

applyPlatform();
loadRelease();
loadNetwork();
