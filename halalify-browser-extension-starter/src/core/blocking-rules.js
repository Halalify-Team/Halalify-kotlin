const DEFAULT_BLOCKED_DOMAINS = [];

globalThis.HalalifyBlocking = {
  normalizeDomain(value) {
    return value.trim().toLowerCase()
      .replace(/^https?:\/\//, "")
      .replace(/^www\./, "")
      .split("/")[0];
  },

  async getBlockedDomains() {
    const result = await chrome.storage.local.get({ blockedDomains: DEFAULT_BLOCKED_DOMAINS });
    return result.blockedDomains;
  },

  async saveDomains(domains) {
    await chrome.storage.local.set({ blockedDomains: domains });
    await this.syncNetworkRules();
  },

  async addBlockedDomain(value) {
    const domain = this.normalizeDomain(value);
    if (!domain || domain.includes(" ") || !domain.includes(".")) return { ok: false, domains: await this.getBlockedDomains() };
    const domains = await this.getBlockedDomains();
    if (!domains.includes(domain)) domains.push(domain);
    await this.saveDomains(domains);
    return { ok: true, domains };
  },

  async removeBlockedDomain(value) {
    const domain = this.normalizeDomain(value);
    const domains = (await this.getBlockedDomains()).filter((item) => item !== domain);
    await this.saveDomains(domains);
    return domains;
  },

  async syncNetworkRules() {
    const { protectionEnabled } = await chrome.storage.local.get({ protectionEnabled: true });
    const domains = protectionEnabled ? await this.getBlockedDomains() : [];
    const currentRules = await chrome.declarativeNetRequest.getDynamicRules();
    const removeRuleIds = currentRules.map((rule) => rule.id);
    const addRules = domains.map((domain, index) => ({
      id: 1000 + index,
      priority: 1,
      action: { type: "block" },
      condition: { urlFilter: `||${domain}^`, resourceTypes: ["main_frame"] }
    }));
    await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds, addRules });
  }
};
