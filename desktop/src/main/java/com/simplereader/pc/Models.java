package com.simplereader.pc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class BookEntry {
    final String id;
    Path path;
    String title;
    String group;
    int chapterIndex;
    int charOffset;
    final List<BookmarkAnchor> bookmarks = new ArrayList<>();

    BookEntry(String id, Path path, String title, String group) {
        this.id = id;
        this.path = path;
        this.title = title;
        this.group = group == null ? "" : group;
    }

    static BookEntry create(Path path, String group) {
        String fileName = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String title = dot > 0 ? fileName.substring(0, dot) : fileName;
        return new BookEntry(UUID.randomUUID().toString(), path.toAbsolutePath().normalize(), title, group);
    }

    String extension() {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}

record BookmarkAnchor(int chapterIndex, int charOffset, String label) {}
record Chapter(String title, String text) {}

final class BookDocument {
    final String title;
    final List<Chapter> chapters;
    final long totalChars;
    private final long[] chapterStarts;

    BookDocument(String title, List<Chapter> chapters) {
        this.title = title == null || title.isBlank() ? "未命名" : title.trim();
        this.chapters = List.copyOf(chapters);
        this.chapterStarts = new long[this.chapters.size()];
        long sum = 0L;
        for (int i = 0; i < this.chapters.size(); i++) {
            chapterStarts[i] = sum;
            sum += Math.max(1, this.chapters.get(i).text().length());
        }
        totalChars = Math.max(1L, sum);
    }

    long globalOffset(int chapterIndex, int charOffset) {
        if (chapters.isEmpty()) return 0L;
        int ci = Math.max(0, Math.min(chapters.size() - 1, chapterIndex));
        int local = Math.max(0, Math.min(chapters.get(ci).text().length(), charOffset));
        return chapterStarts[ci] + local;
    }

    Location locate(long globalOffset) {
        if (chapters.isEmpty()) return new Location(0, 0);
        long target = Math.max(0L, Math.min(totalChars - 1L, globalOffset));
        int lo = 0, hi = chapterStarts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long start = chapterStarts[mid];
            long next = mid + 1 < chapterStarts.length ? chapterStarts[mid + 1] : totalChars;
            if (target < start) hi = mid - 1;
            else if (target >= next) lo = mid + 1;
            else return new Location(mid, (int) Math.max(0, target - start));
        }
        return new Location(chapters.size() - 1, 0);
    }

    record Location(int chapterIndex, int charOffset) {}
}

final class LibraryStore {
    private final Path baseDir;
    private final Path libraryFile;
    final List<BookEntry> books = new ArrayList<>();
    int fontSize = 24;
    int theme = 0;

