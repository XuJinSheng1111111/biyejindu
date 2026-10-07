((root, factory) => {
    const calculator = factory();
    if (typeof module === 'object' && module.exports) module.exports = calculator;
    else root.CreditAuditCalculator = calculator;
})(typeof globalThis !== 'undefined' ? globalThis : this, () => {
    'use strict';

    function normalize(value) {
        return String(value || '').toLowerCase().replace(/[\s_—–\-：:（）()【】\[\]]/g, '');
    }

    function number(value) {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : 0;
    }

    function round(value) {
        return Math.round((value + Number.EPSILON) * 100) / 100;
    }

    function matchesCourseName(courseName, ruleName) {
        const courseKey = normalize(courseName);
        const ruleKey = normalize(ruleName);
        return Boolean(courseKey && ruleKey)
                && (courseKey.includes(ruleKey) || ruleKey.includes(courseKey));
    }

    function categoryNames(modulesInput) {
        const names = new Set(['待归类']);
        (Array.isArray(modulesInput) ? modulesInput : []).forEach(item => {
            const name = String(item?.name || '').trim();
            if (name) names.add(name);
        });
        return [...names];
    }

    function summarize(result) {
        const required = number(result?.required);
        const earned = number(result?.earned);
        const missing = number(result?.missing);
        const percent = required ? Math.min(100, Math.round(earned / required * 100)) : 0;
        return {
            required,
            earned,
            missing,
            percent,
            headline: missing > 0 ? `总学分还差 ${missing} 学分` : '计入总学分的板块已达标',
            badge: missing > 0 ? '继续加油' : '板块已达标'
        };
    }

    function calculate(modulesInput, coursesInput, completionInput = [], subRequirementInput = []) {
        const modules = (Array.isArray(modulesInput) ? modulesInput : [])
                .map(item => ({...item, name: String(item?.name || '').trim(), requiredCredits: number(item?.requiredCredits)}))
                .filter(item => item.name && item.requiredCredits > 0);
        if (!modules.length) throw new Error('请至少填写一个有效培养方案板块');

        const duplicateNames = [];
        const unique = new Map();
        const unnamed = [];
        (Array.isArray(coursesInput) ? coursesInput : [])
                .map(item => ({...item, name: String(item?.name || '').trim(), category: String(item?.category || '').trim(), credits: number(item?.credits)}))
                .filter(course => course.credits > 0 && course.passed === true)
                .forEach(course => {
                    const key = normalize(course.name);
                    if (!key) {
                        unnamed.push(course);
                        return;
                    }
                    const previous = unique.get(key);
                    if (!previous || course.credits > previous.credits) {
                        if (previous) duplicateNames.push(course.name);
                        unique.set(key, course);
                    } else {
                        duplicateNames.push(course.name);
                    }
                });

        const validCourses = [...unique.values(), ...unnamed];
        const completionRules = Array.isArray(completionInput) ? completionInput : [];
        const excludedRules = completionRules.filter(rule => rule?.countsTowardTotal === false);
        const isExcludedFromGraduationTotal = course => excludedRules.some(rule =>
            matchesCourseName(course.name, rule.courseName));
        const graduationCourses = validCourses.filter(course => !isExcludedFromGraduationTotal(course));
        const details = modules.map(module => {
            const moduleKey = normalize(module.name);
            const earned = round(graduationCourses
                    .filter(course => normalize(course.category) === moduleKey)
                    .reduce((sum, course) => sum + course.credits, 0));
            return {
                ...module,
                earned,
                counted: round(Math.min(earned, module.requiredCredits)),
                missing: round(Math.max(0, module.requiredCredits - earned))
            };
        });
        const moduleKeys = new Set(modules.map(module => normalize(module.name)));
        const unassigned = graduationCourses.filter(course => !moduleKeys.has(normalize(course.category)));
        const totalDetails = details.filter(item => item.countsTowardTotal !== false);
        const required = round(totalDetails.reduce((sum, item) => sum + item.requiredCredits, 0));
        const earned = round(totalDetails.reduce((sum, item) => sum + item.counted, 0));
        const missing = round(Math.max(0, required - earned));
        const completionDetails = completionRules.map(rule => {
                    const ruleName = normalize(rule?.courseName);
                    return {...rule, completed: validCourses.some(course => {
                        return matchesCourseName(course.name, ruleName);
                    })};
                });
        const subDetails = (Array.isArray(subRequirementInput) ? subRequirementInput : []).map(rule => {
            const ruleName = normalize(rule?.name);
            const matched = graduationCourses.filter(course => {
                const sourceCategory = normalize(course.rawCategory || course.category);
                return Boolean(ruleName && sourceCategory)
                        && (sourceCategory.includes(ruleName) || ruleName.includes(sourceCategory));
            });
            const earned = round(matched.reduce((sum, course) => sum + course.credits, 0));
            const requiredCredits = number(rule?.requiredCredits);
            return {...rule, requiredCredits, earned, confirmed: matched.length > 0,
                missing: round(Math.max(0, requiredCredits - earned))};
        });

        if (round(earned + missing) !== required) throw new Error('学分汇总不一致，请重新核对课程数据');
        return {details, required, earned, missing, unassigned, duplicateNames, subDetails, completionDetails};
    }

    return {calculate, normalize, categoryNames, summarize};
});
