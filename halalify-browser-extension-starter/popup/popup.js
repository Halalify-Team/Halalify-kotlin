const toggle = document.querySelector("#protection-toggle");
const status = document.querySelector("#status");
const optionsButton = document.querySelector("#open-options");

function showStatus(message) {
  status.textContent = message;
  window.setTimeout(() => {
    if (status.textContent === message) status.textContent = "";
  }, 2500);
}

chrome.storage.local.get({ protectionEnabled: true }, ({ protectionEnabled }) => {
  toggle.checked = protectionEnabled;
});

toggle.addEventListener("change", async () => {
  const protectionEnabled = toggle.checked;
  await chrome.storage.local.set({ protectionEnabled });
  await chrome.runtime.sendMessage({ type: "PROTECTION_TOGGLED", protectionEnabled });
  showStatus(protectionEnabled ? "تم تفعيل الحماية" : "تم إيقاف الحماية");
});

optionsButton.addEventListener("click", () => {
  chrome.runtime.openOptionsPage();
});
