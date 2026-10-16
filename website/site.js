const previews = {
  overview: { title: 'Everything in perspective.', copy: 'A visual map turns a long list of files into something that makes sense. Explore by category, follow a folder, or put your biggest files first.', image: 'assets/overview.png', alt: 'Sift storage overview with a category chart and proportional file map.', icon: '#i-folder' },
  cleanup: { title: 'A good place to start.', copy: 'Old installers. Large videos. Forgotten archives. Cleanup Center explains why each file is worth a look, so you can decide what still earns its space.', image: 'assets/cleanup.png', alt: 'Sift Cleanup Center with explanations for large videos, older installers, archives, and Downloads files.', icon: '#i-shield' },
  duplicates: { title: 'Keep the memories. Lose the copies.', copy: 'Compare exact duplicates, choose the copy to keep, and review the extras. Sift checks that an identical, unchanged copy remains before moving a duplicate.', image: 'assets/duplicates.png', alt: 'Sift Duplicate Review showing identical file groups with controls for choosing a copy to keep.', icon: '#i-copy' },
};

const tabs = [...document.querySelectorAll('[data-preview]')];
function showPreview(tab) {
  const preview = previews[tab.dataset.preview];
  if (!preview) return;
  tabs.forEach(item => {
    const selected = item === tab;
    item.setAttribute('aria-selected', String(selected));
    item.tabIndex = selected ? 0 : -1;
  });
  document.getElementById('preview-panel').setAttribute('aria-labelledby', tab.id);
  document.getElementById('preview-title').textContent = preview.title;
  document.getElementById('preview-copy').textContent = preview.copy;
  document.getElementById('preview-symbol').setAttribute('href', preview.icon);
  const img = document.getElementById('preview-image');
  img.src = preview.image;
  img.alt = preview.alt;
}
tabs.forEach((tab, index) => {
  tab.addEventListener('click', () => showPreview(tab));
  tab.addEventListener('keydown', event => {
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    showPreview(tabs[next]);
    tabs[next].focus();
  });
});

const macLink = document.getElementById('mac-download');
const macAlternate = document.getElementById('mac-alternate');
const macButtons = [...document.querySelectorAll('[data-mac]')];
macButtons.forEach(button => button.addEventListener('click', () => {
  const arm = button.dataset.mac === 'arm';
  macButtons.forEach(item => item.setAttribute('aria-pressed', String(item === button)));
  macLink.href = arm ? macAlternate.dataset.armUrl : macAlternate.dataset.intelUrl;
  macLink.dataset.asset = arm ? 'mac-arm' : 'mac-intel';
  const meta = document.getElementById('mac-meta');
  meta.textContent = `DMG · ${arm ? macAlternate.dataset.armSize : macAlternate.dataset.intelSize} · ${arm ? 'M1 or newer' : 'Intel processor'}`;
  macAlternate.href = arm ? macAlternate.dataset.intelUrl : macAlternate.dataset.armUrl;
  macAlternate.replaceChildren(document.createTextNode(arm ? 'Get the Intel Mac version ' : 'Get the Apple Silicon version '));
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('icon');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#i-arrow');
  svg.append(use);
  macAlternate.append(svg);
}));

// Recommend a platform without guessing the processor or hiding other downloads.
const platform = navigator.userAgentData?.platform || navigator.platform || '';
const mobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent) || (/Mac/i.test(platform) && navigator.maxTouchPoints > 1);
const system = mobile ? null : /Win/i.test(platform) ? 'windows' : /Mac/i.test(platform) ? 'macos' : /Linux/i.test(platform) ? 'linux' : null;
if (system) {
  const card = document.querySelector(`[data-platform="${system}"]`);
  card?.classList.add('is-recommended');
  const label = card?.querySelector('.recommended');
  if (label) label.hidden = false;
}
