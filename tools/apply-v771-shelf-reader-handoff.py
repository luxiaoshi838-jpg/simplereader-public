#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- old ---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_build_gradle() -> None:
    path = ROOT / "app/build.gradle.kts"
    text = path.read_text(encoding="utf-8")
    text = text.replace('"2098000770"', '"2098000771"')
    text = text.replace('?: 2098000770', '?: 2098000771')
    text = text.replace('?: "770"', '?: "771"')
    path.write_text(text, encoding="utf-8")


def patch_reader() -> None:
    path = ROOT / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"

    replace_once(
        path,
        "import com.simplereader.app.runtime.ReaderRuntimeState\n",
        "import com.simplereader.app.runtime.ReaderRuntimeState\nimport com.simplereader.app.runtime.ShelfCacheHandoff\n",
    )
    replace_once(
        path,
        "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.flow.first\n",
        "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.flow.first\n",
    )
    replace_once(
        path,
        "    private var readerGeneration: Long = 0L\n",
        "    private var readerGeneration: Long = 0L\n    private var shelfCacheClaimBookId: Long = 0L\n",
    )
    replace_once(
        path,
        "        CrashLogStore.recordEvent(this, \"ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset\")\n        releaseReaderMemory()\n",
        "        CrashLogStore.recordEvent(this, \"ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset\")\n        releaseShelfCacheReaderClaim(markCompleted = false)\n        releaseReaderMemory()\n",
    )

    load_marker = "    private fun loadBook() {\n"
    helpers = '''    private suspend fun awaitShelfCacheReaderClaim() {\n        var waitLogged = false\n        while (true) {\n            when (ShelfCacheHandoff.tryClaimForReader(bookId)) {\n                ShelfCacheHandoff.ReaderClaimResult.NOT_NEEDED -> return\n                ShelfCacheHandoff.ReaderClaimResult.ACQUIRED -> {\n                    shelfCacheClaimBookId = bookId\n                    CrashLogStore.recordEvent(this, \"shelf_handoff:reader_claimed book=$bookId\")\n                    return\n                }\n                ShelfCacheHandoff.ReaderClaimResult.WAIT_FOR_WORKER -> {\n                    if (!waitLogged) {\n                        waitLogged = true\n                        CrashLogStore.recordEvent(this, \"shelf_handoff:reader_wait_worker book=$bookId\")\n                    }\n                    progressLabel.text = \"正在完成该书目录…\"\n                    delay(100L)\n                }\n            }\n        }\n    }\n\n    private fun releaseShelfCacheReaderClaim(markCompleted: Boolean): Boolean {\n        val claimedBookId = shelfCacheClaimBookId\n        if (claimedBookId <= 0L) return false\n        val addedToCompleted = if (markCompleted) {\n            ShelfCacheHandoff.markForegroundCompleted(claimedBookId)\n        } else {\n            false\n        }\n        ShelfCacheHandoff.releaseReader(claimedBookId)\n        shelfCacheClaimBookId = 0L\n        CrashLogStore.recordEvent(\n            this,\n            \"shelf_handoff:reader_released book=$claimedBookId completed=$markCompleted added=$addedToCompleted\"\n        )\n        return addedToCompleted\n    }\n\n    private fun loadBook() {\n'''
    replace_once(path, load_marker, helpers)

    replace_once(
        path,
        "                if (selected.format.equals(\"CHM\", ignoreCase = true)) {\n                    error(\"当前版本已停止支持 CHM：请改用 TXT 或 EPUB\")\n                }\n                val loaded = withContext(Dispatchers.IO) { ReaderDocumentLoader.load(this@ReaderActivity, selected) }\n",
        "                if (selected.format.equals(\"CHM\", ignoreCase = true)) {\n                    error(\"当前版本已停止支持 CHM：请改用 TXT 或 EPUB\")\n                }\n                awaitShelfCacheReaderClaim()\n                val loaded = withContext(Dispatchers.IO) { ReaderDocumentLoader.load(this@ReaderActivity, selected) }\n",
    )
    replace_once(
        path,
        "            } catch (error: Throwable) {\n                CrashLogStore.recordEvent(this@ReaderActivity, \"loadBook:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()}\")\n                showFatal(error.message ?: \"打开书籍失败\")\n",
        "            } catch (error: Throwable) {\n                releaseShelfCacheReaderClaim(markCompleted = false)\n                CrashLogStore.recordEvent(this@ReaderActivity, \"loadBook:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()}\")\n                showFatal(error.message ?: \"打开书籍失败\")\n",
    )

    old_success = '''                CrashLogStore.recordMemorySnapshot(\n                    this@ReaderActivity,\n                    \"paginate_success\",\n                    memoryDiagnosticDetails() + \" cached=${cached != null}\"\n                )\n                paginationInProgress = false\n                showActiveReader()\n                pendingTurnMode?.let { queued -> pendingTurnMode = null; setTurnMode(queued) }\n                lifecycleScope.launch(Dispatchers.IO) {\n                    runCatching {\n                        if (cached == null) {\n                            PageCacheStore.savePages(this@ReaderActivity, identity, paged)\n                        }\n                        PageCacheStore.markRecognitionComplete(\n                            context = this@ReaderActivity,\n                            bookId = selectedBook.id,\n                            fileName = selectedBook.fileName,\n                            fileSize = selectedBook.fileSize,\n                            chapterCount = paged.chapters.count { it.catalogVisible },\n                            pageCount = paged.pages.size\n                        )\n                    }\n                }\n'''
    new_success = '''                CrashLogStore.recordMemorySnapshot(\n                    this@ReaderActivity,\n                    \"paginate_success\",\n                    memoryDiagnosticDetails() + \" cached=${cached != null}\"\n                )\n                val shelfHandoffOwned = shelfCacheClaimBookId == selectedBook.id\n                if (shelfHandoffOwned) {\n                    withContext(Dispatchers.IO) {\n                        if (cached == null) {\n                            PageCacheStore.savePages(this@ReaderActivity, identity, paged)\n                        }\n                        PageCacheStore.markRecognitionComplete(\n                            context = this@ReaderActivity,\n                            bookId = selectedBook.id,\n                            fileName = selectedBook.fileName,\n                            fileSize = selectedBook.fileSize,\n                            chapterCount = paged.chapters.count { it.catalogVisible },\n                            pageCount = paged.pages.size\n                        )\n                    }\n                    val addedToShelfCompleted = releaseShelfCacheReaderClaim(markCompleted = true)\n                    CrashLogStore.recordEvent(\n                        this@ReaderActivity,\n                        \"shelf_handoff:foreground_complete book=${selectedBook.id} added=$addedToShelfCompleted\"\n                    )\n                }\n                paginationInProgress = false\n                showActiveReader()\n                pendingTurnMode?.let { queued -> pendingTurnMode = null; setTurnMode(queued) }\n                if (!shelfHandoffOwned) {\n                    lifecycleScope.launch(Dispatchers.IO) {\n                        runCatching {\n                            if (cached == null) {\n                                PageCacheStore.savePages(this@ReaderActivity, identity, paged)\n                            }\n                            PageCacheStore.markRecognitionComplete(\n                                context = this@ReaderActivity,\n                                bookId = selectedBook.id,\n                                fileName = selectedBook.fileName,\n                                fileSize = selectedBook.fileSize,\n                                chapterCount = paged.chapters.count { it.catalogVisible },\n                                pageCount = paged.pages.size\n                            )\n                        }\n                    }\n                }\n'''
    replace_once(path, old_success, new_success)

    replace_once(
        path,
        "            } catch (error: Throwable) {\n                CrashLogStore.recordEvent(this@ReaderActivity, \"paginate:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset\")\n",
        "            } catch (error: Throwable) {\n                releaseShelfCacheReaderClaim(markCompleted = false)\n                CrashLogStore.recordEvent(this@ReaderActivity, \"paginate:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset\")\n",
    )


