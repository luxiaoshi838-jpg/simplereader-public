#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

def replace_once(path: Path, old: str, new: str, label: str):
    s = path.read_text(encoding='utf-8')
    if new in s:
        return
    if old not in s:
        raise SystemExit(f'v779 patch anchor missing: {label}')
    path.write_text(s.replace(old, new, 1), encoding='utf-8')

# Version bump 778 -> 779. Idempotent on already-persisted v779 source.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000778"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000779"')
s = s.replace('?: 2098000778', '?: 2098000779')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "778"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "779"')
gradle.write_text(s, encoding='utf-8')

reader = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = reader.read_text(encoding='utf-8')
if 'import kotlinx.coroutines.CancellationException\n' not in s:
    marker = 'import kotlinx.coroutines.Dispatchers\n'
    if marker not in s:
        raise SystemExit('v779 import anchor missing')
    s = s.replace(marker, 'import kotlinx.coroutines.CancellationException\n' + marker, 1)

if 'private var fontChangeRequestId: Long = 0L' not in s:
    marker = '    private var pendingFontRollback: FontRollback? = null\n'
    if marker not in s:
        raise SystemExit('v779 font request field anchor missing')
    s = s.replace(marker, marker + '    private var fontChangeRequestId: Long = 0L\n', 1)

# A dying reader invalidates delayed font requests before any old callback can run.
old_destroy = '        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n'
new_destroy = '        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        fontChangeRunnable = null\n        fontChangeRequestId += 1L\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n'
if new_destroy not in s:
    if old_destroy not in s:
        raise SystemExit('v779 destroy anchor missing')
    s = s.replace(old_destroy, new_destroy, 1)

# Normal coroutine cancellation is lifecycle/control flow, never a reader failure.
old_load_catch = '''            } catch (error: Throwable) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "loadBook:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()}")
                showFatal(error.message ?: "打开书籍失败")
            }
'''
new_load_catch = '''            } catch (cancelled: CancellationException) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "loadBook:cancelled book=$bookId generation=$readerGeneration")
                throw cancelled
            } catch (error: Throwable) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "loadBook:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()}")
                if (!isFinishing && !isDestroyed && ownsReaderSession()) {
                    showFatal(error.message ?: "打开书籍失败")
                }
            }
'''
if new_load_catch not in s:
    if old_load_catch not in s:
        raise SystemExit('v779 loadBook catch anchor missing')
    s = s.replace(old_load_catch, new_load_catch, 1)

old_paginate_catch = '''            } catch (error: Throwable) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset")
                val rollback = pendingFontRollback
                if (rollback != null) {
                    pendingFontRollback = null
                    readerTextSizeSp = rollback.textSizeSp
                    readerBook = rollback.readerBook
                    layoutSettings = rollback.settings
                    currentPageIndex = rollback.pageIndex.coerceIn(0, rollback.readerBook.pages.lastIndex)
                    applyReaderContentPadding()
                    savePreferences()
                    updateSettingsLabels()
                    showActiveReader()
                    Toast.makeText(this@ReaderActivity, "字号分页失败，已恢复原字号和位置", Toast.LENGTH_SHORT).show()
                } else {
                    showContinuousFallback(error.message ?: "分页失败")
                }
            }
'''
new_paginate_catch = '''            } catch (cancelled: CancellationException) {
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:cancelled book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset generation=$readerGeneration")
                throw cancelled
            } catch (error: Throwable) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset")
                if (isFinishing || isDestroyed || !ownsReaderSession()) return@launch
                val rollback = pendingFontRollback
                if (rollback != null) {
                    pendingFontRollback = null
                    readerTextSizeSp = rollback.textSizeSp
                    readerBook = rollback.readerBook
                    layoutSettings = rollback.settings
                    currentPageIndex = rollback.pageIndex.coerceIn(0, rollback.readerBook.pages.lastIndex)
                    lastStableSourceOffset = rollback.sourceOffset.coerceIn(0, rollback.readerBook.text.length)
                    applyReaderContentPadding()
                    savePreferences()
                    updateSettingsLabels()
                    showActiveReader()
                    Toast.makeText(this@ReaderActivity, "字号分页失败，已恢复原字号和位置", Toast.LENGTH_SHORT).show()
                } else {
                    showContinuousFallback(error.message ?: "分页失败")
                }
            }
'''
if new_paginate_catch not in s:
    if old_paginate_catch not in s:
        raise SystemExit('v779 paginate catch anchor missing')
    s = s.replace(old_paginate_catch, new_paginate_catch, 1)

