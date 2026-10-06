# 中文 PDF 的字体与主题

本目录的东西只服务于 `build/pom.xml` 里的中文 PDF 构建（`README-CN.adoc` → `README-CN.pdf`）。

| 文件 | 用途 |
|---|---|
| `README-CN-theme.yml` | 中文 PDF 主题：在 `default-with-font-fallbacks` 基础上把 Noto Sans SC 放进回退字体链 |
| `NotoSansSC-Regular.ttf` | 静态（非可变）TrueType 字体，覆盖全部简体汉字 |
| `LICENSE-Noto-CJK.txt` | 字体许可证（SIL Open Font License 1.1） |

## 为什么必须自带字体

asciidoctor-pdf 自带的回退字体 M+ 1p 是**日文字体**。实测 `README-CN.adoc` 的 767 个不同码位中有
224 个简体字（如「说、这、简、单、为、么」）它没有字形，直接渲染会缺字。

## 为什么必须是 TrueType(glyf) 而不是 .otf(CFF)

Prawn（asciidoctor-pdf 的排版引擎）把字体一律嵌成 `/FontFile2` + `/Subtype /TrueType` 的简单字体。
喂给它 CFF/OTTO 轮廓的 `.otf`（例如 noto-cjk 仓库的 `Sans/SubsetOTF/SC/NotoSansSC-Regular.otf`）
会得到结构错误的 PDF：汉字整片渲染不出来（PDFBox 会直接回退到 ArialMT）。
所以这里用的是 glyf 轮廓的 TTF。

## `NotoSansSC-Regular.ttf` 是怎么来的

上游可变字体（wght 100–900，默认实例是 Thin）来自 Google Fonts 的 Noto Sans SC：

```
https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf
sha256 a3041811a78c361b...   (17,772,300 bytes)
```

用 fontTools 把它钉到 Regular(400) 并去掉可变表（同时更新 name 表，避免仍叫 "Thin"）：

```python
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

font = TTFont("NotoSansSC[wght].ttf")
instancer.instantiateVariableFont(font, {"wght": 400}, inplace=True, updateFontNames=True)
font.save("NotoSansSC-Regular.ttf")
```

产物：`NotoSansSC-Regular.ttf`，10,595,892 bytes，
sha256 `429f4e784d072f2eb8dcda498e7a8860c2c7db552c3f1285507dd0848da5c764`，
sfnt 版本 `0x00010000`（TrueType/glyf）、无 `fvar`/`CFF ` 表，name 表为 "Noto Sans SC"/"Regular"。

## 为什么要把中文正文改成左对齐（`base.text_align: left`）

`default-theme.yml` 里 `base: text_align: justify`（两端对齐）。拉丁文一行里有大量空格，两端对齐
是把富余量摊到这些空格上，观感正常；但中文行内几乎没有空格，富余量只能全部挤进拉丁词（`DuckDB`、
`Java`、`TCP/IP`）两侧那几个空格里，于是排成

```
网络化的        DuckDB        ——        突破        DuckDB        仅限本地的限制
```

这种大洞。所以中文主题里把正文对齐改成左对齐（ragged right），行内恢复成正常单空格：

```yaml
base:
  text_align: left
```

英文 PDF（`README.adoc`）继续用默认主题的 justify，不受影响——`build/pom.xml` 里两个
asciidoctor 执行各自指定主题，EN 那份没有 `<pdf-theme>`。

需要换字体（例如换更小的子集、或补一份 Bold）时，重复上面这一步，然后把新文件放进本目录并改
`README-CN-theme.yml` 里的文件名即可。
