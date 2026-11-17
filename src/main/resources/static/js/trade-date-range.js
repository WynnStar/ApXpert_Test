'use strict';

const form = document.querySelector('[data-trade-date-url]');
if (form) {
    const code = form.querySelector('[name="code"]');
    const date = form.querySelector('[name="tradeDate"]');
    const hint = form.querySelector('#trade-date-hint');
    let pending;

    code.addEventListener('input', () => {
        pending?.abort();
        date.min = form.dataset.defaultMin;
        hint.textContent = `最早可登記 ${date.min}`;
    });

    code.addEventListener('blur', async () => {
        if (!code.value || !code.validity.valid) return;
        pending?.abort();
        const request = new AbortController();
        pending = request;
        try {
            const query = new URLSearchParams({ code: code.value });
            const response = await fetch(`${form.dataset.tradeDateUrl}?${query}`, { signal: request.signal });
            if (!response.ok || request.signal.aborted) return;
            const range = await response.json();
            if (request.signal.aborted) return;
            date.min = range.minDate;
            hint.textContent = range.firstListingDate
                ? `首次上市或上櫃日為 ${range.firstListingDate}，最早可登記 ${range.minDate}`
                : `首次掛牌日未能確認，最早可登記 ${range.minDate}`;
        } catch (error) {
            if (!request.signal.aborted) {
                hint.textContent = `暫時無法確認首次掛牌日，最早可登記 ${date.min}`;
            }
        }
    });
}
