(() => {
    'use strict';
    const planExtensions = new Set(['pdf', 'doc', 'docx', 'xls', 'xlsx', 'html', 'htm', 'csv', 'txt']);
    const scoreExtensions = new Set([...planExtensions, 'zip']);
    const maxBytes = 8 * 1024 * 1024;
    const state = {planFile: null, scoreFile: null, planHash: '', planId: '', planSource: '', plan: null, availablePlans: [], modules: [], courses: [], aiVerification: null, lastResult: null, resultActivated: false, editRevision: 0, resultRevision: -1, calculationCount: 0};
    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
    const elements = {
        planInput: $('#planFile'), scoreInput: $('#scoreFile'), planState: $('#planState'), scoreState: $('#scoreState'),
        consent: $('#privacyConsent'), parse: $('#parseButton'), review: $('#review'), result: $('#result'),
        moduleRows: $('#moduleRows'), courseRows: $('#courseRows'), warnings: $('#parseWarnings'),
        courseCount: $('#courseCount'), calculate: $('#calculateButton'), toast: $('#toast'), privacy: $('#privacyDialog'),
        school: $('#schoolInput'), major: $('#majorInput'), cohort: $('#cohortInput'), cache: $('#cacheState'),
        planLibrary: $('#planLibrary'), choosePlanFile: $('#choosePlanFileButton'), feedback: $('#feedbackDialog'), feedbackForm: $('#feedbackForm'),
        resultFreshness: $('#resultFreshness'), aiReviewFreshness: $('#aiReviewFreshness')
    };
    let toastTimer;

    function showToast(message) {
        elements.toast.textContent = message;
        elements.toast.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => elements.toast.classList.remove('show'), 2800);
    }
    function extension(file) { return file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : ''; }
    function validateFile(kind, file) {
        if (!file) return '请选择文件';
        const supported = kind === 'score' ? scoreExtensions : planExtensions;
        if (!supported.has(extension(file))) return kind === 'score'
            ? '仅支持文档、网页或 ZIP 成绩文件'
            : '仅支持 PDF、Word、Excel、HTML、CSV、TXT';
        if (file.size > maxBytes) return '单个文件不能超过 8MB';
        if (file.size === 0) return '不能上传空文件';
        return '';
    }
    function formatSize(bytes) { return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))}KB` : `${(bytes / 1024 / 1024).toFixed(1)}MB`; }
    async function setFile(kind, file) {
        const error = validateFile(kind, file);
        if (error) { showToast(error); return; }
        const isPlan = kind === 'plan';
        state[isPlan ? 'planFile' : 'scoreFile'] = file;
        const card = $(`[data-upload="${kind}"]`);
        const label = isPlan ? elements.planState : elements.scoreState;
        label.textContent = `✓ ${file.name} · ${formatSize(file.size)}`;
        card.classList.add('has-file');
        if (isPlan) {
            elements.planLibrary.value = '';
            state.planId = ''; state.planSource = 'upload'; state.plan = null;
            elements.choosePlanFile.textContent = '重新选择培养方案';
            elements.cache.className = 'cache-state'; elements.cache.textContent = '正在生成文件指纹…';
            try {
                const hash = await sha256(file);
                if (state.planFile !== file) return;
                state.planHash = hash;
                await checkPlanCache();
            } catch (_) {
                state.planHash = ''; elements.cache.textContent = '无法校验方案版本，请重新选择文件';
            }
        }
        updateParseButton();
    }
    function updateParseButton() {
        const identity = elements.school.value.trim() && elements.major.value.trim() && /^20\d{2}$/.test(elements.cohort.value.trim());
        const hasPlan = state.planSource === 'library'
            ? Boolean(state.planId)
            : Boolean(state.planSource === 'upload' && state.planFile && state.planHash);
        elements.parse.disabled = !(hasPlan && state.scoreFile && identity && elements.consent.checked);
    }
    async function sha256(file) {
        if (!crypto?.subtle) throw new Error('当前浏览器不支持安全文件指纹');
        const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
        return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('');
    }
    async function checkPlanCache() {
        if (state.planSource !== 'upload') { updateParseButton(); return; }
        state.planId = ''; state.plan = null;
        if (!state.planHash || !elements.school.value.trim() || !elements.major.value.trim() || !/^20\d{2}$/.test(elements.cohort.value.trim())) {
            elements.cache.className = 'cache-state'; elements.cache.textContent = '';
            updateParseButton(); return;
        }
        elements.cache.className = 'cache-state'; elements.cache.textContent = '正在检查服务器方案库…';
        const query = new URLSearchParams({school: elements.school.value.trim(), major: elements.major.value.trim(), cohort: elements.cohort.value.trim(), hash: state.planHash});
        try {
            const response = await fetch(`api/credit-audit/parse?${query}`, {credentials: 'same-origin'});
            const payload = await response.json().catch(() => null);
            if (!response.ok || !payload || payload.code !== 200) throw new Error(payload?.msg || '方案库查询失败');
            const result = payload.data;
            if (result.found && result.skipUpload && result.plan) {
                elements.cache.className = 'cache-state is-hit';
                elements.cache.textContent = '服务器已有相同版本；仍将按你上传的文件重新解析';
            } else {
                elements.cache.className = 'cache-state is-miss'; elements.cache.textContent = result.message || '需要上传当前方案';
            }
        } catch (error) {
            elements.cache.className = 'cache-state is-miss'; elements.cache.textContent = error.message || '暂时无法查询方案库';
        }
        updateParseButton();
    }

    $$('[data-upload]').forEach(card => {
        const kind = card.dataset.upload;
        const input = kind === 'plan' ? elements.planInput : elements.scoreInput;
        card.addEventListener('click', event => { if (!event.target.closest('select,button')) input.click(); });
        card.addEventListener('keydown', event => {
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); input.click(); }
        });
        input.addEventListener('change', () => setFile(kind, input.files[0]));
        ['dragenter', 'dragover'].forEach(type => card.addEventListener(type, event => {
            event.preventDefault(); card.classList.add('is-dragging');
        }));
        ['dragleave', 'drop'].forEach(type => card.addEventListener(type, event => {
            event.preventDefault(); card.classList.remove('is-dragging');
        }));
        card.addEventListener('drop', event => setFile(kind, event.dataTransfer.files[0]));
    });

    elements.consent.addEventListener('change', updateParseButton);
    [elements.school, elements.major, elements.cohort].forEach(field => field.addEventListener('change', () => {
        if (state.planSource === 'upload') checkPlanCache();
        if (state.planSource === 'library' && state.plan) {
            const changed = elements.school.value.trim() !== state.plan.school
                || elements.major.value.trim() !== state.plan.major
                || elements.cohort.value.trim() !== state.plan.cohort;
            if (changed) {
                state.planId = ''; state.planHash = ''; state.planSource = ''; state.plan = null;
                elements.planLibrary.value = '';
                elements.planState.textContent = '信息已变化，请重新选择或上传培养方案';
                elements.cache.className = 'cache-state is-miss'; elements.cache.textContent = '';
                $('[data-upload="plan"]').classList.remove('has-file');
            }
        }
        updateParseButton();
    }));
    async function loadPlanLibrary() {
        try {
            const response = await fetch('api/credit-audit/plans', {credentials: 'same-origin'});
            const payload = await response.json().catch(() => null);
            if (!response.ok || payload?.code !== 200) return;
            state.availablePlans = payload.data || [];
            state.availablePlans.forEach(plan => {
                const option = document.createElement('option');
                option.value = plan.id;
                option.textContent = `${plan.school} · ${plan.cohort}级 · ${plan.major}`;
                elements.planLibrary.append(option);
            });
        } catch (_) {
            state.availablePlans = [];
        }
    }
    elements.choosePlanFile.addEventListener('click', event => {
        event.stopPropagation();
        elements.planInput.value = '';
        elements.planInput.click();
    });
    elements.planLibrary.addEventListener('click', event => event.stopPropagation());
    elements.planLibrary.addEventListener('change', () => {
        const plan = state.availablePlans.find(item => item.id === elements.planLibrary.value);
        if (!plan) {
            state.planId = ''; state.plan = null;
            state.planSource = state.planFile ? 'upload' : '';
            elements.planState.textContent = state.planFile ? `✓ ${state.planFile.name} · ${formatSize(state.planFile.size)}` : '请选择已有方案，或上传自己的方案';
            updateParseButton(); return;
        }
        state.planFile = null; state.planId = plan.id; state.planHash = plan.sha256; state.planSource = 'library'; state.plan = plan;
        elements.planInput.value = '';
        elements.choosePlanFile.textContent = '上传自己的培养方案';
        elements.school.value = plan.school; elements.major.value = plan.major; elements.cohort.value = plan.cohort;
        elements.planState.textContent = `✓ 已选择：${plan.sourceName}`;
        elements.cache.className = 'cache-state is-hit'; elements.cache.textContent = '无需重复上传培养方案';
        $('[data-upload="plan"]').classList.add('has-file');
        updateParseButton();
    });

    elements.parse.addEventListener('click', async () => {
        const form = new FormData();
        form.append('school', elements.school.value.trim()); form.append('major', elements.major.value.trim()); form.append('cohort', elements.cohort.value.trim());
        form.append('planHash', state.planHash); form.append('scoreFile', state.scoreFile);
        if (state.planSource === 'library') {
            form.append('planMode', 'library'); form.append('planId', state.planId);
        } else {
            form.append('planMode', 'upload'); form.append('planFile', state.planFile);
        }
        elements.parse.disabled = true; elements.parse.querySelector('span').textContent = '正在解析并 AI 核对…';
        try {
            const response = await fetch('api/credit-audit/parse', {
                method: 'POST', body: form, credentials: 'same-origin',
                headers: {'X-Credit-Audit-Request': '1'}
            });
            const payload = await response.json().catch(() => null);
            if (!response.ok || !payload || payload.code !== 200) throw new Error(payload?.msg || '文件解析失败');
            loadExtraction(payload.data);
        } catch (error) {
            showToast(error.message || '解析失败，请稍后重试');
        } finally {
            elements.parse.querySelector('span').textContent = '解析并 AI 二次核对'; updateParseButton();
        }
    });

    function normalize(value) { return String(value || '').toLowerCase().replace(/[\s_—–\-：:（）()【】\[\]]/g, ''); }
    function categoryOptions() {
        if (window.CreditAuditCalculator?.categoryNames) return window.CreditAuditCalculator.categoryNames(state.modules);
        return ['待归类', ...state.modules.map(module => module.name).filter(Boolean)];
    }
    function matchCategory(category, courseName = '') {
        const value = normalize(category);
        if (!value || value === '待归类') return '待归类';
        const aliases = [
            [/公共必修|通识必修/, '通识必修'], [/公共选修|通识选修/, '通识选修'],
            [/学科基础.*必修|专业基础.*必修/, '学科基础必修'], [/专业必修/, '专业必修'], [/专业选修/, '专业选修']
        ];
        for (const [pattern, target] of aliases) if (pattern.test(value) && state.modules.some(item => item.name === target)) return target;
        const exact = state.modules.find(item => normalize(item.name) === value);
        if (exact) return exact.name;
        const matches = state.modules.filter(item => {
            const moduleName = normalize(item.name);
            return moduleName.includes(value) || value.includes(moduleName);
        });
        return matches.length === 1 ? matches[0].name : '待归类';
    }
    function loadExtraction(data) {
        state.lastResult = null;
        state.resultActivated = false;
        state.editRevision = 0;
        state.resultRevision = -1;
        state.calculationCount = 0;
        elements.calculate.textContent = '生成学分结果';
        elements.resultFreshness.classList.add('is-hidden');
        elements.aiReviewFreshness.classList.add('is-hidden');
        state.plan = data.plan || state.plan;
        if (state.plan?.id) state.planId = state.plan.id;
        state.modules = (data.modules || []).map(item => ({name: item.name, requiredCredits: Number(item.requiredCredits) || 0,
            source: item.source || '', countsTowardTotal: item.status !== '不计入总学分'}));
        state.courses = (data.courses || []).map(item => ({
            name: item.name, category: item.category || '待归类', rawCategory: item.category || '', credits: Number(item.credits) || 0,
            score: Number(item.score) || 0, passed: Boolean(item.passed), source: item.source || ''
        }));
        state.courses.forEach(item => { item.category = matchCategory(item.category, item.name); });
        state.aiVerification = data.aiVerification || null;
        const warnings = [...(data.warnings || [])];
        if (data.planReused) warnings.unshift('已复用服务器中相同培养方案的规则，培养方案原文件未再次上传。');
        if (state.plan?.verified) warnings.unshift('当前培养方案规则已按所提供文件人工核对；个人成绩仍需逐项确认。');
        renderWarnings(warnings); renderAiReview(); renderReview();
        elements.review.classList.remove('is-hidden'); elements.result.classList.add('is-hidden');
        elements.review.scrollIntoView({behavior: 'smooth', block: 'start'});
    }
    function renderWarnings(warnings) {
        elements.warnings.replaceChildren();
        warnings.forEach(message => {
            const paragraph = document.createElement('p'); paragraph.textContent = `请注意：${message}`; elements.warnings.append(paragraph);
        });
    }
    function renderAiReview() {
        const review = state.aiVerification;
        if (!review?.completed) throw new Error('AI 二次核对未完成');
        $('#aiReviewSummary').textContent = review.summary || 'AI 二次核对已完成。';
        const list = $('#aiReviewIssues');
        list.replaceChildren();
        const issues = Array.isArray(review.issues) ? review.issues : [];
        if (!issues.length) {
            const item = document.createElement('li');
            item.className = 'is-clear';
            item.textContent = '未发现明显异常，仍请逐项确认下方识别结果。';
            list.append(item);
            return;
        }
        issues.forEach(issue => {
            const item = document.createElement('li');
            const title = document.createElement('strong');
            title.textContent = `${issue.level || '需核对'}：${issue.title || '数据异常'}`;
            const detail = document.createElement('span');
            detail.textContent = issue.detail || '请人工核对该项。';
            item.append(title, detail);
            list.append(item);
        });
    }
    function input(type, value, label, options = {}) {
        const field = document.createElement('input'); field.type = type;
        if (type !== 'checkbox') field.value = value ?? '';
        field.setAttribute('aria-label', label);
        Object.entries(options).forEach(([key, option]) => field.setAttribute(key, option));
        return field;
    }
    function cell(child, className = '', label = '') {
        const td = document.createElement('td'); if (className) td.className = className;
        if (label) td.dataset.label = label;
        if (typeof child === 'string') td.textContent = child; else td.append(child); return td;
    }
    function deleteButton(callback, label) {
        const button = document.createElement('button'); button.type = 'button'; button.className = 'delete-row'; button.textContent = '×';
        button.setAttribute('aria-label', label); button.addEventListener('click', callback); return button;
    }
    function renderReview() {
        elements.moduleRows.replaceChildren();
        state.modules.forEach((item, index) => {
            const row = document.createElement('tr');
            const name = input('text', item.name, '板块名称');
            const credits = input('number', item.requiredCredits, '要求学分', {min: '0', max: '300', step: '0.5'});
            name.addEventListener('input', event => { item.name = event.target.value; markResultStale(); });
            name.addEventListener('change', () => { renderCourseCategoryOptions(); markResultStale(); });
            credits.addEventListener('input', event => { item.requiredCredits = Number(event.target.value) || 0; markResultStale(); });
            row.append(cell(name, '', '板块名称'), cell(credits, '', '要求学分'), cell(item.source || '手动添加', 'source-cell', '来源片段'),
                cell(deleteButton(() => { state.modules.splice(index, 1); renderReview(); markResultStale(); }, `删除${item.name || '板块'}`), 'row-action', '操作'));
            elements.moduleRows.append(row);
        });
        renderCourseRows();
    }
    function categorySelect(item) {
        const select = document.createElement('select'); select.setAttribute('aria-label', '归属板块');
        categoryOptions().forEach(name => {
            const option = document.createElement('option'); option.value = name; option.textContent = name; option.selected = name === item.category; select.append(option);
        });
        if (![...select.options].some(option => option.selected)) select.value = '待归类';
        select.addEventListener('change', event => {
            item.category = event.target.value;
            item.rawCategory = event.target.value;
            markResultStale();
        }); return select;
    }
    function renderCourseRows() {
        elements.courseRows.replaceChildren();
        state.courses.forEach((item, index) => {
            const row = document.createElement('tr');
            const name = input('text', item.name, '课程名称');
            const credits = input('number', item.credits, '课程学分', {min: '0', max: '30', step: '0.5'});
            const score = input('number', item.score, '课程成绩', {min: '0', max: '100', step: '0.1'});
            const passed = input('checkbox', '', '课程已通过'); passed.checked = item.passed;
            name.addEventListener('input', event => { item.name = event.target.value; markResultStale(); });
            credits.addEventListener('input', event => { item.credits = Number(event.target.value) || 0; markResultStale(); });
            score.addEventListener('input', event => {
                item.score = event.target.value === '' ? null : Number(event.target.value);
                if (item.score !== null && Number.isFinite(item.score)) {
                    item.passed = item.score >= 60;
                    passed.checked = item.passed;
                }
                markResultStale();
            });
            const passLabel = document.createElement('label'); passLabel.className = 'pass-check';
            passed.addEventListener('change', event => { item.passed = event.target.checked; markResultStale(); });
            passLabel.append(passed, document.createTextNode('已通过'));
            row.append(cell(name, '', '课程名称'), cell(categorySelect(item), '', '归属板块'), cell(credits, '', '学分'), cell(score, '', '成绩'), cell(passLabel, '', '是否通过'),
                cell(deleteButton(() => { state.courses.splice(index, 1); renderCourseRows(); markResultStale(); }, `删除${item.name || '课程'}`), 'row-action', '操作'));
            elements.courseRows.append(row);
        });
        elements.courseCount.textContent = `${state.courses.length} 门`;
    }
    function renderCourseCategoryOptions() {
        const options = new Set(categoryOptions());
        state.courses.forEach(course => { if (!options.has(course.category)) course.category = '待归类'; });
        renderCourseRows();
    }

    $('#addModule').addEventListener('click', () => {
        state.modules.push({name: '', requiredCredits: 0, source: '', countsTowardTotal: true}); renderReview();
        elements.moduleRows.lastElementChild?.querySelector('input')?.focus();
        markResultStale();
    });
    $('#addCourse').addEventListener('click', () => {
        state.courses.push({name: '', category: '待归类', credits: 0, score: null, passed: true, source: ''}); renderCourseRows();
        elements.courseRows.lastElementChild?.querySelector('input')?.focus();
        markResultStale();
    });

    function syncReviewInputs() {
        [...elements.moduleRows.rows].forEach((row, index) => {
            const item = state.modules[index];
            if (!item) return;
            item.name = row.querySelector('[aria-label="板块名称"]')?.value ?? item.name;
            item.requiredCredits = Number(row.querySelector('[aria-label="要求学分"]')?.value) || 0;
        });
        [...elements.courseRows.rows].forEach((row, index) => {
            const item = state.courses[index];
            if (!item) return;
            item.name = row.querySelector('[aria-label="课程名称"]')?.value ?? item.name;
            const category = row.querySelector('[aria-label="归属板块"]')?.value;
            if (category) item.category = category;
            item.credits = Number(row.querySelector('[aria-label="课程学分"]')?.value) || 0;
            const scoreValue = row.querySelector('[aria-label="课程成绩"]')?.value ?? '';
            item.score = scoreValue === '' ? null : Number(scoreValue);
            item.passed = Boolean(row.querySelector('[aria-label="课程已通过"]')?.checked);
        });
    }

    function collectResult() {
        syncReviewInputs();
        if (!window.CreditAuditCalculator) throw new Error('学分计算组件加载失败，请刷新页面');
        return window.CreditAuditCalculator.calculate(
                state.modules, state.courses, state.plan?.completionRequirements || [],
                state.plan?.subRequirements || []);
    }
    function updateResult(options = {}) {
        try {
            state.lastResult = collectResult();
            state.resultActivated = true;
            state.resultRevision = state.editRevision;
            state.calculationCount += 1;
            renderResult(state.lastResult);
            elements.result.classList.remove('is-hidden');
            elements.result.classList.remove('is-stale');
            elements.resultFreshness.textContent = `第 ${state.calculationCount} 次计算完成，所有汇总均已按当前数据同步。`;
            elements.resultFreshness.className = 'result-freshness is-current';
            elements.calculate.textContent = '更新学分结果';
            if (options.announce) showToast(options.message || '学分结果已按最新修改更新');
            if (options.scroll) elements.result.scrollIntoView({behavior: 'smooth', block: 'start'});
            return true;
        } catch (error) {
            state.lastResult = null;
            elements.result.classList.add('is-hidden');
            if (options.showError) showToast(error.message);
            return false;
        }
    }
    function markResultStale() {
        state.editRevision += 1;
        elements.aiReviewFreshness.textContent = '你已修改识别数据；DeepSeek 意见仍基于首次上传文件，修改后的数值请以更新后的学分结果为准。';
        elements.aiReviewFreshness.classList.remove('is-hidden');
        if (!state.resultActivated) return;
        elements.result.classList.add('is-stale');
        elements.resultFreshness.textContent = '课程或板块数据已修改，请点击“更新学分结果”同步全部汇总。';
        elements.resultFreshness.className = 'result-freshness is-pending';
        elements.calculate.textContent = '更新学分结果';
    }
    elements.calculate.addEventListener('click', () => {
        const isUpdate = state.resultActivated;
        updateResult({announce: isUpdate, showError: true, scroll: true});
    });
    function formatNumber(value) { return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, ''); }
    function renderResult(result) {
        const summary = window.CreditAuditCalculator.summarize(result);
        $('#progressPercent').textContent = `${summary.percent}%`; $('#progressRing').style.setProperty('--progress', `${summary.percent * 3.6}deg`);
        $('#requiredTotal').textContent = formatNumber(summary.required); $('#earnedTotal').textContent = formatNumber(summary.earned); $('#missingTotal').textContent = formatNumber(summary.missing);
        $('#resultHeadline').textContent = summary.missing > 0 ? `总学分还差 ${formatNumber(summary.missing)} 学分` : summary.headline;
        $('#resultBadge').textContent = summary.badge;
        const container = $('#moduleResults'); container.replaceChildren();
        [...result.details].sort((a, b) => b.missing - a.missing).forEach(item => {
            const card = document.createElement('article'); card.className = `module-result${item.missing === 0 ? ' is-done' : ''}`;
            const head = document.createElement('div'); head.className = 'module-result-head';
            const title = document.createElement('b'); title.textContent = item.name;
            const status = document.createElement('span'), strong = document.createElement('strong');
            strong.textContent = item.countsTowardTotal === false
                    ? (item.missing === 0 ? '单独达标' : `单独差 ${formatNumber(item.missing)}`)
                    : (item.missing === 0 ? '已达标' : `差 ${formatNumber(item.missing)}`);
            status.append(document.createTextNode(`${formatNumber(item.earned)} / ${formatNumber(item.requiredCredits)} 学分 · `), strong); head.append(title, status);
            const bar = document.createElement('div'); bar.className = 'bar'; const fill = document.createElement('i');
            fill.style.width = `${Math.min(100, item.requiredCredits ? item.earned / item.requiredCredits * 100 : 0)}%`; bar.append(fill); card.append(head, bar); container.append(card);
        });
        const completionResults = $('#completionResults'); completionResults.replaceChildren();
        result.completionDetails.forEach(item => {
            const row = document.createElement('div'); row.className = `completion-result${item.completed ? ' is-done' : ''}`;
            const name = document.createElement('strong'); name.textContent = item.courseName;
            const meta = document.createElement('span');
            const credit = Number(item.nominalCredits) > 0 ? `${formatNumber(Number(item.nominalCredits))} 学分 · ` : '';
            meta.textContent = `${credit}${item.countsTowardTotal === false ? '不计入毕业总学分' : '计入毕业总学分'} · ${item.completed ? '已识别完成' : '未识别完成'}`;
            row.append(name, meta); completionResults.append(row);
        });
        if (!result.completionDetails.length) {
            const empty = document.createElement('p'); empty.className = 'completion-empty'; empty.textContent = '当前方案未识别到另行完成要求，请以培养方案原文为准。'; completionResults.append(empty);
        }
        const attention = $('#attentionList'); attention.replaceChildren();
        const missing = result.details.filter(item => item.missing > 0).sort((a, b) => b.missing - a.missing);
        missing.slice(0, 3).forEach(item => addAttention(attention, item.countsTowardTotal === false
                ? `${item.name}还差 ${formatNumber(item.missing)} 学分，单独核对且不重复计入总学分。`
                : `${item.name}还差 ${formatNumber(item.missing)} 学分。`));
        if (result.unassigned.length) addAttention(attention, `${result.unassigned.length} 门已通过课程尚未归类，共 ${formatNumber(result.unassigned.reduce((sum, item) => sum + item.credits, 0))} 学分。`);
        if (result.duplicateNames.length) addAttention(attention, `发现 ${result.duplicateNames.length} 条同名重复记录，本次只计一次。`);
        result.subDetails.filter(item => !item.confirmed).forEach(item => addAttention(attention,
                `${item.parentModule}中的“${item.name}”至少 ${formatNumber(item.requiredCredits)} 学分；成绩文件未提供可识别的专项分类，请人工核对。`));
        result.subDetails.filter(item => item.confirmed && item.missing > 0).forEach(item => addAttention(attention,
                `${item.parentModule}中的“${item.name}”还差 ${formatNumber(item.missing)} 学分。`));
        const missingCompletion = result.completionDetails.filter(item => !item.completed);
        missingCompletion.forEach(item => addAttention(attention, item.countsTowardTotal === false
                ? `未识别到“${item.courseName}”完成记录；该课程必须完成，但不计入 ${formatNumber(result.required)} 学分。`
                : `未识别到“${item.courseName}”完成记录，请按培养方案核对。`));
        if (!attention.children.length) addAttention(attention, '各板块学分已达标，仍需核对必修课程、论文、实习及学校其他毕业条件。');
    }
    function addAttention(list, message) { const item = document.createElement('li'); item.textContent = message; list.append(item); }

    $('#downloadButton').addEventListener('click', () => {
        if (!state.lastResult) return;
        const rows = [['板块', '要求学分', '已修学分', '待补学分'],
            ...state.lastResult.details.map(item => [item.name, formatNumber(item.requiredCredits), formatNumber(item.earned), formatNumber(item.missing)]),
            [], ['板块内专项要求', '最低学分', '已识别学分', '状态'],
            ...state.lastResult.subDetails.map(item => [item.name, formatNumber(item.requiredCredits), item.confirmed ? formatNumber(item.earned) : '未识别', item.confirmed ? (item.missing > 0 ? `还差 ${formatNumber(item.missing)}` : '已达标') : '需人工核对']),
            [], ['另行完成要求', '名义学分', '是否完成', '计入毕业总学分'],
            ...state.lastResult.completionDetails.map(item => [item.courseName, formatNumber(Number(item.nominalCredits) || 0), item.completed ? '是' : '否', item.countsTowardTotal === false ? '否' : '是'])];
        const csvCell = value => {
            let text = String(value ?? '').replace(/[\r\n\t]+/g, ' ');
            if (/^[=+\-@＝＋－＠]/.test(text)) text = `\t${text}`;
            return `"${text.replace(/"/g, '""')}"`;
        };
        const csv = '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n');
        const url = URL.createObjectURL(new Blob([csv], {type: 'text/csv;charset=utf-8'}));
        const link = document.createElement('a'); link.href = url; link.download = '毕业学分自查清单.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    $('#restartButton').addEventListener('click', () => {
        state.modules = []; state.courses = []; state.aiVerification = null; state.lastResult = null; state.resultActivated = false; state.editRevision = 0; state.resultRevision = -1; state.calculationCount = 0; elements.calculate.textContent = '生成学分结果'; elements.resultFreshness.classList.add('is-hidden'); elements.aiReviewFreshness.classList.add('is-hidden'); elements.review.classList.add('is-hidden'); elements.result.classList.add('is-hidden'); $('#audit').scrollIntoView({behavior: 'smooth'});
    });
    $$('[data-open-privacy]').forEach(button => button.addEventListener('click', event => { event.preventDefault(); elements.privacy.showModal(); }));
    $$('[data-close-privacy]').forEach(button => button.addEventListener('click', () => elements.privacy.close()));
    elements.privacy.addEventListener('click', event => { if (event.target === elements.privacy) elements.privacy.close(); });
    $('#feedbackButton').addEventListener('click', () => elements.feedback.showModal());
    $$('[data-close-feedback]').forEach(button => button.addEventListener('click', () => elements.feedback.close()));
    elements.feedback.addEventListener('click', event => { if (event.target === elements.feedback) elements.feedback.close(); });
    elements.feedbackForm.addEventListener('submit', async event => {
        event.preventDefault();
        const submit = $('#feedbackSubmit'); submit.disabled = true; submit.textContent = '正在提交…';
        try {
            const response = await fetch('api/credit-audit/feedback', {
                method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', 'X-Credit-Audit-Request': '1'},
                body: JSON.stringify({type: $('#feedbackType').value, content: $('#feedbackContent').value.trim(),
                    contact: $('#feedbackContact').value.trim(), page: location.pathname})
            });
            const payload = await response.json().catch(() => null);
            if (!response.ok || payload?.code !== 200) throw new Error(payload?.msg || '反馈提交失败');
            elements.feedbackForm.reset(); elements.feedback.close(); showToast('反馈已提交');
        } catch (error) {
            showToast(error.message || '反馈提交失败');
        } finally {
            submit.disabled = false; submit.textContent = '提交反馈';
        }
    });
    fetch('api/credit-audit/visit', {credentials: 'same-origin'}).catch(() => {});
    loadPlanLibrary();
})();
