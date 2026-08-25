package io.github.liumaishenjian.ccjava.cli.stdio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 真实 stdio E2E 使用的最小 OpenXML 工作簿生成与校验进程。
 *
 * <p>该类型只存在于测试 classpath；生产 Runtime 仍通过真实 {@code run_command} 启动独立
 * Java 进程。工作表写入河南 18 个地市各 7 天共 126 条数据记录，校验进程重新读取 ZIP part，
 * 验证记录基数、城市覆盖、日期覆盖和必需 OpenXML relationship，而不是只检查文件名或 ZIP magic。</p>
 */
public final class WorkbookMakerFixtureMain {
    private static final List<String> CITIES = List.of(
            "郑州", "开封", "洛阳", "平顶山", "安阳", "鹤壁", "新乡", "焦作", "濮阳",
            "许昌", "漯河", "三门峡", "南阳", "商丘", "信阳", "周口", "驻马店", "济源");
    private static final List<String> WEATHER = List.of("晴", "多云", "阴", "小雨", "晴", "多云", "阵雨");
    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 25);
    private static final int DATA_ROWS = 18 * 7;
    private static final Map<String, String> ENTRIES = entries();

    private WorkbookMakerFixtureMain() { }

    /**
     * 执行生成、校验或带真实等待的校验。
     *
     * @param args mode 与 Workspace-relative XLSX 路径
     * @throws Exception 文件、ZIP 或等待失败时由进程以非零状态退出
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) System.exit(64);
        Path target = Path.of(args[1]);
        switch (args[0]) {
            case "generate" -> generate(target);
            case "verify" -> verify(target);
            case "slow-verify" -> {
                Thread.sleep(1_200);
                verify(target);
            }
            default -> System.exit(64);
        }
    }

    private static void generate(Path target) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : ENTRIES.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void verify(Path target) throws Exception {
        if (!Files.isRegularFile(target) || Files.size(target) < 3_000) System.exit(2);
        try (ZipFile zip = new ZipFile(target.toFile())) {
            for (String name : ENTRIES.keySet()) {
                if (zip.getEntry(name) == null) System.exit(3);
            }
            String workbook = new String(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            String sheet = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            if (occurrences(sheet, "<row r=\"") != DATA_ROWS + 1
                    || !sheet.contains("<dimension ref=\"A1:C127\"")
                    || !workbook.contains("河南18地市7天天气（126条）")) {
                System.exit(4);
            }
            for (String city : CITIES) {
                if (occurrences(sheet, "><t>" + city + "</t>") != 7) System.exit(5);
            }
            for (int day = 0; day < 7; day++) {
                if (occurrences(sheet, "><t>" + START_DATE.plusDays(day) + "</t>") != CITIES.size()) {
                    System.exit(6);
                }
            }
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

    private static Map<String, String> entries() {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        entries.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        entries.put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"河南18地市7天天气（126条）\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        entries.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        entries.put("xl/worksheets/sheet1.xml", sheetXml());
        return Map.copyOf(entries);
    }

    private static String sheetXml() {
        StringBuilder xml = new StringBuilder(32_768)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<dimension ref=\"A1:C127\"/><sheetData>")
                .append(row(1, "城市", "日期", "天气"));
        int row = 2;
        for (int city = 0; city < CITIES.size(); city++) {
            for (int day = 0; day < 7; day++) {
                xml.append(row(row++, CITIES.get(city), START_DATE.plusDays(day).toString(),
                        WEATHER.get((city + day) % WEATHER.size())));
            }
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static String row(int row, String city, String date, String weather) {
        return "<row r=\"" + row + "\">" + inlineCell("A", row, city)
                + inlineCell("B", row, date) + inlineCell("C", row, weather) + "</row>";
    }

    private static String inlineCell(String column, int row, String value) {
        return "<c r=\"" + column + row + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }
}
