const $ = id => document.getElementById(id);
let state = null;

function newOperationId() {
    if (globalThis.crypto?.randomUUID) {
        return globalThis.crypto.randomUUID().replaceAll('-', '').slice(0, 16);
    }
    return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
}

function browserLog(level, message, details = {}) {
    const logger = console[level] || console.log;
    logger.call(console, `[BITRIX BOT ADMIN] ${message}`, details);
}

function responseSummary(body) {
    if (body === null || body === undefined) return body;
    if (typeof body === 'string') return { type: 'text', length: body.length };
    if (Array.isArray(body)) return { type: 'array', length: body.length };
    return {
        type: 'object',
        keys: Object.keys(body),
        ok: body.ok,
        error: body.error,
        message: body.message,
        botId: body?.result?.bot?.id ?? body?.result?.id ?? body?.result?.botId ?? body?.result
    };
}

async function api(url, options = {}) {
    const operationId = newOperationId();
    const method = options.method || 'GET';
    const started = performance.now();

    browserLog('info', `HTTP -> ${method} ${url}`, { operationId });

    let response;
    try {
        response = await fetch(url, {
            ...options,
            headers: {
                'Content-Type': 'application/json',
                'X-Operation-Id': operationId,
                ...(options.headers || {})
            }
        });
    } catch (networkError) {
        browserLog('error', `HTTP !! ${method} ${url}: network error`, {
            operationId,
            error: networkError
        });
        const error = new Error(`Сетевая ошибка. Operation ID: ${operationId}`);
        error.operationId = operationId;
        throw error;
    }

    const text = await response.text();
    let body = null;
    try {
        body = text ? JSON.parse(text) : null;
    } catch {
        body = text;
    }

    const durationMs = Math.round(performance.now() - started);
    browserLog(response.ok ? 'info' : 'error', `HTTP <- ${method} ${url}`, {
        operationId,
        status: response.status,
        durationMs,
        response: responseSummary(body)
    });

    if (!response.ok) {
        const message = body?.message || body?.error_description || body?.error || `HTTP ${response.status}`;
        const error = new Error(`${message} [operationId=${operationId}]`);
        error.operationId = operationId;
        error.status = response.status;
        error.body = body;
        throw error;
    }
    return body;
}

function setMessage(text, type = '') {
    const box = $('message');
    box.textContent = text;
    box.className = `message ${type}`;
}

function setBusy(button, busy) {
    button.disabled = busy;
    if (busy) {
        button.dataset.oldText = button.textContent;
        button.textContent = 'Выполняется…';
    } else if (button.dataset.oldText) {
        button.textContent = button.dataset.oldText;
    }
}

function fillModel(model) {
    $('complexProvider').value = model.provider || 'OPENAI';
    $('complexEndpoint').value = model.endpoint || '';
    $('complexModelId').value = model.modelId || '';
    $('complexApiKey').value = '';
    $('complexKeyState').textContent = model.apiKeyConfigured
        ? 'Ключ сохранён. Оставьте поле пустым, чтобы не менять его.'
        : 'Ключ пока не сохранён.';
}

function readModel() {
    return {
        provider: $('complexProvider').value,
        endpoint: $('complexEndpoint').value.trim(),
        modelId: $('complexModelId').value.trim(),
        apiKey: $('complexApiKey').value.trim()
    };
}

function render(config) {
    state = config;
    fillModel(config.complexModel);
    $('bitrixWebhook').value = config.bitrix.webhookUrl || '';
    $('bitrixBotId').value = config.bitrix.botId || '';
    $('bitrixBotCode').value = config.bitrix.botCode || '';
    $('bitrixBotName').value = config.bitrix.botName || '';
    $('bitrixWorkPosition').value = config.bitrix.workPosition || '';
    $('analysisPrompt').value = config.analysisPrompt || '';
    $('noFilesReply').value = config.noFilesReply || 'Прикрепите файлы документов';
    $('processingReply').value = config.processingReply || '';
    $('errorReply').value = config.errorReply || '';
    $('maxFileCount').value = config.maxFileCount;
    $('maxFileSizeMb').value = Math.round(config.maxFileSizeBytes / 1024 / 1024);
    $('maxCharsPerFile').value = config.maxExtractedCharsPerFile;
    $('maxTotalChars').value = config.maxTotalExtractedChars;
    $('chunkSize').value = config.outgoingMessageChunkSize;
}

