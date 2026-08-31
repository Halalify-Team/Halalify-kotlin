importScripts("../core/blocking-rules.js");

async function initialize() {
  await chrome.storage.local.get({ protectionEnabled: true, blockedDomains: [] });
  await globalThis.HalalifyBlocking.syncNetworkRules();
}

chrome.runtime.onInstalled.addListener(initialize);
chrome.runtime.onStartup.addListener(initialize);

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    if (message.type === "PROTECTION_TOGGLED") {
      await globalThis.HalalifyBlocking.syncNetworkRules();
      const tabs = await chrome.tabs.query({});
      for (const tab of tabs) {
        if (tab.id) chrome.tabs.sendMessage(tab.id, message).catch(() => {});
      }
      sendResponse({ ok: true });
      return;
    }
    if (message.type === "GET_BLOCKED_DOMAINS") {
      sendResponse({ domains: await globalThis.HalalifyBlocking.getBlockedDomains() });
      return;
    }
    if (message.type === "ADD_BLOCKED_DOMAIN") {
      sendResponse(await globalThis.HalalifyBlocking.addBlockedDomain(message.domain));
      return;
    }
    if (message.type === "REMOVE_BLOCKED_DOMAIN") {
      sendResponse({ domains: await globalThis.HalalifyBlocking.removeBlockedDomain(message.domain) });
    }
  })();
  return true;
});
