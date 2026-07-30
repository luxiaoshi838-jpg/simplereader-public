# 简阅 v577 真实纸纤维纹理封面日志

日期：2026-07-30  
分支：`agent/v576-cover-texture`  
草稿 PR：`#26`

## 用户要求

- 封面蓝色保持 v575/v576 不变。
- 使用用户最终选定的纸纤维纹理图，不再使用随机点线模拟纹理。
- TXT、EPUB、CHM、PDF、文件扩展名和其它格式角标均不得出现。
- 只修改普通默认封面；EPUB 自带封面、阅读、导入、分组和数据均不改动。

## 实现

1. 将最终选定纹理转换为 128×128 中性灰度纹理遮罩，只保留纤维明暗结构，不携带原图颜色。
2. 构建时从 `paper_texture_v577.png.b64` 生成 `drawable-nodpi/paper_texture_v577.png`。
3. `PaperCoverDrawable` 继续使用顶部 `RGB(74,126,172)`、底部 `RGB(47,94,136)`，与上一版一致。
4. 纹理使用 `PorterDuff.Mode.OVERLAY` 叠加，只增加纸纤维凹凸感，不改变蓝色基调。
5. 每本书仅改变纹理起始相位，不改变颜色，也不绘制格式角标。
6. CI 构建前应用 `MainActivity_v577_image_texture.patch`，将普通默认封面接入真实纹理资源。

## 版本

- package：`com.simplereader.app`
- versionCode：`2098000577`
- versionName：`2026.07.30.paper-image-texture.577`
- 构建约束：Gradle release 单步骤 5 分钟硬超时。
- 签名规则：公开库不保存密钥；构建产物下载后使用用户原简阅证书在本地签名。

## 验收标准

- 封面蓝色参数与上一版相同。
- 纹理来源为用户最终选定图片的纤维结构。
- 普通封面不显示 TXT、EPUB、CHM、PDF 或扩展名。
- EPUB 自带图片封面显示逻辑保持不变。