old_font = '''    private fun changeTextSize(delta: Float) {
        stopAutoReading(false)
        val paged = readerBook ?: return
        val currentOffset = currentVisibleSourceOffset()
        val requested = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        if (requested == readerTextSizeSp) return
        fontChangeRunnable?.let(mainHandler::removeCallbacks)
        val oldSize = readerTextSizeSp
        val oldBook = paged
        val oldSettings = layoutSettings
        val oldPage = currentPageIndex
        readerTextSizeSp = requested
        applyReaderContentPadding()
        savePreferences()
        updateSettingsLabels()
        fontChangeRunnable = Runnable {
            pendingFontRollback = FontRollback(oldSize, oldBook, oldSettings, oldPage, currentOffset)
            paginateAndDisplay(currentOffset)
        }.also { mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS) }
    }
'''
new_font = '''    private fun changeTextSize(delta: Float) {
        stopAutoReading(false)
        val paged = readerBook ?: return
        val currentOffset = currentVisibleSourceOffset()
        val requested = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        if (requested == readerTextSizeSp) return

        // Restore the v754 contract: one stable rollback baseline per adjustment burst, a
        // source-character anchor, and only the latest delayed request may repaginate.
        fontChangeRunnable?.let(mainHandler::removeCallbacks)
        fontChangeRunnable = null
        fontChangeRequestId += 1L
        val requestId = fontChangeRequestId
        if (pendingFontRollback == null) {
            pendingFontRollback = FontRollback(
                readerTextSizeSp,
                paged,
                layoutSettings,
                currentPageIndex,
                currentOffset
            )
        }

        // If an earlier font pagination already started, cancel it immediately. Its
        // CancellationException is control flow and is explicitly excluded from failure UI.
        paginationJob?.cancel()
        readerTextSizeSp = requested
        applyReaderContentPadding()
        savePreferences()
        updateSettingsLabels()
        progressLabel.text = "字体分页中…"
        fontChangeRunnable = Runnable {
            fontChangeRunnable = null
            if (requestId != fontChangeRequestId || isFinishing || isDestroyed || !ownsReaderSession()) return@Runnable
            paginateAndDisplay(currentOffset)
        }.also { mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS) }
    }
'''
if new_font not in s:
    if old_font not in s:
        raise SystemExit('v779 changeTextSize anchor missing')
    s = s.replace(old_font, new_font, 1)

# Fallback/fatal UI must never attach to a destroyed or stale reader window.
old_fallback = '    private fun showContinuousFallback(reason: String) {\n        val loaded = document ?: return showFatal(reason)\n'
new_fallback = '    private fun showContinuousFallback(reason: String) {\n        if (isFinishing || isDestroyed || !ownsReaderSession()) return\n        val loaded = document ?: return showFatal(reason)\n'
if new_fallback not in s:
    if old_fallback not in s:
        raise SystemExit('v779 fallback lifecycle anchor missing')
    s = s.replace(old_fallback, new_fallback, 1)

old_fatal = '    private fun showFatal(message: String) {\n        CrashLogStore.recordEvent(this, "showFatal book=$bookId message=${message.replace("\\n", " ").take(500)}")\n'
new_fatal = '    private fun showFatal(message: String) {\n        if (isFinishing || isDestroyed || !ownsReaderSession()) {\n            CrashLogStore.recordEvent(this, "showFatal:suppressed book=$bookId finishing=$isFinishing destroyed=$isDestroyed owner=${ownsReaderSession()}")\n            return\n        }\n        CrashLogStore.recordEvent(this, "showFatal book=$bookId message=${message.replace("\\n", " ").take(500)}")\n'
if new_fatal not in s:
    if old_fatal not in s:
        raise SystemExit('v779 showFatal anchor missing')
    s = s.replace(old_fatal, new_fatal, 1)

required = [
    'import kotlinx.coroutines.CancellationException',
    'private var fontChangeRequestId: Long = 0L',
    'catch (cancelled: CancellationException)',
    'paginate:cancelled',
    'if (pendingFontRollback == null)',
    'paginationJob?.cancel()',
    'val requestId = fontChangeRequestId',
    'requestId != fontChangeRequestId',
    'paginateAndDisplay(currentOffset)',
    'lastStableSourceOffset = rollback.sourceOffset',
    'showFatal:suppressed',
]
missing = [m for m in required if m not in s]
if missing:
    raise SystemExit('v779 reader patch incomplete: ' + repr(missing))
reader.write_text(s, encoding='utf-8')
print('v779 applied: font repagination debounce/anchor/rollback restored; cancellation cannot trigger fatal UI')