def patch_worker() -> None:
    path = ROOT / "app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt"
    replace_once(
        path,
        "import com.simplereader.app.runtime.ReaderRuntimeState\n",
        "import com.simplereader.app.runtime.ReaderRuntimeState\nimport com.simplereader.app.runtime.ShelfCacheHandoff\n",
    )
    replace_once(
        path,
        "        val operationTitle = modeTitle(mode)\n        val workId = id.toString()\n        val database = SimpleReaderDatabase.getDatabase(applicationContext)\n",
        "        val operationTitle = modeTitle(mode)\n        val workId = id.toString()\n        ShelfCacheHandoff.beginWork(workId)\n        val database = SimpleReaderDatabase.getDatabase(applicationContext)\n",
    )
    replace_once(
        path,
        "            val currentFileSize = currentSource?.length()?.takeIf { it >= 0L } ?: book.fileSize\n\n            // Race-safe second check only: a target may have been generated in the foreground\n",
        "            val currentFileSize = currentSource?.length()?.takeIf { it >= 0L } ?: book.fileSize\n\n            awaitWorkerBookClaim(book.id)\n\n            val foregroundCompleted = ShelfCacheHandoff.consumeForegroundCompleted(book.id)\n            if (foregroundCompleted) {\n                val reusableFromForeground = hasReusableCurrentCache(\n                    bookId = book.id,\n                    filePath = book.filePath,\n                    fileName = currentFileName,\n                    fileSize = currentFileSize,\n                    loadDocument = {\n                        ReaderDocumentLoader.load(\n                            context = applicationContext,\n                            book = book,\n                            forceCatalogRefresh = false\n                        )\n                    }\n                )\n                if (reusableFromForeground) {\n                    ShelfCacheHandoff.releaseWorker(book.id)\n                    completed += 1\n                    checkpoint = checkpoint.copy(\n                        nextIndex = index + 1,\n                        completed = completed,\n                        skipped = skipped,\n                        failed = failed\n                    )\n                    withContext(Dispatchers.IO) {\n                        ShelfCacheCheckpointStore.save(applicationContext, workId, checkpoint)\n                    }\n                    publishProgress(\n                        current = displayedIndex,\n                        total = total,\n                        title = book.title,\n                        completed = completed,\n                        skipped = skipped,\n                        failed = failed\n                    )\n                    OperationLogStore.updateShelfCache(\n                        context = applicationContext,\n                        workId = workId,\n                        modeTitle = operationTitle,\n                        state = \"运行中\",\n                        currentIndex = displayedIndex,\n                        total = total,\n                        currentTitle = \"${book.title}（阅读器已完成）\",\n                        completed = completed,\n                        failed = failed,\n                        skipped = skipped\n                    )\n                    continue\n                }\n            }\n\n            // Race-safe second check only: a target may have been generated in the foreground\n",
    )
    replace_once(
        path,
        "            if (alreadyReusable) {\n                skipped += 1\n",
        "            if (alreadyReusable) {\n                ShelfCacheHandoff.releaseWorker(book.id)\n                skipped += 1\n",
    )
    replace_once(
        path,
        "                awaitForegroundReaderIdle()\n                val paged = withContext(Dispatchers.Default) {\n",
        "                val paged = withContext(Dispatchers.Default) {\n",
    )
    replace_once(
        path,
        "            if (result.isSuccess) completed += 1 else failed += 1\n",
        "            ShelfCacheHandoff.releaseWorker(book.id)\n            if (result.isSuccess) completed += 1 else failed += 1\n",
    )
    replace_once(
        path,
        "        // Keep the completed checkpoint. If Android recreates this WorkRequest before WorkManager\n        // commits SUCCEEDED, nextIndex == total makes the recreated worker finish immediately rather\n        // than starting the whole shelf again. A later user action has a different workId.\n        return Result.success(output)\n    }\n\n    private suspend fun awaitForegroundReaderIdle() {\n",
        "        // Keep the completed checkpoint. If Android recreates this WorkRequest before WorkManager\n        // commits SUCCEEDED, nextIndex == total makes the recreated worker finish immediately rather\n        // than starting the whole shelf again. A later user action has a different workId.\n        ShelfCacheHandoff.endWork(workId)\n        return Result.success(output)\n    }\n\n    override fun onStopped() {\n        ShelfCacheHandoff.endWork(id.toString())\n        super.onStopped()\n    }\n\n    private suspend fun awaitForegroundReaderIdle() {\n",
    )
    replace_once(
        path,
        "    private suspend fun hasReusableCurrentCache(\n",
        "    private suspend fun awaitWorkerBookClaim(bookId: Long) {\n        while (!ShelfCacheHandoff.tryClaimForWorker(bookId)) {\n            coroutineContext.ensureActive()\n            delay(100L)\n        }\n    }\n\n    private suspend fun hasReusableCurrentCache(\n",
    )


def main() -> None:
    patch_build_gradle()
    patch_reader()
    patch_worker()
    print("v771 shelf/reader handoff patch applied")


if __name__ == "__main__":
    main()
