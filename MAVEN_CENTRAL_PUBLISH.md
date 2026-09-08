# Maven Central 发布手册（Diagram）

> 本手册基于本仓库（`io.github.zusrsoft` 命名空间）`1.0.4` 版本发布实战整理，包含完整流程、踩坑实录与注意事项。
> 发布目标：Sonatype Central Portal —— https://central.sonatype.com

---

## 目录

1. [发布概览](#1-发布概览)
2. [前置条件（一次性准备）](#2-前置条件一次性准备)
3. [凭证配置（踩坑重灾区）](#3-凭证配置踩坑重灾区)
4. [项目发布配置说明](#4-项目发布配置说明)
5. [本地发布操作步骤](#5-本地发布操作步骤)
6. [常见校验错误对照表（踩坑实录）](#6-常见校验错误对照表踩坑实录)
7. [发布结果确认](#7-发布结果确认)
8. [CI 自动发布](#8-ci-自动发布)
9. [安全注意事项](#9-安全注意事项)
10. [附录：GPG 命令与实用脚本速查](#10附录gpg-命令与实用脚本速查)

---

## 1. 发布概览

### 1.1 发布产物

一次完整发布会上传 4 个模块的全平台构件（`diagram-bench` / `composeApp` / `androidApp` 不发布）：

| 模块 | 坐标 |
| --- | --- |
| diagram-core | `io.github.zusrsoft:diagram-core` |
| diagram-layout | `io.github.zusrsoft:diagram-layout` |
| diagram-parser | `io.github.zusrsoft:diagram-parser` |
| diagram-render | `io.github.zusrsoft:diagram-render` |

每个模块包含以下 publication（vanniktech 插件自动生成，共 4 × 7 = 28 个组件）：

- `jvm`(jar) / `android`(aar) / `js`(klib) / `wasmJs`(klib) / `iosArm64`(klib) / `iosSimulatorArm64`(klib)
- `kotlinMultiplatform`（`.module` 元数据 + allMetadata jar）

每个 publication 自带：主构件、sources.jar、javadoc.jar（KMP 为空壳）、.pom、.module、`.asc` 签名。

> 模块间依赖（如 `diagram-render` POM 中的 `diagram-core`）由 `projects.*` 引用自动生成，
> 坐标跟随各模块 `coordinates()` 配置，发布前无须手工维护。

### 1.2 发布链路

```
gradlew publishAllPublicationsToMavenCentralRepository
   → 编译全平台(约 15 分钟) → 签名(GPG) → 上传(Central Portal API)
   → Sonatype 校验(签名可验证/命名空间/POM 规范/javadoc/sources)
   → publishToMavenCentral(true) 自动发布 → PUBLISHED
   → 约 10~30 分钟内同步到 repo1.maven.org
```

### 1.3 两种发布方式

| 方式 | 适用场景 |
| --- | --- |
| 本地命令行发布 | 首次发布、调试签名问题、紧急发版 |
| GitHub Actions CI | 例行发版（Release 触发，见第 8 节） |

---

## 2. 前置条件（一次性准备）

### 2.1 注册 Central Portal 账号并验证命名空间

1. 打开 https://central.sonatype.com → 用 GitHub 账号（**zusrsoft**）登录
2. 进入 **Namespaces** 页面，确认命名空间 `io.github.zusrsoft` 状态为 **Verified**
   - GitHub 命名空间验证方式：Portal 会要求在 `github.com/zusrsoft` 账号下创建指定名称的临时空仓库，按页面提示操作后自动通过
3. ⚠️ 命名空间必须与发布坐标 groupId 完全一致，否则上传直接被拒（见 6.5，本仓库真实踩过）

### 2.2 生成访问令牌（User Token）

1. Portal 右上角头像 → **Account** → **Generate User Token**
2. 得到一对"用户名 + 密码"（均为随机字符串）
3. 这是发布 API 的认证凭证，**等同于账号密码，切勿提交到仓库**

### 2.3 准备 GPG 密钥

本仓库当前使用的密钥（公开信息）：

- uid：`zusr <zusrsoft@163.com>`
- 短 ID：`88DE87D5`
- 指纹：`9FD4A322C71073ED27A10A0636C9087F88DE87D5`
- 公钥已上传：keyserver.ubuntu.com、keys.openpgp.org

若需新密钥：

```powershell
# 生成密钥（记住自己设置的密码 = passphrase）
gpg --gen-key

# 查看密钥列表（8 位短 ID 在 sec 行）
gpg --list-secret-keys --keyid-format short

# 上传公钥到 keyserver（Sonatype 支持的服务器）
gpg --keyserver keyserver.ubuntu.com --send-keys 88DE87D5
gpg --keyserver keys.openpgp.org --send-keys 88DE87D5

# 验证公钥已可查询
gpg --keyserver keyserver.ubuntu.com --recv-keys 88DE87D5

# 导出私钥（配置凭证用，见第 3 节）
gpg --armor --export-secret-keys 88DE87D5
```

注意事项：

- ⚠️ **keys.openpgp.org 需要邮箱验证**：上传后会向密钥 uid 邮箱发送验证链接，点击确认后公钥才对外提供
- ⚠️ 公钥上传后 Sonatype 有**查询缓存（约 10~60 分钟）**，刚上传就发布会报"找不到公钥"（见 6.4）
- 忘记 GPG 密码无法找回，只能重新生成密钥并重新上传公钥；旧密钥已发布的版本不受影响

---

## 3. 凭证配置（踩坑重灾区）

### 3.1 ⚠️ 坑一：确认 GRADLE_USER_HOME 的真实位置

**Gradle 只读 `GRADLE_USER_HOME` 指向目录下的 `gradle.properties`，不一定是 `~/.gradle`！**

```powershell
# 先查环境变量
echo $env:GRADLE_USER_HOME
# 本机实测：D:\Dev-tools\gradle-repository
# → 凭证必须写入 D:\Dev-tools\gradle-repository\gradle.properties
#   写到 C:\Users\<user>\.gradle\gradle.properties 是无效的！
```

**症状**：凭证明明配了，发布却报 `Cannot perform signing task ... because it has no configured signatory`。

### 3.2 五项凭证属性

写入 `GRADLE_USER_HOME\gradle.properties`：

```properties
mavenCentralUsername=令牌用户名
mavenCentralPassword=令牌密码
signingInMemoryKeyId=88DE87D5
signingInMemoryKeyPassword=GPG密钥密码
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n\<换行内容见3.3\>
```

### 3.3 ⚠️ 坑二：私钥必须是"单行 \n 转义"格式

properties 文件**不支持多行值**。把 `gpg --armor --export-secret-keys` 的输出原样粘贴进去无效——Gradle 只会读到第一行。

正确格式（一行写完，换行用字面 `\n` 表示，**BEGIN 行之后的空行也必须保留**）：

```properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nlQVGBZ+XXXXX...\n...\n=XXXXX\n-----END PGP PRIVATE KEY BLOCK-----
```

要点：

- 每个换行处写 `\n`（反斜杠 + 字母 n，不是真实回车）
- `-----BEGIN-----` 之后有一个**必需的空行**（armor 格式要求），丢掉会报 `Could not read PGP secret key`
- 结尾有 `=XXXXX` 校验行，随后是 `-----END-----`

**PowerShell 一键转换**（从 gpg 直接生成合法单行值）：

```powershell
$single = (gpg --armor --export-secret-keys 88DE87D5 | Where-Object { $_ -ne $null }) -join '\n'
# $single 即为 signingInMemoryKey= 后面的完整值
```

### 3.4 ⚠️ 坑三：keyId 必须是 8 位短 ID

Gradle 的 `PgpKeyId` **只接受 8 位十六进制短 ID**（如 `88DE87D5`）：

| keyId 写法 | 结果 |
| --- | --- |
| `88DE87D5`（8 位短 ID） | ✅ 正常 |
| `36C9087F88DE87D5`（16 位长 ID） | ❌ `The key ID must be in a valid form (eg 00B5050F)` |
| `9FD4A322...88DE87D5`（40 位指纹） | ❌ `Could not read PGP secret key` |

短 ID = 长密钥 ID 的**最后 8 位** = 指纹的**最后 8 位**。

### 3.5 验证凭证是否被 Gradle 读到

```powershell
# 只检查属性是否存在，不回显内容
$guh = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { "$env:USERPROFILE\.gradle" }
'mavenCentralUsername','mavenCentralPassword','signingInMemoryKeyId',
'signingInMemoryKeyPassword','signingInMemoryKey' | ForEach-Object {
    $hit = Select-String -LiteralPath "$guh\gradle.properties" -Pattern "^$($_)\s*="
    "PROP $_ -> " + $(if ($hit) { "OK" } else { "MISSING" })
}
```

五项全部 OK 才能继续。若刚修正过凭证仍报无 signatory，先 `.\gradlew.bat --stop` 停掉守护进程再试。

### 3.6 环境变量方式（CI / 临时覆盖）

```properties
ORG_GRADLE_PROJECT_mavenCentralUsername=...
ORG_GRADLE_PROJECT_mavenCentralPassword=...
ORG_GRADLE_PROJECT_signingInMemoryKeyId=88DE87D5
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...
ORG_GRADLE_PROJECT_signingInMemoryKey=多行私钥原文即可（env 不受 properties 单行限制）
```

---

## 4. 项目发布配置说明

以下配置已就绪，日常发版无需改动：

### 4.1 发布插件

- 根 `build.gradle.kts`：`alias(libs.plugins.mavenPublish) apply false`
- `gradle/libs.versions.toml`：`mavenPublish = { id = "com.vanniktech.maven.publish", version = "0.36.0" }`
- 4 个发布模块各自 `alias(libs.plugins.mavenPublish)`

### 4.2 mavenPublishing 配置（4 个模块相同模式）

```kotlin
mavenPublishing {
    publishToMavenCentral(true)   // true = 校验通过后自动发布，无需手动点 Release
    if (!project.hasProperty("signing.skip")) {
        signAllPublications()     // 使用 signingInMemory* 属性签名
    }
    coordinates("io.github.zusrsoft", "diagram-core", rootProject.property("VERSION").toString())
    pom { /* name/description/url/license/developer/scm 齐全，满足 Central 校验 */ }
}
```

POM 中 url/scm/developer 统一指向 `https://github.com/zusrsoft/diagram`（与 git remote 一致）。

### 4.3 版本号

- 版本唯一来源：`gradle.properties` 的 `VERSION`
- ⚠️ Central **不允许覆盖发布**：已发布的版本号永久占用；**校验失败的部署不占用版本号，可直接重试**；但发布流程进行中（PUBLISHING）时并行上传同坐标会被直接拒绝（见 6.7）

### 4.4 artifactId 命名规则（无 sdk36 后缀）

vanniktech 插件把各平台 publication 规范为标准后缀：

```
<模块>              kotlinMultiplatform（.module 元数据）
<模块>-jvm          jar
<模块>-android      aar
<模块>-js           klib
<模块>-wasm-js      klib
<模块>-iosarm64     klib
<模块>-iossimulatorarm64  klib
```

**不存在也不允许出现 `<模块>-android-sdk36` 之类的 compileSdk 后缀**。AGP 9 KMP 插件（`com.android.kotlin.multiplatform.library`）在某些裸 `maven-publish` 配置下会按 compileSdk 给 android publication 命名（如 sdk36），本项目由 vanniktech 插件接管命名，已实测验证无此后缀。若升级插件后存疑，用 10.2 节探针脚本列出全部 publication 核对 artifactId。

### 4.5 命名空间迁移记录（重要历史）

- 2026-09-06 之前：本仓库曾以 `io.github.huarangmeng` 坐标发布过 diagram 1.0.4（旧命名空间下仍存在，已废弃不再维护）
- 2026-09-06 起：全面切换到 `io.github.zusrsoft`，并重新发布了 1.0.4
- ⚠️ 两个命名空间的同版本号互不兼容，老用户需迁移坐标；新发布**只允许** `io.github.zusrsoft`

---

## 5. 本地发布操作步骤

### 第 1 步：确认版本号可用

到 https://central.sonatype.com/search?q=io.github.zusrsoft 检查目标版本号是否已发布过。已发布的版本永久不可覆盖。

### 第 2 步：设置版本号

修改根目录 `gradle.properties` 的 `VERSION`，提交推送（CI 发版时 tag 需与该版本一致）。

### 第 3 步（可选但推荐）：本地预演

```powershell
.\gradlew.bat publishToMavenLocal --no-configuration-cache
```

产物落到 `%GRADLE_USER_HOME%\m2`，可提前发现签名/POM 问题。

### 第 4 步：正式发布

```powershell
.\gradlew.bat publishAllPublicationsToMavenCentralRepository --no-configuration-cache
```

- 全平台编译约 15 分钟（1.0.4 实测 15m45s）
- 日志出现 `Validating deployment <uuid>...` 表示上传完成、进入 Sonatype 校验
- 自动发布模式下：校验通过 → 直接 `BUILD SUCCESSFUL`；校验失败 → 构建失败并打印全部错误明细
- ⚠️ 发布期间（直至 PUBLISHED）**不要在别处（网页/另一终端/CI）重复上传同版本**，会被 Central 拒绝（见 6.7）

### 第 5 步：确认结果

见第 7 节。Portal 显示 PUBLISHED 即成功；repo1 可下载最迟约 30 分钟。

---

## 6. 常见校验错误对照表（踩坑实录）

按排查顺序排列；6.5 / 6.6 / 6.7 / 6.8 在本仓库 1.0.4 发布实战中真实出现。

### 6.1 `Cannot perform signing task ... because it has no configured signatory`

**原因**：Gradle 没读到签名属性。

排查顺序：

1. 凭证写错文件位置（`~/.gradle` vs 真实 `GRADLE_USER_HOME`）——最常见，见 3.1
2. 属性名拼写错误（大小写敏感）
3. Gradle 守护进程缓存了旧属性文件（`.\gradlew.bat --stop` 后重试）

### 6.2 `Could not read PGP secret key`

**原因**：私钥内容或 keyId 无法解析。

排查顺序：

1. keyId 是 40 位指纹或 16 位长 ID → 改为 8 位短 ID（见 3.4）
2. 私钥单行转义时丢了 BEGIN 后的空行 → 补回（见 3.3）
3. 私钥内容残缺（长度异常、头尾标记不完整）

### 6.3 `The key ID must be in a valid form (eg 00B5050F), given value: xxxxxxxx`

**原因**：keyId 给了 16 位长 ID。**必须 8 位短 ID**。加 `--stacktrace` 能在堆栈里看到真实原因，否则只显示笼统的 6.2 错误。

> 技巧：遇到签名失败，先跑 `.\gradlew.bat <模块>:signJvmPublication --stacktrace` 单独定位，比整包发布快得多。

### 6.4 `Could not find a public key by the key fingerprint`

**原因**：签名用的 GPG **公钥**没上传到 keyserver，或 Sonatype 缓存未刷新。

处理：

1. 确认公钥已上传：`https://keyserver.ubuntu.com/pks/lookup?search=0x<指纹>&op=vindex&fingerprint=on`
2. keys.openpgp.org 的上传需完成**邮箱验证**才生效（见 2.3）
3. 以上都确认后就是 Sonatype 缓存延迟——**等 10~60 分钟重试即可**，期间重试只会重复失败

### 6.5 命名空间被拒（namespace not verified / 403）

**原因**：groupId 与 Portal 已验证命名空间不一致。**本仓库真实发生**：

- 曾用坐标 `io.github.huarangmeng`（旧）vs Portal 命名空间 `io.github.zusrsoft`
- 解决：全面切换坐标——4 个模块 `build.gradle.kts` 的 `coordinates()` + POM url/developer/scm + README/README_zh 依赖示例与 badge
- ⚠️ 切换命名空间对老用户是破坏性变更，需在 README 中提示迁移
- 检查方法：`rg -n "huarangmeng" .` 必须零匹配

### 6.6 版本已存在

同版本号发布第二次会被拒（`... already exists`）。校验失败的部署（FAILED 状态）**不占用**版本号，修复后可直接重试。

### 6.7 并行重复上传被拒（1.0.4 实战）

**错误**：`Component with coordinate 'io.github.zusrsoft:xxx:1.0.4' is currently being published in another deployment (<uuid>)`，deployment 状态 FAILED。

**原因**：一次发布处于 VALIDATING/PUBLISHING 期间，又从另一处（网页手动上传 / 另一终端 / CI）上传了相同坐标。

**处理**：

1. 不影响正在进行的部署，主线照常完成
2. 发布 1.0.4 时实测：14:41 主部署上传 → 14:44 一条重复上传被拒（FAILED）→ 主部署照常 PUBLISHED
3. 预防：发布期间只保留一个发布通道；CI 与本地不要同时发同版本

### 6.8 Portal 状态查询 API 偶发 500（1.0.4 实战）

`GET /api/v1/publisher/status?id=<uuid>` 偶发 HTTP 500。改用列表接口过滤即可（见 7.2）。

### 6.9 `sdk36` 之类的 artifactId 后缀

**表现**：android 变体被发布成 `xxx-android-sdk36`（AGP 9 KMP 插件 + 裸 `maven-publish` 时可能发生）。

**本项目状态**：vanniktech 插件已接管命名，实测 28 个 publication 全部标准后缀，无 sdk36。

**预防**：升级 AGP / vanniktech 后，发布前用 10.2 探针脚本核对全部 artifactId。

---

## 7. 发布结果确认

### 7.1 Portal 页面

https://central.sonatype.com/publishing/deployments 查看部署列表：

| 状态 | 含义 |
| --- | --- |
| PENDING / VALIDATING | 校验中 |
| VALIDATED | 通过（自动发布模式下随即转 PUBLISHING） |
| PUBLISHING | 正在发布到 repo1（可持续 10~30 分钟） |
| PUBLISHED | ✅ 发布成功，永久生效 |
| FAILED | 校验失败，点开看明细；不占用版本号，修复后重试 |

### 7.2 API 查询（脚本化确认，推荐）

```powershell
$props = @{}; Get-Content "D:\Dev-tools\gradle-repository\gradle.properties" |
  ForEach-Object { if ($_ -match '^([^#]\S+?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] } }
$pair = $props['mavenCentralUsername'] + [char]58 + $props['mavenCentralPassword']
$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
$dep = Invoke-RestMethod -Uri "https://central.sonatype.com/api/v1/publisher/deployments" -Headers @{Authorization=$auth}
$dep.deployments | Select-Object deploymentName, deploymentState | Format-Table -AutoSize
```

> 单个 deployment 详情（含每组件错误明细）从返回的 `deployments[].deploymentComponents[].errors` 读取。

### 7.3 实际可下载验证（有同步延迟）

- Portal 显示 PUBLISHED 后，`repo1.maven.org` 同步需 **几分钟~30 分钟**，期间 404 属正常
- 验证地址：`https://repo1.maven.org/maven2/io/github/zusrsoft/diagram-render/<版本>/`
- 搜索索引（central.sonatype.com/search）更新更慢，约 1~2 小时，**不影响依赖解析**

### 7.4 消费方依赖验证

```kotlin
implementation("io.github.zusrsoft:diagram-render:1.0.4")

// 只要部分能力时：
implementation("io.github.zusrsoft:diagram-core:1.0.4")
implementation("io.github.zusrsoft:diagram-layout:1.0.4")
implementation("io.github.zusrsoft:diagram-parser:1.0.4")
```

---

## 8. CI 自动发布

### 8.1 触发方式

工作流：`.github/workflows/publish.yml`

- **Release 触发**（released / prereleased）：macOS runner 上构建全平台并发布
- **workflow_dispatch**：Actions 页面手动触发

### 8.2 必需的 GitHub Secrets

| Secret | 值 | 注意 |
| --- | --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Portal 令牌用户名 | |
| `MAVEN_CENTRAL_PASSWORD` | Portal 令牌密码 | |
| `SIGNING_KEY_ID` | **8 位短 ID（88DE87D5）** | ⚠️ 填 16/40 位会复现 6.3 的坑 |
| `SIGNING_PASSWORD` | GPG 密钥密码 | |
| `GPG_KEY_CONTENTS` | armored 私钥全文 | env 变量支持多行原文，无需 `\n` 转义 |

### 8.3 发版流程

1. 修改 `gradle.properties` 的 `VERSION` → 提交推送
2. GitHub 上创建 Release（tag 与版本号一致）
3. 等 Actions 跑完 → 按第 7 节确认

---

## 9. 安全注意事项

- 令牌、GPG 密码、私钥**绝不提交到仓库**；凭证只放 `GRADLE_USER_HOME\gradle.properties`（本机为 `D:\Dev-tools\gradle-repository\gradle.properties`）
- 排查过程中产生的**凭证副本**（如 `gradle.properties.bak`、写错位置的 `~/.gradle\gradle.properties` 中的凭证段落）用完即删
- 日志/文档中引用凭证时只写属性名和长度，不回显内容
- 公钥、密钥 ID、指纹是公开信息，可以写进文档和 issue
- 令牌泄露应立即到 Portal 重新生成（Account → Generate User Token 会作废旧令牌）

---

## 10. 附录：GPG 命令与实用脚本速查

### 10.1 GPG 命令

```powershell
gpg --gen-key                                   # 生成密钥
gpg --list-secret-keys --keyid-format short     # 列出密钥（看 8 位短 ID）
gpg --armor --export-secret-keys 88DE87D5       # 导出私钥（配置 signingInMemoryKey 用）
gpg --armor --export 88DE87D5                   # 导出公钥
gpg --keyserver keyserver.ubuntu.com --send-keys 88DE87D5   # 上传公钥
gpg --keyserver keyserver.ubuntu.com --recv-keys 88DE87D5   # 验证公钥可查询
# 测试自己是否记得 GPG 密码：
"test" | gpg --batch --pinentry-mode loopback -u 88DE87D5 --clearsign
```

### 10.2 publication 探针脚本（核对 artifactId，防 sdk36 后缀）

保存为 `listpub.gradle`，用于发布前核对全部 publication 的真实坐标：

```groovy
// 用法：gradlew help --init-script listpub.gradle --no-configuration-cache --console=plain
gradle.projectsEvaluated {
    gradle.rootProject.subprojects { sp ->
        def pe = sp.extensions.findByName('publishing')
        if (pe != null) {
            pe.publications.all { p ->
                try {
                    println "PUBCHECK ${sp.name} :: ${p.name} -> ${p.groupId}:${p.artifactId}:${p.version}"
                } catch (Throwable t) {
                    println "PUBCHECK ${sp.name} :: ${p.name} -> <unresolved>"
                }
            }
        }
    }
}
```

正常输出（节选，android 变体必须是 `-android` 而非 `-android-sdk36`）：

```
PUBCHECK diagram-core :: android -> io.github.zusrsoft:diagram-core-android:1.0.4
PUBCHECK diagram-core :: jvm -> io.github.zusrsoft:diagram-core-jvm:1.0.4
PUBCHECK diagram-core :: wasmJs -> io.github.zusrsoft:diagram-core-wasm-js:1.0.4
PUBCHECK diagram-core :: kotlinMultiplatform -> io.github.zusrsoft:diagram-core:1.0.4
...
```

### 10.3 POM 依赖坐标核对（发布后）

模块间依赖坐标写入各 publication 的 POM，发布后可核对：

```powershell
Get-ChildItem -Recurse -Filter "pom-default.xml" -Path ".\diagram-render\build\publications" |
  ForEach-Object { Select-String -LiteralPath $_.FullName -Pattern "<groupId>|<artifactId>" }
```

`diagram-render` 的 POM 中 `diagram-core` / `diagram-layout` / `diagram-parser` 依赖必须同为 `io.github.zusrsoft`。

---

*最后更新：2026-09-06（基于 `io.github.zusrsoft` 1.0.4 版本发布实战整理，deployment `c616485e`）*
