'use strict';

function holdingFieldError(input) {
    switch (input.dataset.validation) {
        case 'code':
            return /^[0-9]{4}[0-9A-Z]{0,2}$/.test(input.value)
                ? ''
                : '股票代號須為 4～6 碼，前 4 碼為數字，後續限數字或大寫英文字母。';
        case 'lots':
            return /^\d+$/.test(input.value) && input.valueAsNumber >= 1 && input.valueAsNumber <= 999
                ? ''
                : '張數須為 1～999 的整數。';
        case 'price':
            return /^(?:\d+(?:\.\d{1,2})?|\.\d{1,2})$/.test(input.value) && input.valueAsNumber > 0
                ? ''
                : '每股買進價須大於 0，最多兩位小數。';
        default:
            return '';
    }
}

for (const input of document.querySelectorAll('[data-validation][data-feedback]')) {
    const feedback = document.getElementById(input.dataset.feedback);
    const serverFeedback = document.getElementById(input.dataset.feedback + '-server');

    function validate(showMessage) {
        const message = holdingFieldError(input);
        input.setCustomValidity(message);

        if (showMessage) {
            feedback.textContent = message;
            feedback.hidden = !message;
            const serverErrorVisible = serverFeedback && !serverFeedback.hidden;
            input.setAttribute('aria-invalid', String(Boolean(message || serverErrorVisible)));
        }
    }

    validate(false);
    input.addEventListener('blur', () => validate(true));
    input.addEventListener('invalid', () => validate(true));
    input.addEventListener('input', () => {
        const wasShowingError = !feedback.hidden || (serverFeedback && !serverFeedback.hidden);
        if (serverFeedback) {
            serverFeedback.hidden = true;
        }
        validate(wasShowingError);
    });
}
