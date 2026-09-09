#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
STAGE1 = ROOT / "tools/apply-v771-shelf-reader-handoff.py"
WORKER = ROOT / "app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt"


def replace_once(old: str, new: str) -> None:
    text = WORKER.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{WORKER}: expected exactly one match, found {count}\n--- old ---\n{old}")
    WORKER.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    # Stage 1 applies the reader/worker handoff and version bump.
    subprocess.run([sys.executable, str(STAGE1)], cwd=ROOT, check=True)

    # CoroutineWorker.onStopped() is final in the WorkManager version used by this project.
    # Put handoff lifetime around doWork instead. Kotlin finally runs for success, failure and
    # coroutine cancellation, so a cancelled/failed shelf job cannot leave a stale active-work flag.
    replace_once(
        "    override suspend fun doWork(): Result {\n        createNotificationChannel()\n",
        "    override suspend fun doWork(): Result {\n"
        "        ShelfCacheHandoff.beginWork(id.toString())\n"
        "        return try {\n"
        "            doWorkWithShelfHandoff()\n"
        "        } finally {\n"
        "            ShelfCacheHandoff.endWork(id.toString())\n"
        "        }\n"
        "    }\n\n"
        "    private suspend fun doWorkWithShelfHandoff(): Result {\n"
        "        createNotificationChannel()\n",
    )
    replace_once(
        "        val operationTitle = modeTitle(mode)\n"
        "        val workId = id.toString()\n"
        "        ShelfCacheHandoff.beginWork(workId)\n"
        "        val database = SimpleReaderDatabase.getDatabase(applicationContext)\n",
        "        val operationTitle = modeTitle(mode)\n"
        "        val workId = id.toString()\n"
        "        val database = SimpleReaderDatabase.getDatabase(applicationContext)\n",
    )
    replace_once(
        "        ShelfCacheHandoff.endWork(workId)\n"
        "        return Result.success(output)\n"
        "    }\n\n"
        "    override fun onStopped() {\n"
        "        ShelfCacheHandoff.endWork(id.toString())\n"
        "        super.onStopped()\n"
        "    }\n",
        "        return Result.success(output)\n"
        "    }\n",
    )

    print("v771 shelf/reader handoff v2 patch applied with cancellation-safe cleanup")


if __name__ == "__main__":
    main()
