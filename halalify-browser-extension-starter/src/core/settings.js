globalThis.HalalifySettings = {
  async isProtectionEnabled() {
    const { protectionEnabled } = await chrome.storage.local.get({ protectionEnabled: true });
    return protectionEnabled;
  }
};
