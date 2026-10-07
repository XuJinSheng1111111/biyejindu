const test = require('node:test');
const assert = require('node:assert/strict');
const calculator = require('../../main/webapp/static/js/credit-audit-calculator.js');

function modules() {
    return [
        {name: '通识必修', requiredCredits: 43.5, countsTowardTotal: true},
        {name: '通识选修', requiredCredits: 15, countsTowardTotal: true},
        {name: '学科基础必修', requiredCredits: 42, countsTowardTotal: true},
        {name: '专业必修', requiredCredits: 41.5, countsTowardTotal: true},
        {name: '专业选修', requiredCredits: 23, countsTowardTotal: true}
    ];
}

function completionRules() {
    return [
        {courseName: '国家安全教育', nominalCredits: 1, countsTowardTotal: false},
        {courseName: '大学生健康与安全教育', nominalCredits: 1, countsTowardTotal: false},
        {courseName: '劳动', nominalCredits: 2, countsTowardTotal: false},
        {courseName: '职业生涯规划', nominalCredits: 0.5, countsTowardTotal: false},
        {courseName: '毕业生就业指导', nominalCredits: 0.5, countsTowardTotal: false}
    ];
}

function courses() {
    return [
        {name: '通识课程', category: '通识必修', credits: 44, passed: true},
        {name: '通识选修课程', category: '通识选修', credits: 14, passed: true},
        {name: '学科基础课程', category: '学科基础必修', credits: 42, passed: true},
        {name: '专业必修课程', category: '专业必修', credits: 10.5, passed: true},
        {name: '专业选修课程', category: '专业选修', credits: 5, passed: true},
        {name: '劳动', category: '通识必修', credits: 1, passed: true}
    ];
}

test('首次计算与各板块汇总保持一致', () => {
    const result = calculator.calculate(modules(), courses(), completionRules());
    assert.equal(result.required, 165);
    assert.equal(result.earned, 115);
    assert.equal(result.missing, 50);
    assert.equal(result.earned + result.missing, result.required);
    assert.equal(result.details.length, 5);
    assert.equal(result.completionDetails.find(item => item.courseName === '劳动').completed, true);
});

test('课程修改后重新计算全部动态数据', () => {
    const changed = courses();
    changed.find(item => item.category === '专业必修').credits = 11.5;
    const result = calculator.calculate(modules(), changed, completionRules());
    assert.equal(result.earned, 116);
    assert.equal(result.missing, 49);
    assert.equal(result.details.find(item => item.name === '专业必修').earned, 11.5);
});

test('连续两次修改后每次都生成全新的汇总', () => {
    const changed = courses();
    const first = calculator.calculate(modules(), changed, completionRules());
    assert.equal(calculator.summarize(first).earned, 115);

    changed.find(item => item.category === '专业选修').credits = 8;
    const second = calculator.calculate(modules(), changed, completionRules());
    const secondSummary = calculator.summarize(second);
    assert.equal(secondSummary.earned, 118);
    assert.equal(secondSummary.missing, 47);
    assert.equal(second.details.find(item => item.name === '专业选修').earned, 8);

    changed.find(item => item.category === '专业必修').credits = 20.5;
    changed.find(item => item.category === '通识选修').credits = 15;
    const third = calculator.calculate(modules(), changed, completionRules());
    const thirdSummary = calculator.summarize(third);
    assert.equal(thirdSummary.earned, 129);
    assert.equal(thirdSummary.missing, 36);
    assert.equal(thirdSummary.percent, 78);
    assert.equal(third.details.find(item => item.name === '专业必修').earned, 20.5);
    assert.equal(third.details.find(item => item.name === '通识选修').missing, 0);
    assert.notDeepEqual(thirdSummary, secondSummary);
});

test('课程改换板块后旧板块和新板块同时更新', () => {
    const changed = courses();
    const target = changed.find(item => item.category === '专业选修');
    const before = calculator.calculate(modules(), changed, completionRules());
    target.category = '专业必修';
    target.rawCategory = '专业必修';
    const after = calculator.calculate(modules(), changed, completionRules());
    assert.equal(before.details.find(item => item.name === '专业选修').earned, 5);
    assert.equal(after.details.find(item => item.name === '专业选修').earned, 0);
    assert.equal(after.details.find(item => item.name === '专业必修').earned, 15.5);
    assert.equal(after.earned, before.earned);
});

test('课程分类下拉框只提供当前培养方案的有效板块', () => {
    assert.deepEqual(calculator.categoryNames(modules()), [
        '待归类', '通识必修', '通识选修', '学科基础必修', '专业必修', '专业选修'
    ]);
    assert.equal(calculator.categoryNames(modules()).includes('劳动'), false);
});

test('超出板块要求的学分不跨板块抵扣', () => {
    const changed = courses();
    changed.find(item => item.category === '通识必修').credits = 50;
    const result = calculator.calculate(modules(), changed, completionRules());
    assert.equal(result.details.find(item => item.name === '通识必修').earned, 50);
    assert.equal(result.details.find(item => item.name === '通识必修').counted, 43.5);
    assert.equal(result.earned, 115);
});

test('全部素质课程单独核对且不混入165学分', () => {
    const changed = courses();
    changed.push(
        {name: '国家安全教育', category: '通识必修', credits: 1, passed: true},
        {name: '大学生健康与安全教育', category: '通识必修', credits: 1, passed: true},
        {name: '职业生涯规划', category: '通识必修', credits: 0.5, passed: true},
        {name: '毕业生就业指导', category: '通识必修', credits: 0.5, passed: true}
    );
    const result = calculator.calculate(modules(), changed, completionRules());
    assert.equal(result.earned, 115);
    assert.equal(result.completionDetails.filter(item => item.completed).length, 5);
    assert.equal(result.unassigned.length, 0);
});

test('通识选修专项只在成绩文件提供明确分类时判定', () => {
    const requirements = [{parentModule: '通识选修', name: '思维与方法', requiredCredits: 2}];
    const unknown = calculator.calculate(modules(), courses(), completionRules(), requirements);
    assert.equal(unknown.subDetails[0].confirmed, false);

    const classified = courses();
    classified.find(item => item.category === '通识选修').rawCategory = '思维与方法';
    const known = calculator.calculate(modules(), classified, completionRules(), requirements);
    assert.equal(known.subDetails[0].confirmed, true);
    assert.equal(known.subDetails[0].earned, 14);
    assert.equal(known.subDetails[0].missing, 0);
});
