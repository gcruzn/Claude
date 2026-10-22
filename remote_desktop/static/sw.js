// Service worker mínimo: hace que Android ofrezca instalar Mi PC como app.
// No guarda nada en caché: todo viene siempre en vivo desde el PC.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
