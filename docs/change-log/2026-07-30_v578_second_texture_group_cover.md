# 简阅 v578：第二版纸纹理与分组页封面统一

## 用户反馈

- v577 在书架缩略封面上纹理过重。
- 进入分组后的独立书籍页面仍使用旧渐变封面，没有纸质纹理。
- 用户指定改用此前生成的第二版纸纤维纹理。

## 本次修改

1. 保持封面原有蓝色不变：
   - 顶部：`RGB(74,126,172)`
   - 底部：`RGB(47,94,136)`
2. 将纹理源替换为用户指定的第二版纸纤维图。
3. 第二版纹理转为中性灰度遮罩，只保留纹理明暗，不改变蓝色。
4. 纹理叠加透明度由 v577 的 `112/255` 降为 `72/255`，避免书架小封面显得过重。
5. `MainActivity` 书架封面、分组四宫格预览继续统一使用 `PaperCoverDrawable`。
6. `GroupBooksActivity` 分组内书籍封面由旧渐变改为同一 `PaperCoverDrawable`。
7. 普通封面继续禁止显示 `TXT`、`EPUB`、`CHM`、`PDF`、扩展名或格式角标。
8. EPUB 自带图片封面不受影响。

## 版本

- package：`com.simplereader.app`
- versionCode：`2098000578`
- versionName：`2026.07.30.second-paper-texture.578`

## 构建规则

- CI 构建前执行并校验：`patches/v578/ShelfAndGroup_v578_second_texture.patch`
- release 构建单步骤设置 5 分钟硬超时。
- 正式签名密钥与密码不上传公开库。
- 构建产物下载后使用原简阅证书在本地签名。

## 待补充

签名 APK 生成后补充 APK SHA-256、证书指纹、v2/v3 签名与 ZIP 对齐结果。
