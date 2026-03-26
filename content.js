let ENABLED = true;

chrome.runtime.onMessage.addListener((msg) => {
    if (msg.type === "TOGGLE_TRANSLATION") {
        ENABLED = msg.enabled;

        console.log("Extension enabled:", ENABLED);

        if (ENABLED) {
            observeImages();
        }
    }
});

console.log("Manhwa Translator Loaded");

let queue = [];
const cache = new Map();
const MAX_CACHE = 50;
const MAX_QUEUE = 10;
const MAX_CONCURRENT = 2;
let active = 0;

const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {

        if (!ENABLED) return;

        if (entry.isIntersecting) {
            const img = entry.target;

            if (
                img.dataset.translated !== "true" &&
                img.dataset.queued !== "1"
            ) {
                img.dataset.queued = "1";
                img.style.opacity = "0.6";

                addToQueue(img);

                if (!processQueue.scheduled) {
                    processQueue.scheduled = true;

                    setTimeout(() => {
                        processQueue();
                        processQueue.scheduled = false;
                    }, 100);
                }
            }
        }
    });
}, {
    root: null,
    rootMargin: "600px",
    threshold: 0.1
});

function observeImages() {
    const images = document.querySelectorAll("img");

    images.forEach(img => {

        if (img.dataset.translated === "true") return;
        if (img.dataset.queued === "1") return;

        if (img.complete) {
            if (isValidManhwaImage(img)) {
                if (img.dataset.observing === "1") return;

                img.dataset.observing = "1";

                img.style.transition = "opacity 0.3s ease";
                observer.observe(img);
            }
        } else {
            img.addEventListener("load", () => {
                if (isValidManhwaImage(img)) {
                    if (img.dataset.observing === "1") return;

                    img.dataset.observing = "1";

                    img.style.transition = "opacity 0.3s ease";
                    observer.observe(img);
                }
            }, { once: true });
        }
    });
}

function isValidManhwaImage(img) {
    return (
        img.src &&
        img.src.startsWith("http") &&
        !img.src.startsWith("blob:") &&
        !img.src.endsWith(".gif") &&
        !img.src.includes("logo") &&
        img.naturalWidth > 300 &&
        img.naturalHeight > 500
    );
}

chrome.storage.local.get(["enabled"], (result) => {
    ENABLED = result.enabled ?? true;
    console.log("Initial ENABLED:", ENABLED);
    observeImages();
});

function base64ToBlob(base64) {
    const byteString = atob(base64.split(',')[1]);
    const mimeString = base64.split(',')[0].split(':')[1].split(';')[0];

    const ab = new ArrayBuffer(byteString.length);
    const ia = new Uint8Array(ab);

    for (let i = 0; i < byteString.length; i++) {
        ia[i] = byteString.charCodeAt(i);
    }

    return new Blob([ab], { type: mimeString });
}

async function sendToBackendUrl(img) {
    return new Promise((resolve) => {
        chrome.runtime.sendMessage(
            {
                type: "TRANSLATE_URL",
                url: img.src
            },
            (response) => {
                if (!response?.base64) {
                    resolve(null);
                    return;
                }
                resolve(base64ToBlob(response.base64));
            }
        );
    });
}

function overlayImage(originalImg, translatedBlob) {
    if (!translatedBlob) return;

    if (originalImg.dataset.blobUrl) {
        URL.revokeObjectURL(originalImg.dataset.blobUrl);
    }

    const url = URL.createObjectURL(translatedBlob);
    originalImg.src = url;
    originalImg.dataset.blobUrl = url;
    originalImg.style.opacity = "1";
    originalImg.dataset.translated = "true";
    originalImg.dataset.queued = "1";
}

async function translateImage(img) {
    if (!ENABLED) return;
    if (img.dataset.translated === "true") return;

    try {
        const url = img.src;

        if (cache.has(url)) {
            overlayImage(img, cache.get(url));
            return;
        }

        console.log("Sending URL to backend:", url);

        const translated = await Promise.race([
            sendToBackendUrl(img),
            new Promise(resolve => setTimeout(() => resolve(null), 42000))
        ]);

        if (!translated) {
            img.dataset.queued = "0";
            img.style.opacity = "1";
            return;
        }

        if (cache.size >= MAX_CACHE) {
            const firstKey = cache.keys().next().value;
            cache.delete(firstKey);
        }

        cache.set(url, translated);
        overlayImage(img, translated);

    } catch (e) {
        console.error("Translate pipeline failed:", e);
        img.dataset.queued = "0";
        img.style.opacity = "1";
    }
}

async function processQueue() {
    if (active >= MAX_CONCURRENT) return;

    while (queue.length > 0 && active < MAX_CONCURRENT) {
        const item = queue.shift();
        const img = item.img;
        active++;

        translateImage(img)
            .catch(err => console.error(err))
            .finally(() => {
                active--;
                processQueue();

                if (queue.length === 0 && active === 0 && ENABLED) {
                    setTimeout(() => {
                        console.log("🔁 Re-scanning images...");
                        observeImages();
                    }, 500);
                }
            });
    }
}

function addToQueue(img) {
    if (queue.some(item => item.img === img)) return;
    if (queue.length >= MAX_QUEUE) return;

    const rect = img.getBoundingClientRect();
    const item = {
        img,
        top: Math.max(-1000, Math.min(1000, rect.top))
    };

    let inserted = false;

    for (let i = 0; i < queue.length; i++) {
        if (Math.abs(item.top) < Math.abs(queue[i].top)) {
            queue.splice(i, 0, item);
            inserted = true;
            break;
        }
    }

    if (!inserted) queue.push(item);
}

const domObserver = new MutationObserver(() => {
    observer.disconnect();
    observeImages();
});

domObserver.observe(document.body, {
    childList: true,
    subtree: true
});

setInterval(() => {
    if (!ENABLED) return;

    if (queue.length === 0 && active === 0) {
        console.log("🛠 Watchdog re-trigger");
        observeImages();
    }
}, 10000);