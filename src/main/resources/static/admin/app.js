const $ = (id) => document.getElementById(id);
let state = null;

async function api(url, options = {}) {
    const response = await fetch(url, {
        headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
        ...options
    });
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    if (!response.ok) {
        throw new Error(body?.message || body?.error_description || body?.error || `HTTP ${response.status}`);
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

function fillModel(prefix, model) {
    $(`${prefix}Provider`).value = model.provider || 'OPENAI';
    $(`${prefix}Endpoint`).value = model.endpoint || '';
    $(`${prefix}ModelId`).value = model.modelId || '';
    $(`${prefix}ApiKey`).value = '';
    $(`${prefix}KeyState`).textContent = model.apiKeyConfigured
        ? 'Ключ сохранён. Оставьте поле пустым, чтобы не менять его.'
        : 'Ключ пока не сохранён.';
}

function readModel(prefix) {
    return {
        provider: $(`${prefix}Provider`).value,
        endpoint: $(`${prefix}Endpoint`).value.trim(),
        modelId: $(`${prefix}ModelId`).value.trim(),
        apiKey: $(`${prefix}ApiKey`).value.trim()
    };
}

function render(config) {
    state = config;
    fillModel('simple', config.simpleModel);
    fillModel('complex', config.complexModel);
    $('bitrixWebhook').value = config.bitrix.webhookUrl || '';
    $('bitrixBotId').value = config.bitrix.botId || '';
    $('bitrixBotCode').value = config.bitrix.botCode || '';
    $('bitrixBotName').value = config.bitrix.botName || '';
    $('bitrixWorkPosition').value = config.bitrix.workPosition || '';
    $('relevancePrompt').value = config.relevancePrompt || '';
    $('analysisPrompt').value = config.analysisPrompt || '';
    $('irrelevantReply').value = config.irrelevantReply || '';
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
        simpleModel: readModel('simple'),
        complexModel: readModel('complex'),
        bitrix: {
            webhookUrl: $('bitrixWebhook').value.trim(),
            botId: state?.bitrix?.botId || null,
            botCode: $('bitrixBotCode').value.trim(),
            botName: $('bitrixBotName').value.trim(),
            workPosition: $('bitrixWorkPosition').value.trim()
        },
        relevancePrompt: $('relevancePrompt').value,
        analysisPrompt: $('analysisPrompt').value,
        irrelevantReply: $('irrelevantReply').value,
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
    setMessage('Загружаю настройки…');
    const [config, status] = await Promise.all([
        api('/api/admin/config'),
        api('/api/admin/status')
    ]);
    render(config);
    const ready = status.simpleModelConfigured && status.complexModelConfigured && status.botRegistered;
    $('globalStatus').textContent = ready ? `Готов · bot ${status.botId}` : 'Требуется настройка';
    $('globalStatus').className = `status-pill ${ready ? 'ok' : ''}`;
    setMessage('Настройки загружены.', 'ok');
}

async function save() {
    const button = $('save');
    setBusy(button, true);
    try {
        const saved = await api('/api/admin/config', { method: 'PUT', body: JSON.stringify(collect()) });
        render(saved);
        setMessage('Настройки сохранены.', 'ok');
    } catch (error) {
        setMessage(error.message, 'error');
        throw error;
    } finally {
        setBusy(button, false);
    }
}

async function action(button, url, successText) {
    setBusy(button, true);
    try {
        const result = await api(url, { method: 'POST' });
        setMessage(`${successText} ${result?.result?.bot?.id ? `ID: ${result.result.bot.id}` : ''}`.trim(), 'ok');
        await load();
    } catch (error) {
        setMessage(error.message, 'error');
    } finally {
        setBusy(button, false);
    }
}

$('save').addEventListener('click', save);
$('reload').addEventListener('click', () => load().catch(error => setMessage(error.message, 'error')));
$('registerBitrix').addEventListener('click', async (event) => {
    await save();
    await action(event.currentTarget, '/api/admin/bitrix/register', 'Бот зарегистрирован.');
});
$('checkBitrix').addEventListener('click', async (event) => {
    await save();
    await action(event.currentTarget, '/api/admin/bitrix/check', 'Бот найден.');
});
document.querySelectorAll('.test-model').forEach(button => {
    button.addEventListener('click', async () => {
        await save();
        await action(button, `/api/admin/models/test?target=${button.dataset.target}`, 'API отвечает.');
    });
});

load().catch(error => setMessage(error.message, 'error'));
