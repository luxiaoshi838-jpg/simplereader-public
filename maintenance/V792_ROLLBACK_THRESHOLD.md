# V792 回撤触发阈值调整

## 用户反馈
V791 的“跨越超过 1 页即提示回撤”过于敏感，正常快速阅读也容易出现回撤浮层。

## 调整
- 回撤只在一次完整移动跨越 **超过 20 页** 时触发。
- 跨越 20 页及以内不提示。
- 手势大移动与目录/搜索/章节等显式跳转使用同一阈值。
- 3 秒自动消失、锚定阅读下栏上方、保存 sourceOffset + viewport offset、触摸即刹车、5 秒 stuck-settling 保险均保持 V791 行为不变。

## 实现
统一常量：`VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20`。
判断口径：`abs(targetPage - originPage) > 20` 才显示“↩︎ 回撤”。

## 验证
- 新增 `ReaderV792RollbackThresholdContractTest`。
- 迁移 V791 历史契约中已被 V792 明确取代的阈值断言。
- 运行 Debug/Release 定向测试、继承全量 baseline、Debug/Release 构建、APK 版本校验。
