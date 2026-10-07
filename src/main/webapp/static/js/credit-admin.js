(() => {
    'use strict';
    const $ = selector => document.querySelector(selector);
    const accessPanel = $('#accessPanel');
    const dashboard = $('#dashboard');
    const storageKey = 'creditAuditAdminKey';
    let data = null;
    let toastTimer;
    let replacementPlan = null;
    let aiSettings = null;

    function showToast(message) {
        const toast = $('#adminToast'); toast.textContent = message; toast.classList.add('show');
        clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.classList.remove('show'), 2500);
    }
    function number(value) { return new Intl.NumberFormat('zh-CN').format(Number(value) || 0); }
    function key() { return sessionStorage.getItem(storageKey) || ''; }
    async function request(options = {}) {
        const response = await fetch('api/credit-audit/admin', {
            ...options, credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-Credit-Admin-Key': key(), 'X-Credit-Audit-Request': '1', ...(options.headers || {})}
        });
        const payload = await response.json().catch(() => null);
        if (!response.ok || payload?.code !== 200) throw new Error(payload?.msg || '后台数据读取失败');
        return payload.data;
    }
    async function load() {
        data = await request();
        accessPanel.classList.add('is-hidden'); dashboard.classList.remove('is-hidden'); render();
        loadManagedPlans().catch(error => { $('#planUploadResult').textContent = error.message; });
        loadAiSettings().catch(error => { $('#aiSettingsResult').textContent = error.message; });
    }
    $('#accessForm').addEventListener('submit', async event => {
        event.preventDefault(); sessionStorage.setItem(storageKey, $('#adminKey').value.trim());
        $('#accessError').textContent = '';
        try { await load(); } catch (error) { sessionStorage.removeItem(storageKey); $('#accessError').textContent = error.message; }
    });
    $('#refreshButton').addEventListener('click', async () => {
        try { await load(); showToast('数据已刷新'); } catch (error) { showToast(error.message); }
    });
    $('#lockButton').addEventListener('click', () => {
        sessionStorage.removeItem(storageKey); dashboard.classList.add('is-hidden'); accessPanel.classList.remove('is-hidden'); $('#adminKey').value = '';
    });

    async function aiRequest(body) {
        const response = await fetch('api/credit-audit/admin/ai', {
            method: body ? 'POST' : 'GET', credentials: 'same-origin',
            headers: {'Content-Type': 'application/json', 'X-Credit-Admin-Key': key(), 'X-Credit-Audit-Request': '1'},
            body: body ? JSON.stringify(body) : undefined
        });
        const payload = await response.json().catch(() => null);
        if (!response.ok || payload?.code !== 200) throw new Error(payload?.msg || 'AI 设置操作失败');
        return payload.data;
    }

    async function loadAiSettings() {
        aiSettings = await aiRequest();
        renderAiSettings();
    }

    function renderAiSettings() {
        if (!aiSettings) return;
        const modelSelect = $('#deepSeekModel');
        modelSelect.replaceChildren();
        (aiSettings.models || []).forEach(model => {
            const option = document.createElement('option');
            option.value = model.id;
            option.textContent = `${model.name}（${model.id}）`;
            option.selected = model.id === aiSettings.modelId;
            modelSelect.append(option);
        });
        renderEfforts();
        renderModels();
    }

    function renderEfforts() {
        const model = (aiSettings?.models || []).find(item => item.id === $('#deepSeekModel').value);
        const efforts = ['none', ...(model?.supportedEfforts || ['low', 'high', 'max'])];
        const labels = {none: 'none（关闭思考）', low: 'low（快速）', high: 'high（深入）', max: 'max（最强）'};
        const select = $('#deepSeekEffort');
        const selected = efforts.includes(aiSettings?.reasoningEffort) ? aiSettings.reasoningEffort : 'high';
        select.replaceChildren();
        efforts.forEach(effort => {
            const option = document.createElement('option'); option.value = effort; option.textContent = labels[effort] || effort;
            option.selected = effort === selected; select.append(option);
        });
    }

    function renderModels() {
        const list = $('#modelList'); list.replaceChildren();
        (aiSettings?.models || []).forEach(model => {
            const row = document.createElement('article');
            const copy = document.createElement('div');
            const title = document.createElement('strong'); title.textContent = model.name;
            copy.append(title); row.append(copy);
            if (model.source === '手动添加') {
                const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '删除';
                remove.addEventListener('click', () => removeModel(model.id)); row.append(remove);
            }
            list.append(row);
        });
    }

    $('#deepSeekModel').addEventListener('change', renderEfforts);
    $('#aiSettingsForm').addEventListener('submit', async event => {
        event.preventDefault();
        const result = $('#aiSettingsResult'); result.textContent = '正在保存…';
        try {
            aiSettings = await aiRequest({action: 'save', apiKey: $('#deepSeekApiKey').value.trim(),
                modelId: $('#deepSeekModel').value, reasoningEffort: $('#deepSeekEffort').value});
            $('#deepSeekApiKey').value = ''; renderAiSettings(); result.textContent = 'AI 设置已保存';
        } catch (error) { result.textContent = error.message; }
    });
    $('#syncModelsButton').addEventListener('click', async () => {
        const result = $('#aiSettingsResult'); result.textContent = '正在从 DeepSeek 同步模型…';
        try { aiSettings = await aiRequest({action: 'sync'}); renderAiSettings(); result.textContent = '官方模型已同步'; }
        catch (error) { result.textContent = error.message; }
    });
    $('#testAiButton').addEventListener('click', async () => {
        const result = $('#aiSettingsResult'); result.textContent = '正在测试 DeepSeek 连接…';
        try { const response = await aiRequest({action: 'test'}); aiSettings = response.settings; renderAiSettings(); result.textContent = response.message; }
        catch (error) { result.textContent = error.message; }
    });
    $('#addModelForm').addEventListener('submit', async event => {
        event.preventDefault();
        const efforts = [...document.querySelectorAll('[name="customEffort"]:checked')].map(item => item.value);
        try {
            aiSettings = await aiRequest({action: 'addModel', modelId: $('#customModelId').value.trim(),
                modelName: $('#customModelName').value.trim(), supportedEfforts: efforts});
            event.currentTarget.reset(); renderAiSettings(); showToast('模型已添加');
        } catch (error) { showToast(error.message); }
    });
    async function removeModel(modelId) {
        try { aiSettings = await aiRequest({action: 'removeModel', modelId}); renderAiSettings(); showToast('模型已删除'); }
        catch (error) { showToast(error.message); }
    }

    $('#passwordForm').addEventListener('submit', async event => {
        event.preventDefault();
        const currentPassword = $('#currentAdminPassword').value;
        const newPassword = $('#newAdminPassword').value;
        const confirmPassword = $('#confirmAdminPassword').value;
        const result = $('#passwordResult');
        if (newPassword !== confirmPassword) { result.textContent = '两次输入的新密码不一致'; return; }
        try {
            const response = await fetch('api/credit-audit/admin/password', {
                method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', 'X-Credit-Admin-Key': key(), 'X-Credit-Audit-Request': '1'},
                body: JSON.stringify({currentPassword, newPassword, confirmPassword})
            });
            const payload = await response.json().catch(() => null);
            if (!response.ok || payload?.code !== 200) throw new Error(payload?.msg || '后台密码修改失败');
            sessionStorage.setItem(storageKey, newPassword); event.currentTarget.reset(); result.textContent = '后台密码已更新';
        } catch (error) { result.textContent = error.message; }
    });
    $('#adminPlanFile').addEventListener('change', event => {
        const selected = event.currentTarget.files?.[0];
        $('#adminPlanFileLabel').textContent = selected ? selected.name : '选择培养方案文件';
        $('#planUploadResult').textContent = selected ? `已选择：${selected.name}` : '';
    });
    $('#planUploadForm').addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const submit = $('#adminPlanSubmit');
        const result = $('#planUploadResult');
        submit.disabled = true;
        result.textContent = '正在安全解析并登记…';
        try {
            const response = await fetch('api/credit-audit/admin/plans', {
                method: 'POST', credentials: 'same-origin',
                headers: {'X-Credit-Admin-Key': key(), 'X-Credit-Audit-Request': '1'}, body: new FormData(form)
            });
            const payload = await response.json().catch(() => null);
            if (!response.ok || payload?.code !== 200) throw new Error(payload?.msg || '培养方案上传失败');
            result.textContent = `已登记：${payload.data.sourceName}`;
            form.reset();
            await load();
            showToast('培养方案已加入方案库');
        } catch (error) {
            result.textContent = error.message;
        } finally {
            submit.disabled = false;
        }
    });

    async function planRequest(options = {}) {
        const {url = 'api/credit-audit/admin/plans', ...requestOptions} = options;
        const response = await fetch(url, {
            ...requestOptions,
            credentials: 'same-origin',
            headers: {'X-Credit-Admin-Key': key(), 'X-Credit-Audit-Request': '1', ...(options.headers || {})}
        });
        const payload = await response.json().catch(() => null);
        if (!response.ok || payload?.code !== 200) throw new Error(payload?.msg || '方案库操作失败');
        return payload.data;
    }

    async function loadManagedPlans() {
        renderManagedPlans(await planRequest());
    }

    function renderManagedPlans(plans) {
        const list = $('#adminPlanList');
        list.replaceChildren();
        const items = Array.isArray(plans) ? plans : [];
        const activeCount = items.filter(item => item.active).length;
        $('#planLibraryCount').textContent = `${activeCount} 份上架 · ${items.length - activeCount} 份下架`;
        if (!items.length) {
            const empty = document.createElement('div');
            empty.className = 'empty';
            empty.textContent = '方案库暂无记录';
            list.append(empty);
            return;
        }
        items.forEach(plan => {
            const row = document.createElement('article');
            row.className = 'admin-plan-row';
            const name = document.createElement('div');
            name.className = 'admin-plan-name';
            const title = document.createElement('strong');
            title.textContent = plan.sourceName;
            name.append(title);
            const status = document.createElement('span');
            status.className = `admin-plan-state${plan.active ? '' : ' is-offline'}`;
            status.textContent = plan.active ? '已上架' : '已下架';
            const time = document.createElement('time');
            time.className = 'admin-plan-meta';
            time.textContent = new Date(plan.createdAt).toLocaleString('zh-CN', {hour12: false});
            const actions = document.createElement('div');
            actions.className = 'admin-plan-actions';
            if (plan.active) {
                const offline = document.createElement('button');
                offline.type = 'button';
                offline.textContent = '下架';
                offline.addEventListener('click', () => deactivatePlan(plan));
                actions.append(offline);
            }
            const replace = document.createElement('button');
            replace.type = 'button';
            replace.textContent = '重新上传';
            replace.addEventListener('click', () => {
                replacementPlan = plan;
                $('#replacementPlanFile').value = '';
                $('#replacementPlanFile').click();
            });
            actions.append(replace);
            row.append(name, status, time, actions);
            list.append(row);
        });
    }

    async function deactivatePlan(plan) {
        if (!window.confirm(`确认下架“${plan.sourceName}”吗？下架后学生端将不能再选择。`)) return;
        try {
            await planRequest({method: 'DELETE', url: `api/credit-audit/admin/plans?id=${encodeURIComponent(plan.id)}`});
            await loadManagedPlans();
            showToast('培养方案已下架');
        } catch (error) {
            showToast(error.message);
        }
    }

    $('#replacementPlanFile').addEventListener('change', async event => {
        const file = event.currentTarget.files?.[0];
        if (!file || !replacementPlan) return;
        const form = new FormData();
        form.append('school', replacementPlan.school);
        form.append('major', replacementPlan.major);
        form.append('cohort', replacementPlan.cohort);
        form.append('replacePlanId', replacementPlan.id);
        form.append('planFile', file);
        $('#planUploadResult').textContent = `正在重新解析：${file.name}`;
        try {
            const saved = await planRequest({method: 'POST', body: form});
            $('#planUploadResult').textContent = `已重新上传：${saved.sourceName}`;
            replacementPlan = null;
            await loadManagedPlans();
            showToast('新版本已上架，旧版本已下架');
        } catch (error) {
            $('#planUploadResult').textContent = error.message;
        }
    });

    function render() {
        $('#usersMetric').textContent = number(data.users);
        $('#auditsMetric').textContent = number(data.totalAudits);
        $('#successMetric').textContent = data.totalAudits ? `${Math.round(data.successfulAudits / data.totalAudits * 100)}%` : '0%';
        $('#reuseMetric').textContent = number(data.planReuses);
        $('#successAudits').textContent = number(data.successfulAudits); $('#failedAudits').textContent = number(data.failedAudits);
        $('#planUploads').textContent = number(data.planUploads); $('#coursesParsed').textContent = number(data.coursesParsed);
        $('#updatedAt').textContent = new Date().toLocaleString('zh-CN', {hour12: false});
        renderTrend(); renderFeedback();
    }
    function renderTrend() {
        const chart = $('#trendChart'); chart.replaceChildren();
        const days = (data.daily || []).slice(-14); const max = Math.max(1, ...days.flatMap(day => [day.users, day.audits]));
        if (!days.length) { const empty = document.createElement('div'); empty.className = 'empty'; empty.textContent = '暂无趋势数据'; chart.append(empty); return; }
        days.forEach(day => {
            const column = document.createElement('div'); column.className = 'trend-day'; column.title = `${day.date}：${day.users} 位用户，${day.audits} 次核验`;
            const bars = document.createElement('div'); bars.className = 'trend-bars';
            const users = document.createElement('i'); users.className = 'users'; users.style.height = `${Math.max(2, day.users / max * 100)}%`;
            const audits = document.createElement('i'); audits.style.height = `${Math.max(2, day.audits / max * 100)}%`; bars.append(users, audits);
            const label = document.createElement('span'); label.textContent = day.date.slice(5); column.append(bars, label); chart.append(column);
        });
    }
    function renderFeedback() {
        if (!data) return;
        const items = data.feedback || [];
        const pending = (data.feedback || []).filter(item => item.status === 'new').length;
        $('#feedbackCount').textContent = `${pending} 条待处理`;
        const list = $('#feedbackList'); list.replaceChildren();
        if (!items.length) { const empty = document.createElement('div'); empty.className = 'empty'; empty.textContent = '没有符合条件的反馈'; list.append(empty); return; }
        const labels = {bug: '功能问题', parse: '解析问题', suggest: '改进建议'};
        items.forEach(item => {
            const article = document.createElement('article'); article.className = 'feedback-item';
            const meta = document.createElement('div'); meta.className = 'feedback-meta';
            const type = document.createElement('span'); type.className = 'feedback-type'; type.textContent = labels[item.type] || '其他';
            const time = document.createElement('time'); time.textContent = new Date(item.createdAt).toLocaleString('zh-CN', {hour12: false}); meta.append(type, time);
            const content = document.createElement('div'); content.className = 'feedback-content'; const text = document.createElement('p'); text.textContent = item.content; content.append(text);
            if (item.contact) { const contact = document.createElement('small'); contact.textContent = `联系方式：${item.contact}`; content.append(contact); }
            const action = document.createElement('button'); action.type = 'button'; action.textContent = item.status === 'new' ? '标记已处理' : '重新打开';
            action.addEventListener('click', () => updateFeedback(item.id, item.status === 'new' ? 'resolved' : 'new'));
            article.append(meta, content, action); list.append(article);
        });
    }
    async function updateFeedback(id, status) {
        try { data = await request({method: 'POST', body: JSON.stringify({id, status})}); render(); showToast('反馈状态已更新'); }
        catch (error) { showToast(error.message); }
    }
    if (key()) load().catch(() => sessionStorage.removeItem(storageKey));
})();
