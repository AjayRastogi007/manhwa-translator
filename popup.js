const toggle = document.getElementById("toggle");
const statusText = document.getElementById("status");

chrome.storage.local.get(["enabled"], (result) => {
    const enabled = result.enabled ?? true;
    toggle.checked = enabled;
    updateStatus(enabled);
});

toggle.addEventListener("change", () => {
    const enabled = toggle.checked;

    chrome.storage.local.set({ enabled });

    updateStatus(enabled);

    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        chrome.tabs.sendMessage(tabs[0].id, {
            type: "TOGGLE_TRANSLATION",
            enabled
        });
    });
});

function updateStatus(enabled) {
    statusText.textContent = enabled
        ? "Extension is ON"
        : "Extension is OFF";
}