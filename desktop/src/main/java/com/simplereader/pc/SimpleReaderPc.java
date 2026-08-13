package com.simplereader.pc;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class SimpleReaderPc extends JFrame {
    private static final String CARD_SHELF = "shelf";
    private static final String CARD_READER = "reader";
    private static final String ALL_GROUP = "全部书籍";
    private static final String UNGROUPED = "未分组";

    private final LibraryStore store = new LibraryStore();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardRoot = new JPanel(cards);

    private final DefaultListModel<String> groupModel = new DefaultListModel<>();
    private final JList<String> groupList = new JList<>(groupModel);
    private final DefaultListModel<BookEntry> bookModel = new DefaultListModel<>();
    private final JList<BookEntry> bookList = new JList<>(bookModel);
    private final JTextField shelfSearch = new JTextField();
    private final JLabel shelfStatus = new JLabel(" ");

    private ReaderPane readerPane;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new SimpleReaderPc().setVisible(true);
        });
    }

    public SimpleReaderPc() {
        super("简阅 PC");
        store.load();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 650));
        setSize(1200, 800);
        setLocationRelativeTo(null);
        buildShelf();
        readerPane = new ReaderPane();
        cardRoot.add(readerPane, CARD_READER);
        setContentPane(cardRoot);
        refreshGroupsAndBooks();
        Runtime.getRuntime().addShutdownHook(new Thread(store::save, "SimpleReaderPC-save"));
    }

    private void buildShelf() {
        JPanel shelf = new JPanel(new BorderLayout(12, 12));
        shelf.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));
        shelf.setBackground(new Color(246, 244, 238));

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("简阅");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        header.add(title, BorderLayout.WEST);

        shelfSearch.setToolTipText("按书名筛选书架");
        shelfSearch.putClientProperty("JTextField.placeholderText", "搜索书架");
        shelfSearch.getDocument().addDocumentListener((SimpleDocumentListener) e -> refreshBooks());
        header.add(shelfSearch, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton importFiles = new JButton("导入书籍");
        JButton importFolder = new JButton("导入文件夹");
        JButton remove = new JButton("移出书架");
        importFiles.addActionListener(e -> importFiles());
        importFolder.addActionListener(e -> importFolder());
        remove.addActionListener(e -> removeSelectedBook());
        actions.add(importFiles);
        actions.add(importFolder);
        actions.add(remove);
        header.add(actions, BorderLayout.EAST);
        shelf.add(header, BorderLayout.NORTH);

        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setFixedCellHeight(38);
        groupList.setFont(groupList.getFont().deriveFont(15f));
        groupList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) refreshBooks(); });
        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setPreferredSize(new Dimension(190, 300));
        groupScroll.setBorder(BorderFactory.createTitledBorder("分组"));

        JPanel groupPanel = new JPanel(new BorderLayout(0, 8));
        groupPanel.setOpaque(false);
        groupPanel.add(groupScroll, BorderLayout.CENTER);
        JButton renameGroup = new JButton("重命名分组");
        renameGroup.addActionListener(e -> renameSelectedGroup());
        groupPanel.add(renameGroup, BorderLayout.SOUTH);

        bookList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        bookList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        bookList.setVisibleRowCount(-1);
        bookList.setFixedCellWidth(180);
        bookList.setFixedCellHeight(238);
        bookList.setCellRenderer(new BookCellRenderer());
        bookList.setBackground(new Color(246, 244, 238));
        bookList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelectedBook();
            }
        });
        bookList.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        bookList.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openSelectedBook(); }
        });
        JScrollPane bookScroll = new JScrollPane(bookList);
        bookScroll.setBorder(BorderFactory.createEmptyBorder());
        bookScroll.getVerticalScrollBar().setUnitIncrement(28);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, groupPanel, bookScroll);
        split.setDividerLocation(205);
        split.setResizeWeight(0);
        split.setBorder(null);
        split.setOpaque(false);
        shelf.add(split, BorderLayout.CENTER);

        shelfStatus.setFont(shelfStatus.getFont().deriveFont(13f));
        shelf.add(shelfStatus, BorderLayout.SOUTH);
        cardRoot.add(shelf, CARD_SHELF);
    }

    private void refreshGroupsAndBooks() {
        String previous = groupList.getSelectedValue();
        groupModel.clear();
        groupModel.addElement(ALL_GROUP);
        groupModel.addElement(UNGROUPED);
        for (String group : store.groups()) groupModel.addElement(group);
        int select = 0;
        if (previous != null) {
            for (int i = 0; i < groupModel.size(); i++) if (previous.equals(groupModel.get(i))) select = i;
        }
        groupList.setSelectedIndex(select);
        refreshBooks();
    }

    private void refreshBooks() {
        String group = groupList.getSelectedValue();
        String query = shelfSearch.getText() == null ? "" : shelfSearch.getText().trim().toLowerCase(Locale.ROOT);
        bookModel.clear();
        List<BookEntry> filtered = new ArrayList<>();
        for (BookEntry b : store.books) {
            boolean groupOk = group == null || ALL_GROUP.equals(group)
                    || (UNGROUPED.equals(group) && (b.group == null || b.group.isBlank()))
                    || Objects.equals(group, b.group);
            boolean queryOk = query.isBlank() || b.title.toLowerCase(Locale.ROOT).contains(query)
                    || b.path.getFileName().toString().toLowerCase(Locale.ROOT).contains(query);
            if (groupOk && queryOk) filtered.add(b);
        }
        filtered.sort(Comparator.comparing(b -> b.title.toLowerCase(Locale.ROOT)));
        for (BookEntry b : filtered) bookModel.addElement(b);
        shelfStatus.setText("书架 " + store.books.size() + " 本 · 当前显示 " + filtered.size() + " 本 · 支持 " + BookLoader.supportedDescription());
    }

    private void importFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入本地书籍");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(BookLoader.supportedDescription(),
                "txt", "md", "log", "epub", "chm", "html", "htm", "xhtml", "rtf", "docx", "odt", "fb2"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String targetGroup = selectedImportGroup();
        int added = 0;
        for (java.io.File file : chooser.getSelectedFiles()) {
            Path path = file.toPath();
            if (!BookLoader.isSupported(path)) continue;
            int before = store.books.size();
            store.add(path, targetGroup);
            if (store.books.size() > before) added++;
        }
        refreshGroupsAndBooks();
        shelfStatus.setText("已导入 " + added + " 本；重复文件不会再次加入书架");
    }

    private void importFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择小说文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        int added = 0;
        for (java.io.File rootFile : chooser.getSelectedFiles()) {
            Path root = rootFile.toPath();
            String group = root.getFileName() == null ? "导入分组" : root.getFileName().toString();
            try (var walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile).filter(BookLoader::isSupported)
                        .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)).toList();
                for (Path path : files) {
                    int before = store.books.size();
                    store.add(path, group);
                    if (store.books.size() > before) added++;
                }
            } catch (IOException error) {
                JOptionPane.showMessageDialog(this, "读取文件夹失败：\n" + root + "\n" + error.getMessage(), "导入失败", JOptionPane.ERROR_MESSAGE);
            }
        }
        refreshGroupsAndBooks();
        shelfStatus.setText("文件夹导入完成：新增 " + added + " 本");
    }

    private String selectedImportGroup() {
        String selected = groupList.getSelectedValue();
        if (selected == null || ALL_GROUP.equals(selected) || UNGROUPED.equals(selected)) return "";
        return selected;
    }

    private void renameSelectedGroup() {
        String selected = groupList.getSelectedValue();
        if (selected == null || ALL_GROUP.equals(selected) || UNGROUPED.equals(selected)) {
            JOptionPane.showMessageDialog(this, "请先选择一个实际分组。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String newName = JOptionPane.showInputDialog(this, "新的分组名称", selected);
        if (newName == null || newName.trim().isBlank() || newName.trim().equals(selected)) return;
        store.renameGroup(selected, newName.trim());
        refreshGroupsAndBooks();
        groupList.setSelectedValue(newName.trim(), true);
    }

    private void removeSelectedBook() {
        BookEntry selected = bookList.getSelectedValue();
        if (selected == null) return;
        int result = JOptionPane.showConfirmDialog(this, "只从书架移除，不删除原文件：\n" + selected.title, "移出书架", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;
        store.remove(selected);
        refreshGroupsAndBooks();
    }

    private void openSelectedBook() {
        BookEntry selected = bookList.getSelectedValue();
        if (selected == null) return;
        if (!Files.isRegularFile(selected.path)) {
            JOptionPane.showMessageDialog(this, "原文件已不存在：\n" + selected.path, "无法打开", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        shelfStatus.setText("正在读取《" + selected.title + "》……");
        new SwingWorker<BookDocument, Void>() {
            @Override protected BookDocument doInBackground() throws Exception { return BookLoader.load(selected.path); }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    BookDocument doc = get();
                    readerPane.open(selected, doc);
                    cards.show(cardRoot, CARD_READER);
                    readerPane.requestReaderFocus();
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    JOptionPane.showMessageDialog(SimpleReaderPc.this,
                            "读取失败：\n" + selected.path + "\n\n" + cause.getMessage(), "无法打开书籍", JOptionPane.ERROR_MESSAGE);
                    shelfStatus.setText("读取失败：" + cause.getMessage());
                }
            }
        }.execute();
    }

    private void backToShelf() {
        readerPane.saveProgress();
        cards.show(cardRoot, CARD_SHELF);
        refreshGroupsAndBooks();
    }

    private final class ReaderPane extends JLayeredPane {
        private final ReaderCanvas canvas = new ReaderCanvas();
        private final JPanel topBar = new JPanel(new BorderLayout(8, 0));
        private final JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        private final JLabel readerTitle = new JLabel(" ", SwingConstants.CENTER);
        private final JSlider progressSlider = new JSlider(0, 10000, 0);
        private final JLabel progressText = new JLabel("0%", SwingConstants.RIGHT);
        private final Timer resizeTimer;
        private boolean chromeVisible = true;
        private boolean internalSliderChange = false;
        private BookEntry book;
        private BookDocument document;
        private int chapterIndex;
        private int pageIndex;
        private List<Paginator.Page> pages = List.of();

        ReaderPane() {
            setLayout(null);
            setOpaque(true);
            add(canvas, JLayeredPane.DEFAULT_LAYER);
            buildTopBar();
            buildBottomBar();
            add(topBar, JLayeredPane.PALETTE_LAYER);
            add(bottomBar, JLayeredPane.PALETTE_LAYER);
            resizeTimer = new Timer(180, e -> rebuildPages(currentOffset()));
            resizeTimer.setRepeats(false);
            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { resizeTimer.restart(); }
            });
            installReaderActions();
            canvas.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (document == null) return;
                    int w = Math.max(1, canvas.getWidth());
                    if (e.getX() < w * 0.30) previousPage();
                    else if (e.getX() > w * 0.70) nextPage();
                    else toggleChrome();
                }
            });
            canvas.addMouseWheelListener(this::onMouseWheel);
            applyTheme();
        }

        private void buildTopBar() {
            topBar.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            JButton back = new JButton("← 书架");
            back.addActionListener(e -> backToShelf());
            topBar.add(back, BorderLayout.WEST);
            readerTitle.setFont(readerTitle.getFont().deriveFont(Font.BOLD, 15f));
            topBar.add(readerTitle, BorderLayout.CENTER);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            JButton catalog = new JButton("目录");
            JButton search = new JButton("搜索");
            JButton bookmarks = new JButton("书签");
            catalog.addActionListener(e -> showCatalog());
            search.addActionListener(e -> showSearch());
            bookmarks.addActionListener(e -> showBookmarks());
            right.add(catalog); right.add(search); right.add(bookmarks);
            topBar.add(right, BorderLayout.EAST);
        }

        private void buildBottomBar() {
            bottomBar.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.setOpaque(false);
            JButton prevChapter = new JButton("上一章");
            JButton prevPage = new JButton("上一页");
            JButton nextPage = new JButton("下一页");
            JButton nextChapter = new JButton("下一章");
            prevChapter.addActionListener(e -> previousChapter());
            prevPage.addActionListener(e -> previousPage());
            nextPage.addActionListener(e -> nextPage());
            nextChapter.addActionListener(e -> nextChapter());
            left.add(prevChapter); left.add(prevPage); left.add(nextPage); left.add(nextChapter);
            bottomBar.add(left, BorderLayout.WEST);

            progressSlider.addChangeListener(e -> {
                if (internalSliderChange || document == null) return;
                if (!progressSlider.getValueIsAdjusting()) jumpToOverall(progressSlider.getValue());
            });
            bottomBar.add(progressSlider, BorderLayout.CENTER);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            JButton smaller = new JButton("A−");
            JButton larger = new JButton("A+");
            JButton theme = new JButton("日/夜");
            JButton addBookmark = new JButton("＋书签");
            smaller.addActionListener(e -> changeFont(-2));
            larger.addActionListener(e -> changeFont(2));
            theme.addActionListener(e -> cycleTheme());
            addBookmark.addActionListener(e -> addBookmark());
            progressText.setPreferredSize(new Dimension(62, 28));
            right.add(smaller); right.add(larger); right.add(theme); right.add(addBookmark); right.add(progressText);
            bottomBar.add(right, BorderLayout.EAST);
        }

        @Override public void doLayout() {
            int w = getWidth(), h = getHeight();
            canvas.setBounds(0, 0, w, h);
            int topH = 54, bottomH = 58;
            topBar.setBounds(0, 0, w, topH);
            bottomBar.setBounds(0, Math.max(0, h - bottomH), w, bottomH);
        }

        void open(BookEntry book, BookDocument document) {
            this.book = book;
            this.document = document;
            chapterIndex = Math.max(0, Math.min(document.chapters.size() - 1, book.chapterIndex));
            pageIndex = 0;
            readerTitle.setText(document.title);
            setChromeVisible(true);
            applyTheme();
            SwingUtilities.invokeLater(() -> rebuildPages(book.charOffset));
        }

        void requestReaderFocus() {
            canvas.setFocusable(true);
            canvas.requestFocusInWindow();
        }

        void saveProgress() {
            if (book == null || document == null || pages.isEmpty()) return;
            book.chapterIndex = chapterIndex;
            book.charOffset = currentOffset();
            store.save();
        }

        private int currentOffset() {
            if (pages.isEmpty() || pageIndex < 0 || pageIndex >= pages.size()) return 0;
            return pages.get(pageIndex).startOffset();
        }

        private void rebuildPages(int anchorOffset) {
            if (document == null || document.chapters.isEmpty() || canvas.getWidth() < 120 || canvas.getHeight() < 180) return;
            Chapter chapter = document.chapters.get(chapterIndex);
            Font bodyFont = new Font("Microsoft YaHei UI", Font.PLAIN, store.fontSize);
            FontMetrics fm = canvas.getFontMetrics(bodyFont);
            pages = Paginator.paginate(chapter.text(), fm, canvas.bodyWidth(), canvas.bodyHeight(), Math.max(4, store.fontSize / 4));
            pageIndex = Paginator.pageForOffset(pages, anchorOffset);
            canvas.setBodyFont(bodyFont);
            updateProgress();
            canvas.repaint();
        }

        private void nextPage() {
            if (document == null) return;
            if (pageIndex + 1 < pages.size()) pageIndex++;
            else if (chapterIndex + 1 < document.chapters.size()) { chapterIndex++; rebuildPages(0); return; }
            updateProgress(); saveProgress(); canvas.repaint();
        }

        private void previousPage() {
            if (document == null) return;
            if (pageIndex > 0) pageIndex--;
            else if (chapterIndex > 0) {
                chapterIndex--;
                rebuildPages(Integer.MAX_VALUE);
                pageIndex = Math.max(0, pages.size() - 1);
            }
            updateProgress(); saveProgress(); canvas.repaint();
        }

        private void nextChapter() {
            if (document == null || chapterIndex + 1 >= document.chapters.size()) return;
            chapterIndex++;
            rebuildPages(0);
            saveProgress();
        }

        private void previousChapter() {
            if (document == null || chapterIndex <= 0) return;
            chapterIndex--;
            rebuildPages(0);
            saveProgress();
        }

        private void jumpTo(int chapter, int offset) {
            if (document == null) return;
            chapterIndex = Math.max(0, Math.min(document.chapters.size() - 1, chapter));
            rebuildPages(Math.max(0, offset));
            saveProgress();
            requestReaderFocus();
        }

        private void jumpToOverall(int sliderValue) {
            long target = Math.round((sliderValue / 10000.0) * Math.max(0, document.totalChars - 1));
            BookDocument.Location location = document.locate(target);
            jumpTo(location.chapterIndex(), location.charOffset());
        }

        private void updateProgress() {
            if (document == null || pages.isEmpty()) return;
            long global = document.globalOffset(chapterIndex, currentOffset());
            int value = (int) Math.round(global * 10000.0 / Math.max(1L, document.totalChars));
            value = Math.max(0, Math.min(10000, value));
            internalSliderChange = true;
            progressSlider.setValue(value);
            internalSliderChange = false;
            progressText.setText(String.format(Locale.ROOT, "%.1f%%", value / 100.0));
            readerTitle.setText(document.title + "  ·  " + document.chapters.get(chapterIndex).title());
        }

        private void changeFont(int delta) {
            int anchor = currentOffset();
            store.fontSize = Math.max(14, Math.min(54, store.fontSize + delta));
            store.save();
            rebuildPages(anchor);
        }

        private void cycleTheme() {
            store.theme = (store.theme + 1) % 3;
            store.save();
            applyTheme();
            canvas.repaint();
        }

        private void applyTheme() {
            Theme t = Theme.of(store.theme);
            setBackground(t.background);
            canvas.setTheme(t);
            topBar.setBackground(t.toolbar);
            bottomBar.setBackground(t.toolbar);
            readerTitle.setForeground(t.text);
            progressText.setForeground(t.text);
        }

        private void toggleChrome() { setChromeVisible(!chromeVisible); requestReaderFocus(); }
        private void setChromeVisible(boolean visible) {
            chromeVisible = visible;
            topBar.setVisible(visible);
            bottomBar.setVisible(visible);
            // Overlay-only: canvas bounds and pagination dimensions never change with toolbar visibility.
            canvas.repaint();
        }

        private void showCatalog() {
            if (document == null) return;
            DefaultListModel<String> model = new DefaultListModel<>();
            for (int i = 0; i < document.chapters.size(); i++) model.addElement((i + 1) + ". " + document.chapters.get(i).title());
            JList<String> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(chapterIndex);
            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new Dimension(520, 560));
            int result = JOptionPane.showConfirmDialog(this, scroll, "目录", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result == JOptionPane.OK_OPTION && list.getSelectedIndex() >= 0) jumpTo(list.getSelectedIndex(), 0);
        }

        private void showSearch() {
            if (document == null) return;
            JTextField query = new JTextField();
            DefaultListModel<SearchHit> model = new DefaultListModel<>();
            JList<SearchHit> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new SearchHitRenderer());
            JProgressBar busy = new JProgressBar();
            busy.setIndeterminate(true);
            busy.setVisible(false);
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.add(query, BorderLayout.NORTH);
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
            panel.add(busy, BorderLayout.SOUTH);
            JDialog dialog = new JDialog(SimpleReaderPc.this, "全文搜索", false);
            dialog.setContentPane(panel);
            dialog.setSize(680, 560);
            dialog.setLocationRelativeTo(SimpleReaderPc.this);
            Runnable searchAction = () -> {
                String keyword = query.getText().trim();
                if (keyword.isBlank()) return;
                model.clear(); busy.setVisible(true);
                new SwingWorker<List<SearchHit>, Void>() {
                    @Override protected List<SearchHit> doInBackground() {
                        List<SearchHit> hits = new ArrayList<>();
                        String needle = keyword.toLowerCase(Locale.ROOT);
                        for (int ci = 0; ci < document.chapters.size() && hits.size() < 800; ci++) {
                            Chapter c = document.chapters.get(ci);
                            String lower = c.text().toLowerCase(Locale.ROOT);
                            int from = 0;
                            while (hits.size() < 800) {
                                int at = lower.indexOf(needle, from);
                                if (at < 0) break;
                                int s = Math.max(0, at - 45), e = Math.min(c.text().length(), at + keyword.length() + 65);
                                String snippet = c.text().substring(s, e).replace('\n', ' ').trim();
                                hits.add(new SearchHit(ci, at, c.title(), snippet));
                                from = at + Math.max(1, keyword.length());
                            }
                        }
                        return hits;
                    }
                    @Override protected void done() {
                        busy.setVisible(false);
                        try { for (SearchHit hit : get()) model.addElement(hit); }
                        catch (Exception ignored) {}
                    }
                }.execute();
            };
            query.addActionListener(e -> searchAction.run());
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        SearchHit hit = list.getSelectedValue();
                        if (hit != null) { jumpTo(hit.chapter, hit.offset); dialog.dispose(); }
                    }
                }
            });
            dialog.setVisible(true);
            SwingUtilities.invokeLater(query::requestFocusInWindow);
        }

        private void addBookmark() {
            if (book == null || document == null) return;
            int offset = currentOffset();
            String label = document.chapters.get(chapterIndex).title() + " · " + progressText.getText();
            for (BookmarkAnchor mark : book.bookmarks) {
                if (mark.chapterIndex() == chapterIndex && Math.abs(mark.charOffset() - offset) < 20) return;
            }
            book.bookmarks.add(new BookmarkAnchor(chapterIndex, offset, label));
            store.save();
        }

        private void showBookmarks() {
            if (book == null) return;
            DefaultListModel<BookmarkAnchor> model = new DefaultListModel<>();
            for (BookmarkAnchor b : book.bookmarks) model.addElement(b);
            JList<BookmarkAnchor> list = new JList<>(model);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                    BookmarkAnchor b = (BookmarkAnchor) value;
                    return super.getListCellRendererComponent(l, b.label(), index, selected, focus);
                }
            });
            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new Dimension(520, 420));
            Object[] options = {"跳转", "删除", "关闭"};
            int result = JOptionPane.showOptionDialog(this, scroll, "书签", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            BookmarkAnchor selected = list.getSelectedValue();
            if (selected == null) return;
            if (result == 0) jumpTo(selected.chapterIndex(), selected.charOffset());
            else if (result == 1) { book.bookmarks.remove(selected); store.save(); }
        }

        private void installReaderActions() {
            bind("LEFT", "prev", this::previousPage);
            bind("PAGE_UP", "prev2", this::previousPage);
            bind("RIGHT", "next", this::nextPage);
            bind("PAGE_DOWN", "next2", this::nextPage);
            bind("SPACE", "next3", this::nextPage);
            bind("ESCAPE", "back", SimpleReaderPc.this::backToShelf);
            bind("F11", "chrome", this::toggleChrome);
        }

        private void bind(String keyStroke, String name, Runnable action) {
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(keyStroke), name);
            getActionMap().put(name, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { action.run(); }
            });
        }

        private void onMouseWheel(MouseWheelEvent e) {
            if (e.getWheelRotation() > 0) nextPage();
            else if (e.getWheelRotation() < 0) previousPage();
        }

        private final class ReaderCanvas extends JComponent {
            private Font bodyFont = new Font("Microsoft YaHei UI", Font.PLAIN, store.fontSize);
            private Theme theme = Theme.of(store.theme);

            ReaderCanvas() { setOpaque(true); }
            void setBodyFont(Font font) { bodyFont = font; }
            void setTheme(Theme theme) { this.theme = theme; setBackground(theme.background); setForeground(theme.text); }
            int horizontalMargin() { return Math.max(54, Math.min(150, getWidth() / 9)); }
            int bodyTop() { return 92; }
            int bodyBottom() { return 58; }
            int bodyWidth() { return Math.max(120, getWidth() - horizontalMargin() * 2); }
            int bodyHeight() { return Math.max(80, getHeight() - bodyTop() - bodyBottom()); }

            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                    g.setColor(theme.background); g.fillRect(0, 0, getWidth(), getHeight());
                    if (document == null || document.chapters.isEmpty()) return;
                    Chapter chapter = document.chapters.get(chapterIndex);
                    int margin = horizontalMargin();
                    g.setColor(theme.subtleText);
                    Font heading = bodyFont.deriveFont(Font.BOLD, Math.min(60f, bodyFont.getSize2D() + 3f));
                    g.setFont(heading);
                    FontMetrics hfm = g.getFontMetrics();
                    String headingText = chapter.title();
                    if (hfm.stringWidth(headingText) > bodyWidth()) headingText = ellipsize(headingText, hfm, bodyWidth());
                    g.drawString(headingText, margin, 54);

                    if (pages.isEmpty()) return;
                    Paginator.Page page = pages.get(Math.max(0, Math.min(pageIndex, pages.size() - 1)));
                    g.setFont(bodyFont);
                    g.setColor(theme.text);
                    FontMetrics fm = g.getFontMetrics();
                    int lineHeight = fm.getHeight() + Math.max(4, store.fontSize / 4);
                    int y = bodyTop() + fm.getAscent();
                    for (Paginator.VisualLine line : page.lines()) {
                        g.drawString(line.text(), margin, y);
                        y += lineHeight;
                    }
                    g.setFont(bodyFont.deriveFont(Math.max(11f, bodyFont.getSize2D() * 0.62f)));
                    g.setColor(theme.subtleText);
                    String indicator = (chapterIndex + 1) + "/" + document.chapters.size() + "  ·  " + (pageIndex + 1) + "/" + pages.size();
                    FontMetrics ifm = g.getFontMetrics();
                    g.drawString(indicator, getWidth() - margin - ifm.stringWidth(indicator), getHeight() - 22);
                } finally { g.dispose(); }
            }

            private String ellipsize(String text, FontMetrics fm, int width) {
                if (fm.stringWidth(text) <= width) return text;
                String ellipsis = "…";
                int lo = 0, hi = text.length();
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    if (fm.stringWidth(text.substring(0, mid) + ellipsis) <= width) lo = mid; else hi = mid - 1;
                }
                return text.substring(0, lo) + ellipsis;
            }
        }
    }

    private static final class BookCellRenderer extends JPanel implements ListCellRenderer<BookEntry> {
        private BookEntry book;
        private boolean selected;
        BookCellRenderer() { setOpaque(false); }
        @Override public Component getListCellRendererComponent(JList<? extends BookEntry> list, BookEntry value, int index, boolean isSelected, boolean cellHasFocus) {
            book = value; selected = isSelected; return this;
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                if (selected) {
                    g.setColor(new Color(75, 111, 86, 30));
                    g.fillRoundRect(5, 5, w - 10, h - 10, 18, 18);
                }
                int coverX = 28, coverY = 16, coverW = w - 56, coverH = 150;
                int hue = Math.abs(book == null ? 0 : book.title.hashCode());
                Color cover = new Color(150 + (hue % 55), 126 + ((hue / 7) % 55), 102 + ((hue / 17) % 55));
                g.setColor(new Color(0, 0, 0, 25));
                g.fillRoundRect(coverX + 4, coverY + 5, coverW, coverH, 10, 10);
                g.setColor(cover);
                g.fillRoundRect(coverX, coverY, coverW, coverH, 10, 10);
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(new Color(255, 255, 255, 120));
                g.drawRoundRect(coverX + 8, coverY + 8, coverW - 16, coverH - 16, 7, 7);
                g.setColor(new Color(255, 255, 255, 235));
                g.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 16));
                drawCenteredWrapped(g, book == null ? "" : book.title, coverX + 12, coverY + 38, coverW - 24, 3);

                g.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
                g.setColor(new Color(42, 42, 42));
                String title = book == null ? "" : book.title;
                FontMetrics fm = g.getFontMetrics();
                if (fm.stringWidth(title) > w - 20) {
                    while (title.length() > 2 && fm.stringWidth(title + "…") > w - 20) title = title.substring(0, title.length() - 1);
                    title += "…";
                }
                g.drawString(title, 10, 190);
                g.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                g.setColor(new Color(105, 105, 105));
                String meta = (book == null ? "" : book.extension().toUpperCase(Locale.ROOT)) + "  ·  " + (book == null || book.group.isBlank() ? "未分组" : book.group);
                if (book != null && book.chapterIndex > 0) meta += "  ·  第" + (book.chapterIndex + 1) + "章";
                g.drawString(meta, 10, 214);
            } finally { g.dispose(); }
        }
        private static void drawCenteredWrapped(Graphics2D g, String text, int x, int y, int width, int maxLines) {
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = new ArrayList<>();
            int pos = 0;
            while (pos < text.length() && lines.size() < maxLines) {
                int end = pos + 1;
                while (end <= text.length() && fm.stringWidth(text.substring(pos, end)) <= width) end++;
                end = Math.max(pos + 1, end - 1);
                lines.add(text.substring(pos, end)); pos = end;
            }
            if (pos < text.length() && !lines.isEmpty()) lines.set(lines.size() - 1, lines.get(lines.size() - 1) + "…");
            int yy = y;
            for (String line : lines) {
                g.drawString(line, x + Math.max(0, (width - fm.stringWidth(line)) / 2), yy);
                yy += fm.getHeight() + 4;
            }
        }
    }

    private static final class SearchHit {
        final int chapter, offset; final String chapterTitle, snippet;
        SearchHit(int chapter, int offset, String chapterTitle, String snippet) {
            this.chapter = chapter; this.offset = offset; this.chapterTitle = chapterTitle; this.snippet = snippet;
        }
    }

    private static final class SearchHitRenderer extends JPanel implements ListCellRenderer<SearchHit> {
        private final JLabel title = new JLabel();
        private final JLabel snippet = new JLabel();
        SearchHitRenderer() {
            setLayout(new GridLayout(2, 1, 0, 2));
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            add(title); add(snippet);
        }
        @Override public Component getListCellRendererComponent(JList<? extends SearchHit> list, SearchHit value, int index, boolean selected, boolean focus) {
            title.setText(value.chapterTitle);
            snippet.setText(value.snippet);
            Color bg = selected ? list.getSelectionBackground() : list.getBackground();
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(bg); title.setForeground(fg); snippet.setForeground(fg); setOpaque(true);
            return this;
        }
    }

    private record Theme(Color background, Color text, Color subtleText, Color toolbar) {
        static Theme of(int index) {
            return switch (index) {
                case 1 -> new Theme(new Color(235, 222, 190), new Color(58, 49, 38), new Color(109, 92, 68), new Color(222, 207, 174));
                case 2 -> new Theme(new Color(31, 34, 36), new Color(214, 214, 210), new Color(145, 148, 150), new Color(43, 46, 48));
                default -> new Theme(new Color(248, 246, 239), new Color(43, 43, 42), new Color(112, 110, 103), new Color(238, 235, 226));
            };
        }
    }

    @FunctionalInterface
    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void update(javax.swing.event.DocumentEvent e);
        @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { update(e); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { update(e); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { update(e); }
    }
}
