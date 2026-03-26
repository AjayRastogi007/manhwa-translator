chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.type === "TRANSLATE_URL") {

        console.log("➡️ Sending to backend:", request.url);

        let responded = false;

        const safeSend = (data) => {
            if (!responded) {
                responded = true;
                sendResponse(data);
            }
        };

        const timeout = setTimeout(() => {
            console.warn("⏰ Timeout fallback");
            safeSend({ base64: null });
        }, 60000);

        fetch("https://manhwa-translator.onrender.com/api/translate-url", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ url: request.url })
        })
            .then(async (res) => {
                console.log("⬅️ Backend response status:", res.status);
                const blob = await res.blob();

                return new Promise((resolve) => {
                    const reader = new FileReader();
                    reader.onloadend = () => resolve(reader.result);
                    reader.onerror = () => resolve(null);
                    reader.readAsDataURL(blob);
                });
            })
            .then((base64) => {
                clearTimeout(timeout);
                safeSend({ base64 });
            })
            .catch(err => {
                clearTimeout(timeout);
                console.error("❌ Background error:", err);
                safeSend({ base64: null });
            });

        return true;
    }
});