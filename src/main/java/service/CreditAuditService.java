package service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/**
 * 毕业学分自查文档解析服务。
 * 只做文本提取和保守识别；最终归类由学生在页面确认后计算。
 */
public final class CreditAuditService {

    public static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 1_000_000;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_CELLS_PER_ROW = 60;
    private static final int MAX_ARCHIVE_ENTRIES = 1_000;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final Pattern CREDIT_PATTERN = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:\\.\\d{1,2})?)(?:\\s*学分)?(?![\\d.])");
    private static final Pattern SCORE_PATTERN = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:\\.\\d{1,2})?)(?![\\d.])");
    private static final Set<String> PLAN_HINTS = Set.of(
            "通识", "公共", "基础", "专业", "学科", "必修", "选修", "实践", "实习", "毕业设计",
            "创新创业", "第二课堂", "素质拓展", "劳动", "军事", "体育", "合计", "总计"
    );
    private static final Set<String> HEADER_NAMES = Set.of(
            "课程名称", "课程名", "科目名称", "科目", "课程", "教学环节"
    );
    private static final Set<String> HEADER_CREDITS = Set.of(
            "学分", "课程学分", "获得学分", "取得学分"
    );
    private static final Set<String> HEADER_CATEGORIES = Set.of(
            "课程类别", "课程性质", "课程模块", "模块", "类别", "性质", "课程归属", "课程组"
    );
    private static final Set<String> HEADER_SCORES = Set.of(
            "成绩", "总评成绩", "最终成绩", "总成绩", "考核成绩", "等级成绩"
    );
    private static final Set<String> HEADER_STATUS = Set.of(
            "是否通过", "通过标记", "及格标志", "修读状态", "成绩标志"
    );

    static {
        ZipSecureFile.setMinInflateRatio(0.02);
        ZipSecureFile.setMaxEntrySize(MAX_ARCHIVE_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(2L * 1024 * 1024);
    }

    public AuditExtraction parse(byte[] planBytes, String planName, byte[] scoreBytes, String scoreName) throws IOException {
        PlanExtraction plan = parsePlan(planBytes, planName);
        CourseExtraction score = parseCourses(scoreBytes, scoreName);
        List<String> warnings = new ArrayList<>();
        warnings.addAll(plan.warnings());
        warnings.addAll(score.warnings());
        return new AuditExtraction(plan.modules(), score.courses(), warnings, planName, scoreName);
    }

    public PlanExtraction parsePlan(byte[] bytes, String fileName) throws IOException {
        DocumentData data = extract(bytes, fileName);
        List<ModuleRequirement> modules = extractRequirements(data);
        List<String> warnings = new ArrayList<>();
        if (modules.isEmpty()) warnings.add("未自动识别到学分板块，请按培养方案手动添加后再核验。");
        if (data.text().isBlank()) warnings.add("培养方案没有可复制文字，可能是扫描件，请换用可复制文字的文件。");
        return new PlanExtraction(modules, warnings, fileName);
    }

    public CourseExtraction parseCourses(byte[] bytes, String fileName) throws IOException {
        DocumentData data = extract(bytes, fileName);
        List<CourseRecord> courses = extractCourses(data);
        List<String> warnings = new ArrayList<>();
        if (courses.isEmpty() && isGradeWrapperHtml(bytes, fileName)) {
            warnings.add("当前文件是成绩查询外层页面，请选择完整导出文件夹，系统会自动读取其中的 cjcx_list.html。");
        } else if (courses.isEmpty()) {
            warnings.add("未自动识别到课程记录，请检查成绩文件表头或手动添加课程。");
        }
        return new CourseExtraction(courses, warnings, fileName);
    }

    private boolean isGradeWrapperHtml(byte[] bytes, String fileName) {
        try {
            String lower = safeName(fileName).toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".html") && !lower.endsWith(".htm")) return false;
            return decodeText(bytes).toLowerCase(Locale.ROOT).contains("cjcx_list.html");
        } catch (IOException ignored) {
            return false;
        }
    }

    DocumentData extract(byte[] bytes, String fileName) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) {
            throw new IOException("文件为空或超过 8MB");
        }
        String lower = safeName(fileName).toLowerCase(Locale.ROOT);
        return switch (extension(lower)) {
            case "pdf" -> extractPdf(bytes);
            case "docx" -> extractDocx(bytes);
            case "doc" -> extractDoc(bytes);
            case "xlsx", "xls" -> extractWorkbook(bytes);
            case "csv" -> extractDelimited(bytes, ',');
            case "txt" -> extractDelimited(bytes, '\t');
            case "html", "htm" -> extractHtml(bytes);
            default -> throw new IOException("不支持该文件格式");
        };
    }

    private DocumentData extractPdf(byte[] bytes) throws IOException {
        requirePrefix(bytes, new int[]{0x25, 0x50, 0x44, 0x46}, "PDF 文件内容与扩展名不匹配");
        Path temp = Files.createTempFile("credit-audit-pdf-", ".pdf");
        try {
            Files.write(temp, bytes);
            try (PDDocument document = Loader.loadPDF(temp.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                if (document.getNumberOfPages() > 300) {
                    throw new IOException("PDF 页数超过 300 页");
                }
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                String text = limitText(stripper.getText(document));
                return new DocumentData(text, linesFromText(text), List.of());
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private DocumentData extractDocx(byte[] bytes) throws IOException {
        requireZip(bytes, "Word 文件内容与扩展名不匹配");
        inspectZip(bytes);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = limitText(extractor.getText());
            List<List<String>> rows = new ArrayList<>();
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                List<String> cells = row.getTableCells().stream().map(cell -> clean(cell.getText())).toList();
                if (cells.stream().anyMatch(value -> !value.isBlank())) {
                    rows.add(cells);
                }
            }));
            return new DocumentData(text, linesFromText(text), rows);
        } catch (RuntimeException err) {
            throw new IOException("Word 文件无法安全解析", err);
        }
    }

    private DocumentData extractDoc(byte[] bytes) throws IOException {
        requireOle(bytes, "Word 文件内容与扩展名不匹配");
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
            String text = limitText(extractor.getText());
            return new DocumentData(text, linesFromText(text), List.of());
        } catch (RuntimeException err) {
            throw new IOException("Word 文件无法安全解析", err);
        }
    }

    private DocumentData extractWorkbook(byte[] bytes) throws IOException {
        if (isZip(bytes)) {
            requireZip(bytes, "Excel 文件内容与扩展名不匹配");
            inspectZip(bytes);
        } else {
            requireOle(bytes, "Excel 文件内容与扩展名不匹配");
        }
        List<List<String>> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            int rowCount = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                for (Row row : sheet) {
                    checkInterrupted();
                    if (++rowCount > MAX_ROWS) {
                        throw new IOException("表格行数超过 10000 行");
                    }
                    List<String> cells = new ArrayList<>();
                    int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_CELLS_PER_ROW);
                    for (int index = 0; index < last; index++) {
                        Cell cell = row.getCell(index);
                        if (cell != null && cell.getCellType() == CellType.FORMULA) {
                            throw new IOException("文件含公式单元格，请先粘贴为固定值");
                        }
                        cells.add(clean(cell == null ? "" : formatter.formatCellValue(cell)));
                    }
                    if (cells.stream().anyMatch(value -> !value.isBlank())) {
                        rows.add(cells);
                        text.append(String.join("\t", cells)).append('\n');
                    }
                }
            }
        } catch (RuntimeException err) {
            throw new IOException("Excel 文件无法安全解析", err);
        }
        return new DocumentData(limitText(text.toString()), linesFromText(text.toString()), rows);
    }

    private DocumentData extractDelimited(byte[] bytes, char delimiter) throws IOException {
        String text = decodeText(bytes);
        if (text.indexOf('\0') >= 0) {
            throw new IOException("文本文件包含不支持的二进制内容");
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : linesFromText(text)) {
            if (rows.size() >= MAX_ROWS) {
                throw new IOException("文本行数超过 10000 行");
            }
            rows.add(parseDelimitedLine(line, delimiter));
        }
        return new DocumentData(limitText(text), linesFromText(text), rows);
    }

    private DocumentData extractHtml(byte[] bytes) throws IOException {
        String html = decodeText(bytes);
        if (html.indexOf('\0') >= 0) throw new IOException("HTML 文件包含不支持的二进制内容");
        List<List<String>> rows = new ArrayList<>();
        StringBuilder allText = new StringBuilder();
        try {
            new ParserDelegator().parse(new java.io.StringReader(html), new HTMLEditorKit.ParserCallback() {
                private List<String> currentRow;
                private StringBuilder currentCell;

                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.TR) currentRow = new ArrayList<>();
                    if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && currentRow != null) currentCell = new StringBuilder();
                }
                @Override public void handleText(char[] data, int pos) {
                    String value = clean(new String(data));
                    if (value.isBlank()) return;
                    if (allText.length() + value.length() + 1 <= MAX_TEXT_CHARS) allText.append(value).append('\n');
                    if (currentCell != null) currentCell.append(value).append(' ');
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && currentRow != null && currentCell != null) {
                        if (currentRow.size() < MAX_CELLS_PER_ROW) currentRow.add(clean(currentCell.toString()));
                        currentCell = null;
                    }
                    if (tag == HTML.Tag.TR && currentRow != null) {
                        if (rows.size() < MAX_ROWS && currentRow.stream().anyMatch(value -> !value.isBlank())) rows.add(currentRow);
                        currentRow = null;
                    }
                }
            }, true);
        } catch (RuntimeException err) {
            throw new IOException("HTML 文件无法安全解析", err);
        }
        String text = limitText(allText.toString());
        return new DocumentData(text, linesFromText(text), rows);
    }

    private List<ModuleRequirement> extractRequirements(DocumentData data) {
        LinkedHashMap<String, ModuleRequirement> found = new LinkedHashMap<>();
        List<List<String>> candidates = new ArrayList<>();
        int graduationTotalRow = -1;
        for (int index = 0; index < data.rows().size(); index++) {
            if (normalize(String.join(" ", data.rows().get(index))).startsWith(normalize("毕业标准最低总学分合计"))) {
                graduationTotalRow = index;
                break;
            }
        }
        if (graduationTotalRow >= 0) {
            int start = Math.max(0, graduationTotalRow - 10);
            candidates.addAll(data.rows().subList(start, graduationTotalRow));
            data.rows().stream()
                    .filter(row -> normalize(String.join(" ", row)).contains("劳动"))
                    .forEach(candidates::add);
        } else {
            candidates.addAll(data.rows());
            data.lines().forEach(line -> candidates.add(List.of(line)));
        }
        String mergedCategory = "";
        for (List<String> row : candidates) {
            String joined = clean(String.join(" ", row));
            if (!containsAny(joined, PLAN_HINTS)) {
                continue;
            }
            String normalizedRow = normalize(joined);
            if (normalizedRow.contains("通识课程") || normalizedRow.contains("公共课程")) mergedCategory = "通识课程";
            else if (normalizedRow.contains("学科基础课程") || normalizedRow.contains("专业基础课程")) mergedCategory = "学科基础课程";
            else if (normalizedRow.contains("专业课程")) mergedCategory = "专业课程";
            List<String> classificationRow = row;
            if (!mergedCategory.isBlank() && !normalizedRow.contains("劳动")
                    && !normalizedRow.contains(normalize(mergedCategory))) {
                classificationRow = new ArrayList<>(row.size() + 1);
                classificationRow.add(mergedCategory);
                classificationRow.addAll(row);
            }
            String name = canonicalModuleName(classificationRow, joined);
            Double credits = findRequirementCredits(row, joined);
            if (name == null || credits == null || credits <= 0 || credits > 300 || isGrandTotal(name)) {
                continue;
            }
            String key = normalize(name);
            String status = "劳动学分".equals(name) ? "不计入总学分" : "待确认";
            found.put(key, new ModuleRequirement(name, credits, excerpt(joined), status));
            if (found.size() >= 30) {
                break;
            }
        }
        return new ArrayList<>(found.values());
    }

    /** 只保留毕业标准采用的一级学分板块，避免把课程模块误当成独立毕业要求。 */
    private String canonicalModuleName(List<String> row, String text) {
        String value = normalize(String.join(" ", row.subList(0, Math.min(row.size(), 4))));
        if (value.isBlank()) value = normalize(text);
        String full = normalize(text);
        if (value.contains("劳动") && (full.contains("学分") || full.contains("必修"))) return "劳动学分";
        if (value.contains("必修") && value.contains("选修")) return null;
        if ((value.contains("通识") || value.contains("公共")) && value.contains("必修")) return "通识必修";
        if ((value.contains("通识") || value.contains("公共")) && value.contains("选修")) return "通识选修";
        if ((value.contains("学科基础") || value.contains("专业基础")) && value.contains("必修")) return "学科基础必修";
        if (value.contains("专业") && value.contains("必修")) return "专业必修";
        if (value.contains("专业") && value.contains("选修")) return "专业选修";
        return null;
    }

    private List<CourseRecord> extractCourses(DocumentData data) {
        List<CourseRecord> tabular = extractCoursesFromRows(data.rows());
        if (!tabular.isEmpty()) {
            return tabular;
        }
        List<CourseRecord> courses = new ArrayList<>();
        for (String line : data.lines()) {
            String cleaned = clean(line);
            if (cleaned.length() < 4 || containsAny(cleaned, HEADER_NAMES)) {
                continue;
            }
            Matcher numberMatcher = SCORE_PATTERN.matcher(cleaned);
            List<Double> numbers = new ArrayList<>();
            while (numberMatcher.find()) {
                numbers.add(parseNumber(numberMatcher.group(1)));
            }
            if (numbers.isEmpty()) {
                continue;
            }
            Double credit = numbers.stream().filter(value -> value > 0 && value <= 20).findFirst().orElse(null);
            if (credit == null) {
                continue;
            }
            String name = cleaned.replaceAll("\\d+(?:\\.\\d+)?", " ")
                    .replaceAll("(学分|成绩|通过|合格|及格|优秀|良好|中等|不及格)", " ")
                    .replaceAll("\\s+", " ").trim();
            if (name.length() < 2 || name.length() > 80) {
                continue;
            }
            double score = numbers.stream().filter(value -> value > 20 && value <= 100).findFirst().orElse(0d);
            boolean passed = inferPassed(cleaned, score);
            courses.add(new CourseRecord(name, "待归类", credit, score, passed, excerpt(cleaned)));
            if (courses.size() >= MAX_ROWS) {
                break;
            }
        }
        return courses;
    }

    private List<CourseRecord> extractCoursesFromRows(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        int headerIndex = -1;
        ColumnMap columns = null;
        for (int index = 0; index < Math.min(rows.size(), 60); index++) {
            ColumnMap candidate = mapColumns(rows.get(index));
            if (candidate.name() >= 0 && candidate.credit() >= 0) {
                headerIndex = index;
                columns = candidate;
                break;
            }
        }
        if (columns == null) {
            return List.of();
        }
        List<CourseRecord> courses = new ArrayList<>();
        for (int index = headerIndex + 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String name = cell(row, columns.name());
            Double credit = parseCredit(cell(row, columns.credit()));
            if (name.isBlank() || credit == null || credit <= 0 || credit > 30 || isSummaryRow(name)) {
                continue;
            }
            String category = columns.category() >= 0 ? cell(row, columns.category()) : "待归类";
            if (category.isBlank()) {
                category = "待归类";
            }
            String scoreText = columns.score() >= 0 ? cell(row, columns.score()) : "";
            String statusText = columns.status() >= 0 ? cell(row, columns.status()) : "";
            double score = parseScore(scoreText);
            boolean passed = inferPassed(scoreText + " " + statusText, score);
            courses.add(new CourseRecord(name, category, credit, score, passed,
                    excerpt(String.join(" ", row))));
        }
        return courses;
    }

    private ColumnMap mapColumns(List<String> row) {
        int name = -1, credit = -1, category = -1, score = -1, status = -1;
        for (int index = 0; index < row.size(); index++) {
            String value = normalize(row.get(index));
            if (name < 0 && matchesHeader(value, HEADER_NAMES)) name = index;
            if (credit < 0 && matchesHeader(value, HEADER_CREDITS)) credit = index;
            if (value.equals(normalize("课程属性"))) category = index;
            else if (category < 0 && matchesHeader(value, HEADER_CATEGORIES)) category = index;
            if (score < 0 && matchesHeader(value, HEADER_SCORES)) score = index;
            if (status < 0 && matchesHeader(value, HEADER_STATUS)) status = index;
        }
        return new ColumnMap(name, credit, category, score, status);
    }

    private String findModuleName(List<String> row, String joined) {
        for (String cell : row) {
            String value = clean(cell);
            if (value.length() >= 2 && value.length() <= 50 && containsAny(value, PLAN_HINTS)
                    && !value.matches(".*\\d{4}.*")) {
                return value;
            }
        }
        Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z·（）()]{2,30}(?:必修|选修|课程|环节|实践|教育|模块))").matcher(joined);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Double findRequirementCredits(List<String> row, String joined) {
        for (int index = 1; index < row.size(); index++) {
            Double value = parseCredit(row.get(index));
            if (value != null && value > 0 && value <= 300) {
                return value;
            }
        }
        Matcher explicit = Pattern.compile("(?:要求|应修|最低|小计|合计)?\\s*(\\d{1,3}(?:\\.\\d{1,2})?)\\s*学分").matcher(joined);
        if (explicit.find()) {
            return parseNumber(explicit.group(1));
        }
        Matcher any = CREDIT_PATTERN.matcher(joined);
        while (any.find()) {
            double value = parseNumber(any.group(1));
            if (value > 0 && value <= 300 && value < 1900) {
                return value;
            }
        }
        return null;
    }

    private boolean inferPassed(String text, double score) {
        String value = normalize(text);
        if (value.contains("不及格") || value.contains("未通过") || value.contains("不合格")
                || value.contains("缺考") || value.contains("旷考") || value.contains("取消资格")) {
            return false;
        }
        if (value.contains("合格") || value.contains("通过") || value.contains("及格")
                || value.contains("优秀") || value.contains("良好") || value.contains("中等")) {
            return true;
        }
        return score >= 60;
    }

    private String decodeText(byte[] bytes) throws IOException {
        try {
            return limitText(decode(bytes, StandardCharsets.UTF_8));
        } catch (CharacterCodingException ignored) {
            try {
                return limitText(decode(bytes, Charset.forName("GB18030")));
            } catch (CharacterCodingException err) {
                throw new IOException("文本编码无法识别，请另存为 UTF-8", err);
            }
        }
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharBuffer decoded = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString().replace("\uFEFF", "");
    }

    private List<String> parseDelimitedLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == delimiter && !quoted) {
                cells.add(clean(cell.toString()));
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        cells.add(clean(cell.toString()));
        return cells;
    }

    private List<String> linesFromText(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String value = clean(line);
            if (!value.isBlank()) lines.add(value);
            if (lines.size() >= MAX_ROWS) break;
        }
        return lines;
    }

    private String limitText(String text) throws IOException {
        if (text == null) return "";
        if (text.length() > MAX_TEXT_CHARS) throw new IOException("可提取文本超过安全限制");
        return text;
    }

    private void requirePrefix(byte[] bytes, int[] prefix, String message) throws IOException {
        if (bytes.length < prefix.length) throw new IOException(message);
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xff) != prefix[index]) throw new IOException(message);
        }
    }

    private void requireZip(byte[] bytes, String message) throws IOException {
        if (!isZip(bytes)) throw new IOException(message);
    }

    private void inspectZip(byte[] bytes) throws IOException {
        int entries = 0;
        long expanded = 0;
        long ratioLimit = Math.min(MAX_ARCHIVE_EXPANDED_BYTES,
                Math.max(8L * 1024 * 1024, bytes.length * 50L));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("压缩文档条目过多");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) throw new IOException("压缩文档路径不安全");
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    checkInterrupted();
                    entryBytes += read;
                    expanded += read;
                    if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES || expanded > ratioLimit) {
                        throw new IOException("压缩文档解压后超过安全限制");
                    }
                }
            }
        }
    }

    private void requireOle(byte[] bytes, String message) throws IOException {
        requirePrefix(bytes, new int[]{0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1}, message);
    }

    private boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B
                && (bytes[2] == 0x03 || bytes[2] == 0x05 || bytes[2] == 0x07)
                && (bytes[3] == 0x04 || bytes[3] == 0x06 || bytes[3] == 0x08);
    }

    private void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("文件解析已取消");
        }
    }

    private String safeName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("文件名为空");
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (clean.isBlank() || clean.length() > 180) throw new IOException("文件名不合法");
        return clean;
    }

    private String extension(String name) throws IOException {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) throw new IOException("文件缺少扩展名");
        return name.substring(dot + 1);
    }

    private String trimModuleName(String value) {
        return clean(value).replaceFirst("^[一二三四五六七八九十0-9]+[、.．]\\s*", "")
                .replaceAll("[：:]?\\s*\\d+(?:\\.\\d+)?\\s*学分.*$", "").trim();
    }

    private boolean isGrandTotal(String value) {
        String normalized = normalize(value);
        return normalized.equals("合计") || normalized.equals("总计") || normalized.contains("总学分");
    }

    private boolean isSummaryRow(String value) {
        String normalized = normalize(value);
        return normalized.contains("平均") || normalized.contains("合计") || normalized.contains("总计");
    }

    private boolean matchesHeader(String value, Set<String> headers) {
        return headers.stream().map(CreditAuditService::normalize)
                .anyMatch(header -> value.equals(header) || (header.length() >= 4 && value.contains(header)));
    }

    private boolean containsAny(String value, Set<String> hints) {
        return hints.stream().anyMatch(value::contains);
    }

    private Double parseCredit(String value) {
        if (value == null) return null;
        Matcher matcher = CREDIT_PATTERN.matcher(value.trim());
        return matcher.find() ? parseNumber(matcher.group(1)) : null;
    }

    private double parseScore(String value) {
        if (value == null) return 0;
        Matcher matcher = SCORE_PATTERN.matcher(value);
        while (matcher.find()) {
            double number = parseNumber(matcher.group(1));
            if (number >= 0 && number <= 100) return number;
        }
        return 0;
    }

    private static double parseNumber(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? clean(row.get(index)) : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s_—–\\-：:（）()【】\\[\\]]", "");
    }

    private String excerpt(String value) {
        String cleaned = clean(value);
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120) + "…";
    }

    public record ModuleRequirement(String name, double requiredCredits, String source, String status) {}
    public record CourseRecord(String name, String category, double credits, double score, boolean passed, String source) {}
    public record AuditExtraction(List<ModuleRequirement> modules, List<CourseRecord> courses,
                                  List<String> warnings, String planFileName, String scoreFileName) {}
    public record PlanExtraction(List<ModuleRequirement> modules, List<String> warnings, String fileName) {}
    public record CourseExtraction(List<CourseRecord> courses, List<String> warnings, String fileName) {}
    record DocumentData(String text, List<String> lines, List<List<String>> rows) {}
    record ColumnMap(int name, int credit, int category, int score, int status) {}
}
