const analyzed = new WeakSet();
let protectionEnabled = true;

function hideElement(element) {
  element.classList.add("halalify-hidden-content");
  element.setAttribute("data-halalify-reason", "محتوى غير مناسب");
}

function scanImages() {
  if (!protectionEnabled) return;
  for (const image of document.images) {
    if (analyzed.has(image) || !image.complete || image.naturalWidth < 80) continue;
    analyzed.add(image);
    globalThis.HalalifyModel.classifyImage(image).then((result) => {
      if (result.isExplicit) hideElement(image);
    }).catch(() => {});
  }
}

function scanVideos() {
  if (!protectionEnabled) return;
  for (const video of document.querySelectorAll("video")) {
    if (analyzed.has(video)) continue;
    analyzed.add(video);
    globalThis.HalalifyModel.classifyVideoFrame(video).then((result) => {
      if (result.isExplicit) hideElement(video);
    }).catch(() => {});
  }
}

async function initialize() {
  protectionEnabled = await globalThis.HalalifySettings.isProtectionEnabled();
  if (!protectionEnabled) return;
  scanImages();
  scanVideos();
  const observer = new MutationObserver(() => {
    scanImages();
    scanVideos();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
}

chrome.runtime.onMessage.addListener((message) => {
  if (message.type !== "PROTECTION_TOGGLED") return;
  protectionEnabled = message.protectionEnabled;
  if (protectionEnabled) {
    scanImages();
    scanVideos();
  }
});

initialize();
