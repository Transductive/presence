/* Classical Bloom sizing; this is an estimate, not benchmark evidence. */
(() => {
    const form = document.getElementById("sizing-form");
    if (!form) return;
    function update() {
        const n = Number(document.getElementById("capacity").value);
        const p = Number(document.getElementById("probability").value);
        const bits = Math.ceil(-n * Math.log(p) / (Math.LN2 ** 2));
        const hashes = Math.max(1, Math.round(bits / n * Math.LN2));
        const bytes = Math.ceil(bits / 64) * 8;
        document.getElementById("bit-storage").textContent = bytes < 1048576
            ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1048576).toFixed(1)} MiB`;
        document.getElementById("bit-count").textContent = bits.toLocaleString("en-US");
        document.getElementById("hash-count").textContent = String(hashes);
        document.getElementById("bits-per-value").textContent = (bits / n).toFixed(2);
    }
    form.addEventListener("change", update);
    form.addEventListener("submit", event => event.preventDefault());
    update();
})();
