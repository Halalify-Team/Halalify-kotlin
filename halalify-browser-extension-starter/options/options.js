const form = document.querySelector("#domain-form");
const input = document.querySelector("#domain-input");
const list = document.querySelector("#domain-list");
const message = document.querySelector("#message");

function renderDomains(domains) {
  list.replaceChildren();
  for (const domain of domains) {
    const item = document.createElement("li");
    const label = document.createElement("span");
    label.textContent = domain;
    const remove = document.createElement("button");
    remove.className = "remove";
    remove.textContent = "حذف";
    remove.addEventListener("click", async () => {
      const result = await chrome.runtime.sendMessage({ type: "REMOVE_BLOCKED_DOMAIN", domain });
      renderDomains(result.domains);
    });
    item.append(label, remove);
    list.append(item);
  }
}

async function loadDomains() {
  const result = await chrome.runtime.sendMessage({ type: "GET_BLOCKED_DOMAINS" });
  renderDomains(result.domains);
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const domain = input.value.trim();
  if (!domain) return;

  const result = await chrome.runtime.sendMessage({ type: "ADD_BLOCKED_DOMAIN", domain });
  if (!result.ok) {
    message.textContent = "أدخل نطاقًا صحيحًا بدون مسافات.";
    return;
  }

  input.value = "";
  message.textContent = "تمت إضافة النطاق.";
  renderDomains(result.domains);
});

loadDomains();
