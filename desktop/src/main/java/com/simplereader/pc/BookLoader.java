package com.simplereader.pc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class BookLoader {
    private static final Set<String> SUPPORTED = Set.of(
            "txt", "md", "log", "epub", "chm", "html", "htm", "xhtml", "rtf", "docx", "odt", "fb2"
    );
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern H1_TAG = Pattern.compile("(?is)<h[12][^>]*>(.*?)</h[12]>");
    private static final Pattern CHM_OBJECT = Pattern.compile("(?is)<object\\s+type\\s*=\\s*[\"']text/sitemap[\"'][^>]*>(.*?)</object>");
    private static final Pattern CHM_PARAM = Pattern.compile("(?is)<param\\s+name\\s*=\\s*[\"']([^\"']+)[\"']\\s+value\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>");
    private static final Pattern EPUB_NAV_LINK = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");

    static boolean isSupported(Path path) {
        if (path == null || path.getFileName() == null) return false;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String supportedDescription() {
        return "TXT / EPUB / CHM / DOCX / ODT / RTF / FB2 / HTML / MD";
    }

    static BookDocument load(Path file) throws Exception {
        String ext = extension(file);
        return switch (ext) {
            case "txt", "md", "log" -> loadTextBook(file);
            case "epub" -> loadEpub(file);
            case "chm" -> loadChm(file);
            case "html", "htm", "xhtml" -> loadHtmlFile(file);
            case "rtf" -> loadRtf(file);
            case "docx" -> loadDocx(file);
            case "odt" -> loadOdt(file);
            case "fb2" -> loadFb2(file);
            default -> throw new IOException("暂不支持该格式：" + ext);
        };
    }

    private static BookDocument loadTextBook(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text = normalizeText(decodeBest(bytes));
        if (text.isBlank()) throw new IOException("文件正文为空");
        List<Chapter> chapters = splitTextChapters(text);
        return new BookDocument(baseName(file), chapters);
    }

    private static List<Chapter> splitTextChapters(String text) {
        List<Chapter> chapters = new ArrayList<>();
        Pattern heading = Pattern.compile(
                "(?m)^[ \\t　]*(?:" +
                        "第[0-9０-９零〇一二三四五六七八九十百千万两]+[卷部篇集章回节幕].{0,45}" +
                        "|(?:卷|部|篇)[0-9０-９零〇一二三四五六七八九十百千万两]+.{0,45}" +
                        "|(?:序章|楔子|引子|序言|前言|后记|尾声|终章|番外(?:篇)?)(?:[：:·・.、 \\t　-].{0,45})?" +
                        "|(?:chapter|part|volume)\\s+[0-9ivxlcdm]+.{0,45}" +
                        ")[ \\t　]*$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );
        Matcher m = heading.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find() && starts.size() < 20000) {
            String title = text.substring(m.start(), m.end()).trim();
            if (title.length() > 70) continue;
            starts.add(m.start());
            titles.add(title);
        }
        if (starts.isEmpty()) {
            chapters.add(new Chapter("正文", text.trim()));
            return chapters;
        }
        int first = starts.get(0);
        if (first > 0) {
            String preface = text.substring(0, first).trim();
            if (preface.length() >= 20) chapters.add(new Chapter("正文开始", preface));
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int bodyStart = lineEnd(text, start);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String body = text.substring(Math.min(bodyStart, end), end).trim();
            if (body.isBlank()) body = titles.get(i);
            chapters.add(new Chapter(titles.get(i), body));
        }
        return chapters;
    }

    private static int lineEnd(String text, int start) {
        int n = text.indexOf('\n', start);
        return n < 0 ? text.length() : n + 1;
    }

    private static BookDocument loadHtmlFile(Path file) throws IOException {
        String html = decodeBest(Files.readAllBytes(file));
        String title = extractHtmlTitle(html, baseName(file));
        String text = htmlToText(html);
        return new BookDocument(title, List.of(new Chapter(title, text)));
    }

    private static BookDocument loadRtf(Path file) throws Exception {
        RTFEditorKit kit = new RTFEditorKit();
        DefaultStyledDocument doc = new DefaultStyledDocument();
        try (InputStream in = Files.newInputStream(file)) {
            kit.read(in, doc, 0);
        }
        String text = normalizeText(doc.getText(0, doc.getLength()));
        return new BookDocument(baseName(file), splitTextChapters(text));
    }

    private static BookDocument loadDocx(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry document = zip.getEntry("word/document.xml");
            if (document == null) throw new IOException("DOCX 缺少正文 document.xml");
            String xml = new String(readAll(zip.getInputStream(document)), StandardCharsets.UTF_8);
            xml = xml.replaceAll("(?is)</w:p>", "\\n")
                     .replaceAll("(?is)<w:tab[^>]*/>", "\\t")
                     .replaceAll("(?is)<w:br[^>]*/>", "\\n");
            String text = normalizeText(unescapeXml(xml.replaceAll("(?is)<[^>]+>", "")));
            return new BookDocument(baseName(file), splitTextChapters(text));
        }
    }

    private static BookDocument loadOdt(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry content = zip.getEntry("content.xml");
            if (content == null) throw new IOException("ODT 缺少 content.xml");
            String xml = new String(readAll(zip.getInputStream(content)), StandardCharsets.UTF_8);
            xml = xml.replaceAll("(?is)</text:p>", "\\n")
                     .replaceAll("(?is)</text:h>", "\\n")
                     .replaceAll("(?is)<text:line-break[^>]*/>", "\\n")
                     .replaceAll("(?is)<text:tab[^>]*/>", "\\t");
            String text = normalizeText(unescapeXml(xml.replaceAll("(?is)<[^>]+>", "")));
            return new BookDocument(baseName(file), splitTextChapters(text));
        }
    }

    private static BookDocument loadFb2(Path file) throws IOException {
        String xml = decodeBest(Files.readAllBytes(file));
        String title = firstTagText(xml, "book-title");
        if (title.isBlank()) title = baseName(file);
        List<Chapter> chapters = new ArrayList<>();
        Pattern section = Pattern.compile("(?is)<section\\b[^>]*>(.*?)</section>");
        Matcher sm = section.matcher(xml);
        while (sm.find() && chapters.size() < 20000) {
            String block = sm.group(1);
            String sectionTitle = firstTagText(block, "title");
            String text = htmlToText(block);
            if (!text.isBlank()) chapters.add(new Chapter(sectionTitle.isBlank() ? "正文" : sectionTitle, text));
        }
        if (chapters.isEmpty()) chapters.add(new Chapter(title, htmlToText(xml)));
        return new BookDocument(title, chapters);
    }

    private static BookDocument loadEpub(Path file) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) throw new IOException("EPUB 缺少 container.xml");
            Document container = parseXml(readAll(zip.getInputStream(containerEntry)));
            NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
            if (roots.getLength() == 0) roots = container.getElementsByTagName("rootfile");
            if (roots.getLength() == 0) throw new IOException("EPUB 找不到 OPF");
            String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) throw new IOException("EPUB OPF 不存在：" + opfPath);
            Document opf = parseXml(readAll(zip.getInputStream(opfEntry)));

            String title = firstElementText(opf, "title");
            if (title.isBlank()) title = baseName(file);
            Map<String, ManifestItem> manifest = new HashMap<>();
            NodeList items = opf.getElementsByTagNameNS("*", "item");
            if (items.getLength() == 0) items = opf.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element e = (Element) items.item(i);
                manifest.put(e.getAttribute("id"), new ManifestItem(
                        e.getAttribute("href"), e.getAttribute("media-type"), e.getAttribute("properties")
                ));
            }

            Map<String, String> tocTitles = readEpubToc(zip, opfPath, manifest);
            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            if (refs.getLength() == 0) refs = opf.getElementsByTagName("itemref");
            List<Chapter> chapters = new ArrayList<>();
            for (int i = 0; i < refs.getLength() && chapters.size() < 20000; i++) {
                Element ref = (Element) refs.item(i);
                ManifestItem item = manifest.get(ref.getAttribute("idref"));
                if (item == null || item.href.isBlank()) continue;
                String entryPath = resolveZipPath(opfPath, item.href);
                ZipEntry chapterEntry = findZipEntry(zip, stripFragment(entryPath));
                if (chapterEntry == null) continue;
                String html = decodeBest(readAll(zip.getInputStream(chapterEntry)));
                String text = htmlToText(html);
                if (text.isBlank()) continue;
                String key = stripFragment(entryPath).toLowerCase(Locale.ROOT);
                String chapterTitle = tocTitles.getOrDefault(key, extractHtmlTitle(html, ""));
                if (chapterTitle.isBlank()) chapterTitle = "第 " + (chapters.size() + 1) + " 节";
                chapters.add(new Chapter(chapterTitle, text));
            }
            if (chapters.isEmpty()) throw new IOException("EPUB 中没有读取到正文");
            return new BookDocument(title, chapters);
        }
    }

    private static Map<String, String> readEpubToc(ZipFile zip, String opfPath, Map<String, ManifestItem> manifest) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ManifestItem item : manifest.values()) {
            if (item.properties.toLowerCase(Locale.ROOT).contains("nav")) {
                String navPath = resolveZipPath(opfPath, item.href);
                ZipEntry entry = findZipEntry(zip, stripFragment(navPath));
                if (entry == null) continue;
                try {
                    String html = decodeBest(readAll(zip.getInputStream(entry)));
                    Matcher m = EPUB_NAV_LINK.matcher(html);
                    while (m.find()) {
                        String path = resolveZipPath(navPath, m.group(1));
                        String label = htmlToText(m.group(2)).trim();
                        if (!label.isBlank()) result.putIfAbsent(stripFragment(path).toLowerCase(Locale.ROOT), label);
                    }
                } catch (Exception ignored) {}
            }
        }
        for (ManifestItem item : manifest.values()) {
            if (!item.mediaType.toLowerCase(Locale.ROOT).contains("ncx")) continue;
            String ncxPath = resolveZipPath(opfPath, item.href);
            ZipEntry entry = findZipEntry(zip, stripFragment(ncxPath));
            if (entry == null) continue;
            try {
                Document ncx = parseXml(readAll(zip.getInputStream(entry)));
                NodeList navPoints = ncx.getElementsByTagNameNS("*", "navPoint");
                if (navPoints.getLength() == 0) navPoints = ncx.getElementsByTagName("navPoint");
                for (int i = 0; i < navPoints.getLength(); i++) {
                    Element nav = (Element) navPoints.item(i);
                    String label = firstElementText(nav, "text");
                    Element content = firstElement(nav, "content");
                    if (content == null) continue;
                    String src = content.getAttribute("src");
                    String path = resolveZipPath(ncxPath, src);
                    if (!label.isBlank()) result.putIfAbsent(stripFragment(path).toLowerCase(Locale.ROOT), label.trim());
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static BookDocument loadChm(Path file) throws Exception {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) throw new IOException("CHM 内置读取仅支持 Windows");
        Path hh = Path.of(windir, "hh.exe");
        if (!Files.isRegularFile(hh)) throw new IOException("找不到 Windows HTML Help（hh.exe）");
        Path temp = Files.createTempDirectory("simplereader_chm_");
        try {
            Process process = new ProcessBuilder(hh.toString(), "-decompile", temp.toString(), file.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            try { process.getInputStream().transferTo(OutputStreamNull.INSTANCE); } catch (IOException ignored) {}
            process.waitFor(90, TimeUnit.SECONDS);
            waitForExtractedFiles(temp, Duration.ofSeconds(3));
            List<Path> htmlFiles = listHtmlFiles(temp);
            if (htmlFiles.isEmpty()) throw new IOException("CHM 解包后没有找到可读正文");

            Map<String, Path> relativeLookup = new HashMap<>();
            for (Path html : htmlFiles) {
                String rel = temp.relativize(html).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                relativeLookup.put(rel, html);
            }
            List<Chapter> chapters = new ArrayList<>();
            Set<Path> used = new LinkedHashSet<>();
            List<Path> hhcFiles = new ArrayList<>();
            try (var stream = Files.walk(temp)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hhc"))
                        .forEach(hhcFiles::add);
            }
            hhcFiles.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
            for (Path hhc : hhcFiles) {
                String toc = decodeBest(Files.readAllBytes(hhc));
                Matcher obj = CHM_OBJECT.matcher(toc);
                while (obj.find() && chapters.size() < 20000) {
                    String block = obj.group(1);
                    Matcher param = CHM_PARAM.matcher(block);
                    String name = "", local = "";
                    while (param.find()) {
                        if (param.group(1).equalsIgnoreCase("Name")) name = unescapeXml(param.group(2));
                        if (param.group(1).equalsIgnoreCase("Local")) local = unescapeXml(param.group(2));
                    }
                    if (local.isBlank()) continue;
                    String normalized = stripFragment(local).replace('\\', '/');
                    while (normalized.startsWith("/")) normalized = normalized.substring(1);
                    Path html = relativeLookup.get(normalized.toLowerCase(Locale.ROOT));
                    if (html == null) html = findByFileName(htmlFiles, Path.of(normalized).getFileName().toString());
                    if (html == null || !used.add(html)) continue;
                    String source = decodeBest(Files.readAllBytes(html));
                    String text = htmlToText(source);
                    if (text.isBlank()) continue;
                    String chapterTitle = name.isBlank() ? extractHtmlTitle(source, baseName(html)) : name.trim();
                    chapters.add(new Chapter(chapterTitle, text));
                }
            }
            for (Path html : htmlFiles) {
                if (chapters.size() >= 20000) break;
                if (!used.add(html)) continue;
                String source = decodeBest(Files.readAllBytes(html));
                String text = htmlToText(source);
                if (text.isBlank()) continue;
                chapters.add(new Chapter(extractHtmlTitle(source, baseName(html)), text));
            }
            if (chapters.isEmpty()) throw new IOException("CHM 中没有读取到可显示内容");
            return new BookDocument(baseName(file), chapters);
        } finally {
            deleteTree(temp);
        }
    }

    private static void waitForExtractedFiles(Path temp, Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            try (var stream = Files.list(temp)) {
                if (stream.findAny().isPresent()) return;
            } catch (IOException ignored) {}
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static List<Path> listHtmlFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".htm") || n.endsWith(".html") || n.endsWith(".xhtml");
            }).forEach(result::add);
        }
        result.sort(Comparator.comparing(p -> root.relativize(p).toString(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static Path findByFileName(List<Path> files, String name) {
        for (Path p : files) if (p.getFileName().toString().equalsIgnoreCase(name)) return p;
        return null;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    static String decodeBest(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8;
        Charset gb = charset("GB18030");
        String gbText = decodeStrict(bytes, gb);
        Charset big5 = charset("Big5");
        String big5Text = decodeStrict(bytes, big5);
        if (gbText == null) return big5Text == null ? new String(bytes, StandardCharsets.UTF_8) : big5Text;
        if (big5Text == null) return gbText;
        return textQuality(gbText) >= textQuality(big5Text) ? gbText : big5Text;
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { return null; }
    }

    private static Charset charset(String name) {
        try { return Charset.forName(name); } catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static int textQuality(String text) {
        int good = 0, bad = 0;
        int limit = Math.min(text.length(), 12000);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if ((c >= '\u4E00' && c <= '\u9FFF') || Character.isLetterOrDigit(c) || Character.isWhitespace(c)) good++;
            if (c == '\uFFFD' || (c < 32 && c != '\n' && c != '\r' && c != '\t')) bad += 6;
        }
        return good - bad;
    }

    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style\\b[^>]*>.*?</style>", "")
                .replaceAll("(?is)<head\\b[^>]*>.*?</head>", "")
                .replaceAll("(?is)<br\\s*/?>", "\\n")
                .replaceAll("(?is)</(?:p|div|li|h[1-6]|tr|section|article|blockquote)\\s*>", "\\n")
                .replaceAll("(?is)<li\\b[^>]*>", "• ")
                .replaceAll("(?is)<[^>]+>", "");
        return normalizeText(unescapeXml(s));
    }

    private static String extractHtmlTitle(String html, String fallback) {
        Matcher m = TITLE_TAG.matcher(html == null ? "" : html);
        if (m.find()) {
            String value = htmlToText(m.group(1)).trim();
            if (!value.isBlank()) return value;
        }
        m = H1_TAG.matcher(html == null ? "" : html);
        if (m.find()) {
            String value = htmlToText(m.group(1)).trim();
            if (!value.isBlank()) return value;
        }
        return fallback == null || fallback.isBlank() ? "正文" : fallback;
    }

    private static String firstTagText(String xml, String tag) {
        Matcher m = Pattern.compile("(?is)<(?:\\w+:)?" + Pattern.quote(tag) + "\\b[^>]*>(.*?)</(?:\\w+:)?" + Pattern.quote(tag) + ">").matcher(xml);
        return m.find() ? htmlToText(m.group(1)).trim() : "";
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t　]+\\n", "\\n")
                .replaceAll("\\n[ \\t　]+", "\\n")
                .replaceAll("\\n{4,}", "\\n\\n\\n")
                .trim();
    }

    private static String unescapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
        Matcher dec = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuffer b = new StringBuffer();
        while (dec.find()) {
            int cp;
            try { cp = Integer.parseInt(dec.group(1)); } catch (Exception e) { cp = 0xFFFD; }
            dec.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(Math.max(0, Math.min(0x10FFFF, cp))))));
        }
        dec.appendTail(b);
        Matcher hex = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(b.toString());
        StringBuffer h = new StringBuffer();
        while (hex.find()) {
            int cp;
            try { cp = Integer.parseInt(hex.group(1), 16); } catch (Exception e) { cp = 0xFFFD; }
            hex.appendReplacement(h, Matcher.quoteReplacement(new String(Character.toChars(Math.max(0, Math.min(0x10FFFF, cp))))));
        }
        hex.appendTail(h);
        return h.toString();
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(bytes));
    }

    private static String firstElementText(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) nodes = doc.getElementsByTagName(localName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String firstElementText(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) nodes = root.getElementsByTagName(localName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static Element firstElement(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) nodes = root.getElementsByTagName(localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String resolveZipPath(String baseFile, String relative) {
        relative = relative == null ? "" : relative.replace('\\', '/');
        String fragment = "";
        int hash = relative.indexOf('#');
        if (hash >= 0) { fragment = relative.substring(hash); relative = relative.substring(0, hash); }
        Path base = Path.of("/" + baseFile.replace('\\', '/')).getParent();
        Path resolved = base == null ? Path.of("/" + relative) : base.resolve(relative).normalize();
        String value = resolved.toString().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        return value + fragment;
    }

    private static String stripFragment(String path) {
        int hash = path.indexOf('#');
        return hash >= 0 ? path.substring(0, hash) : path;
    }

    private static ZipEntry findZipEntry(ZipFile zip, String name) {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) return direct;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().equalsIgnoreCase(name)) return e;
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static String baseName(Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private record ManifestItem(String href, String mediaType, String properties) {}

    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();
        @Override public void write(int b) {}
        @Override public void write(byte[] b, int off, int len) {}
    }
}
