package com.simplereader.pc;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

final class Paginator {
    record VisualLine(String text, int startOffset, int endOffset) {}
    record Page(int startOffset, int endOffset, List<VisualLine> lines) {}

    static List<Page> paginate(String text, FontMetrics fm, int contentWidth, int contentHeight, int extraLineGap) {
        String source = text == null ? "" : text;
        int width = Math.max(120, contentWidth);
        int lineHeight = Math.max(1, fm.getHeight() + Math.max(0, extraLineGap));
        int maxLines = Math.max(1, contentHeight / lineHeight);
        List<VisualLine> allLines = wrap(source, fm, width);
        if (allLines.isEmpty()) allLines.add(new VisualLine("", 0, 0));
        List<Page> pages = new ArrayList<>((allLines.size() + maxLines - 1) / maxLines);
        for (int i = 0; i < allLines.size(); i += maxLines) {
            int end = Math.min(allLines.size(), i + maxLines);
            List<VisualLine> slice = List.copyOf(allLines.subList(i, end));
            int startOffset = slice.get(0).startOffset();
            int endOffset = slice.get(slice.size() - 1).endOffset();
            pages.add(new Page(startOffset, endOffset, slice));
        }
        return pages;
    }

    static int pageForOffset(List<Page> pages, int offset) {
        if (pages == null || pages.isEmpty()) return 0;
        int target = Math.max(0, offset);
        int lo = 0, hi = pages.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Page p = pages.get(mid);
            if (target < p.startOffset()) hi = mid - 1;
            else if (target >= p.endOffset() && mid + 1 < pages.size()) lo = mid + 1;
            else return mid;
        }
        return Math.max(0, Math.min(pages.size() - 1, lo));
    }

    private static List<VisualLine> wrap(String text, FontMetrics fm, int width) {
        List<VisualLine> result = new ArrayList<>();
        int length = text.length();
        int paragraphStart = 0;
        while (paragraphStart <= length) {
            int newline = text.indexOf('\n', paragraphStart);
            int paragraphEnd = newline >= 0 ? newline : length;
            wrapParagraph(text, paragraphStart, paragraphEnd, fm, width, result);
            if (newline < 0) break;
            if (paragraphEnd == paragraphStart) {
                result.add(new VisualLine("", paragraphStart, Math.min(length, paragraphStart + 1)));
            }
            paragraphStart = newline + 1;
        }
        return result;
    }

    private static void wrapParagraph(String text, int start, int end, FontMetrics fm, int width, List<VisualLine> out) {
        if (start >= end) return;
        int pos = start;
        while (pos < end) {
            int fit = furthestFit(text, pos, end, fm, width);
            if (fit <= pos) fit = Math.min(end, pos + 1);
            int adjusted = adjustBreak(text, pos, fit, end);
            if (adjusted > pos) fit = adjusted;
            String line = text.substring(pos, fit).stripTrailing();
            out.add(new VisualLine(line, pos, fit));
            pos = fit;
            while (pos < end && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) pos++;
        }
    }

    private static int furthestFit(String text, int start, int end, FontMetrics fm, int width) {
        int lo = start + 1;
        int hi = end;
        int best = start;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int pixels = fm.stringWidth(text.substring(start, mid));
            if (pixels <= width) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static int adjustBreak(String text, int start, int fit, int end) {
        if (fit >= end || fit - start < 10) return fit;
        int scanStart = Math.max(start + 1, fit - 24);
        for (int i = fit - 1; i >= scanStart; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || isPunctuation(c)) return i + 1;
        }
        return fit;
    }

    private static boolean isPunctuation(char c) {
        return "，。！？；：、,.!?;:）)]】》〉」』”’—…".indexOf(c) >= 0;
    }
}