    LibraryStore() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            baseDir = Paths.get(System.getProperty("user.home"), ".simplereader-pc");
        } else {
            baseDir = Paths.get(appData, "SimpleReaderPC");
        }
        libraryFile = baseDir.resolve("library.properties");
    }

    void load() {
        books.clear();
        if (!Files.isRegularFile(libraryFile)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(libraryFile)) {
            p.load(in);
        } catch (IOException ignored) {
            return;
        }
        fontSize = parseInt(p.getProperty("ui.fontSize"), 24, 14, 54);
        theme = parseInt(p.getProperty("ui.theme"), 0, 0, 2);
        int count = parseInt(p.getProperty("book.count"), 0, 0, 100000);
        for (int i = 0; i < count; i++) {
            String prefix = "book." + i + ".";
            String pathValue = decode(p.getProperty(prefix + "path"));
            if (pathValue.isBlank()) continue;
            Path path;
            try { path = Paths.get(pathValue); } catch (Exception e) { continue; }
            BookEntry b = new BookEntry(
                    valueOr(p.getProperty(prefix + "id"), UUID.randomUUID().toString()),
                    path,
                    decode(p.getProperty(prefix + "title")),
                    decode(p.getProperty(prefix + "group"))
            );
            if (b.title == null || b.title.isBlank()) b.title = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            b.chapterIndex = Math.max(0, parseInt(p.getProperty(prefix + "chapter"), 0, 0, Integer.MAX_VALUE));
            b.charOffset = Math.max(0, parseInt(p.getProperty(prefix + "offset"), 0, 0, Integer.MAX_VALUE));
            String bookmarkText = decode(p.getProperty(prefix + "bookmarks"));
            if (!bookmarkText.isBlank()) {
                for (String line : bookmarkText.split("\\n")) {
                    String[] parts = line.split("\\t", 3);
                    if (parts.length < 2) continue;
                    int chapter = parseInt(parts[0], -1, -1, Integer.MAX_VALUE);
                    int offset = parseInt(parts[1], -1, -1, Integer.MAX_VALUE);
                    String label = parts.length >= 3 ? parts[2] : "书签";
                    if (chapter >= 0 && offset >= 0) b.bookmarks.add(new BookmarkAnchor(chapter, offset, label));
                }
            }
            books.add(b);
        }
        dedupe();
    }

    void save() {
        try { Files.createDirectories(baseDir); } catch (IOException ignored) {}
        Properties p = new Properties();
        p.setProperty("ui.fontSize", Integer.toString(fontSize));
        p.setProperty("ui.theme", Integer.toString(theme));
        p.setProperty("book.count", Integer.toString(books.size()));
        for (int i = 0; i < books.size(); i++) {
            BookEntry b = books.get(i);
            String prefix = "book." + i + ".";
            p.setProperty(prefix + "id", b.id);
            p.setProperty(prefix + "path", encode(b.path.toString()));
            p.setProperty(prefix + "title", encode(b.title));
            p.setProperty(prefix + "group", encode(b.group));
            p.setProperty(prefix + "chapter", Integer.toString(b.chapterIndex));
            p.setProperty(prefix + "offset", Integer.toString(b.charOffset));
            StringBuilder marks = new StringBuilder();
            for (BookmarkAnchor mark : b.bookmarks) {
                if (marks.length() > 0) marks.append('\n');
                marks.append(mark.chapterIndex()).append('\t').append(mark.charOffset()).append('\t')
                        .append(mark.label() == null ? "书签" : mark.label().replace('\n', ' '));
            }
            p.setProperty(prefix + "bookmarks", encode(marks.toString()));
        }
        try (OutputStream out = Files.newOutputStream(libraryFile)) {
            p.store(out, "SimpleReader PC library");
        } catch (IOException ignored) {}
    }

    BookEntry add(Path path, String group) {
        Path normalized = path.toAbsolutePath().normalize();
        for (BookEntry existing : books) {
            if (existing.path.toAbsolutePath().normalize().equals(normalized)) return existing;
        }
        BookEntry entry = BookEntry.create(normalized, group == null ? "" : group);
        books.add(entry);
        books.sort(Comparator.comparing(b -> b.title.toLowerCase()));
        save();
        return entry;
    }

    void remove(BookEntry entry) {
        books.remove(entry);
        save();
    }

    List<String> groups() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (BookEntry b : books) if (b.group != null && !b.group.isBlank()) seen.put(b.group, Boolean.TRUE);
        return new ArrayList<>(seen.keySet());
    }

    void renameGroup(String oldName, String newName) {
        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) return;
        for (BookEntry b : books) if (oldName.equals(b.group)) b.group = newName.trim();
        save();
    }

    private void dedupe() {
        Map<Path, BookEntry> unique = new LinkedHashMap<>();
        for (BookEntry b : books) unique.putIfAbsent(b.path.toAbsolutePath().normalize(), b);
        books.clear();
        books.addAll(unique.values());
    }

    private static String encode(String value) {
        if (value == null) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isBlank()) return "";
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return ""; }
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
        catch (Exception e) { return fallback; }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
