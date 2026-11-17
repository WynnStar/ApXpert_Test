'use strict';

const addErrorSummary = document.querySelector('[data-add-error-summary]');
addErrorSummary?.focus();

const codeInput = document.querySelector('[data-stock-lookup-url]');
if (codeInput) {
    const result = document.getElementById('stock-lookup-result');
    let active;

    codeInput.addEventListener('input', () => {
        active?.abort();
        result.textContent = '';
        if (addErrorSummary) {
            addErrorSummary.hidden = true;
        }
    });

    codeInput.addEventListener('blur', async () => {
        if (!codeInput.validity.valid) return;

        active?.abort();
        const current = new AbortController();
        active = current;
        result.textContent = '查詢股票代號中…';
        try {
            const query = new URLSearchParams({code: codeInput.value});
            const response = await fetch(codeInput.dataset.stockLookupUrl + '?' + query, {
                signal: current.signal
            });
            if (!response.ok) throw new Error('Stock lookup failed');
            const stock = await response.json();
            if (!current.signal.aborted) result.textContent = stock.message;
        } catch (error) {
            if (!current.signal.aborted) result.textContent = '暫時無法驗證股票代號';
        }
    });
}
