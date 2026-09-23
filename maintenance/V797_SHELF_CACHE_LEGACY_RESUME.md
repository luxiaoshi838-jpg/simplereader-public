# V797 旧版全书架缓存任务自动接管修复

## 实机反馈

升级到 V796 后，原本显示 0/0 的旧任务可以恢复出真实 checkpoint，例如：

- 86 / 2725
- 成功 86
- 当前：《等待继续》

但任务本身仍没有继续执行。

## 根因

V796 已经取消新的 Result.retry()，但设备中 V795/V786 已经产生的旧 WorkRequest 仍可能保留在 WorkManager 的 retry/backoff 队列。

V796 继续使用 ExistingWorkPolicy.KEEP。只要旧唯一任务仍处于 ENQUEUED/BLOCKED，新 WorkRequest 就不会替换它。因此：

- checkpoint 是正常的；
- UI 能恢复 86/2725；
- 但 Worker 不一定重新进入 RUNNING。

“等待继续”只是 V796 的状态文字，不是一个隐藏按钮。

## V797 修复

1. 仅把以下任务识别为旧版停滞任务：
   - state == ENQUEUED 或 BLOCKED；
   - runAttemptCount > 0。
2. 普通首次排队任务 runAttemptCount == 0，不做替换。
3. 发现旧停滞任务时：
   - 读取旧 workId checkpoint；
   - 先把 checkpoint 完整复制到新 WorkRequest id；
   - 再用 ExistingWorkPolicy.REPLACE 替换旧唯一任务；
   - 新 Worker 从旧 nextIndex / completed / failed / skipped 继续。
4. 进入首页时 ShelfCacheUiController 自动触发恢复，不需要按钮。
5. 用户再次点击“全书架目录缓存”时也会先识别并接管旧 retry 任务。
6. 恢复期间显示“正在自动恢复”，不再显示“等待继续”。
7. Rule 117 不变。
8. V796 的连续运行修复保留，ShelfCacheWorker 内仍禁止 Result.retry()。

## 版本

- versionName: 797
- versionCode: 2098000797
- branch: source-v797