function collect() {
    return {
        complexModel: readModel(),
        bitrix: {
            webhookUrl: $('bitrixWebhook').value.trim(),
            botId: state?.bitrix?.botId || null,
            botCode: $('bitrixBotCode').value.trim(),
            botName: $('bitrixBotName').value.trim(),
            workPosition: $('bitrixWorkPosition').value.trim()
        },
        analysisPrompt: $('analysisPrompt').value,
        noFilesReply: $('noFilesReply').value,
        processingReply: $('processingReply').value,
        errorReply: $('errorReply').value,
        maxFileCount: Number($('maxFileCount').value),
        maxFileSizeBytes: Number($('maxFileSizeMb').value) * 1024 * 1024,
        maxExtractedCharsPerFile: Number($('maxCharsPerFile').value),
        maxTotalExtractedChars: Number($('maxTotalChars').value),
        outgoingMessageChunkSize: Number($('chunkSize').value)
    };
}

async function load() {
    browserLog('info', 'LOAD settings started');
    setMessage('Загружаю настройки…');
    const [config, status] = await Promise.all([
        api('/api/admin/config'),
        api('/api/admin/status')
    ]);
    render(config);
    const ready = status.complexModelConfigured && status.botRegistered;
    $('globalStatus').textContent = ready ? `Готов · bot ${status.botId}` : 'Требуется настройка';
    $('globalStatus').className = `status-pill ${ready ? 'ok' : ''}`;
    setMessage('Настройки загружены.', 'ok');
    browserLog('info', 'LOAD settings completed', {
        ready,
        botId: status.botId,
        complexModelConfigured: status.complexModelConfigured
    });
}

async function save() {
    const button = $('save');
    setBusy(button, true);
    browserLog('info', 'SAVE button operation started');
    try {
        const saved = await api('/api/admin/config', { method: 'PUT', body: JSON.stringify(collect()) });
        render(saved);
        setMessage('Настройки сохранены.', 'ok');
        browserLog('info', 'SAVE button operation completed');
        return saved;
    } catch (error) {
        setMessage(error.message, 'error');
        browserLog('error', 'SAVE button operation failed', { error });
        throw error;
    } finally {
        setBusy(button, false);
    }
}

function botIdFromResult(result) {
    const candidate = result?.result?.bot?.id
        ?? result?.result?.id
        ?? result?.result?.botId
        ?? result?.result?.BOT_ID
        ?? (typeof result?.result === 'number' ? result.result : null);
    return candidate || null;
}

async function action(button, url, successText) {
    setBusy(button, true);
    browserLog('info', `ACTION started ${url}`);
    try {
        const result = await api(url, { method: 'POST' });
        const botId = botIdFromResult(result);
        setMessage(`${successText}${botId ? ` ID: ${botId}` : ''}`, 'ok');
        browserLog('info', `ACTION completed ${url}`, { botId, response: responseSummary(result) });
        await load();
        return result;
    } catch (error) {
        setMessage(error.message, 'error');
        browserLog('error', `ACTION failed ${url}`, { error });
        throw error;
    } finally {
        setBusy(button, false);
    }
}

async function saveThenAction(button, url, successText) {
    try {
        await save();
        await action(button, url, successText);
    } catch (error) {
        // save() or action() already displayed and logged the precise error.
    }
}

$('save').addEventListener('click', () => {
    save().catch(() => {});
});

$('reload').addEventListener('click', () => {
    browserLog('info', 'RELOAD button clicked');
    load().catch(error => setMessage(error.message, 'error'));
});

$('registerBitrix').addEventListener('click', event => {
    browserLog('info', 'REGISTER BOT button clicked');
    saveThenAction(event.currentTarget, '/api/admin/bitrix/register', 'Бот зарегистрирован.');
});

$('checkBitrix').addEventListener('click', event => {
    browserLog('info', 'CHECK BOT button clicked');
    saveThenAction(event.currentTarget, '/api/admin/bitrix/check', 'Бот найден.');
});

$('testModel').addEventListener('click', event => {
    browserLog('info', 'TEST MODEL button clicked', { target: 'complex' });
    saveThenAction(event.currentTarget, '/api/admin/model/test', 'API отвечает.');
});

window.addEventListener('error', event => {
    browserLog('error', 'Unhandled browser error', {
        message: event.message,
        source: event.filename,
        line: event.lineno,
        column: event.colno
    });
});

window.addEventListener('unhandledrejection', event => {
    browserLog('error', 'Unhandled Promise rejection', { reason: event.reason });
});

browserLog('info', 'Admin frontend initialized');
load().catch(error => setMessage(error.message, 'error'));
