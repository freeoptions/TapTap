# URL Controller Search

面向IntelliJ IDEA 2023.1至2025.3系列的Java + Spring MVC插件。双击 Shift，在 **All** 页粘贴 URL，在 **URL Controllers** 分组选择结果，回车定位 Controller 方法。

## 安装与使用

1. 打开 IDEA 的 Settings → Plugins → 齿轮 → Install Plugin from Disk。
2. 选择 `URL-Controller-Search-1.0.2.zip`，按 IDEA 提示重启。已安装旧版时，使用相同入口更新。
3. 打开 Java 项目，等待 IDEA 首次索引结束。
4. 双击 Shift，选择 All，输入 `outboundorder/list`、`tboundorder/list`、`boundorder/li`或完整URL，例如 `https://example.com/outboundorder/list?page=1`。
5. 匹配的 URL Controllers 结果优先排在普通类、文件、方法结果之前；同组内保持完整路由匹配优先，其次为后缀、模板、前缀和连续子串匹配。选择结果回车跳到方法，结果显示请求方式、原始路由、类名、方法名和文件位置。

搜索忽略大小写仅影响插件查找，不改变 Spring 实际处理 HTTP 请求时的大小写规则。首次安装、清空 IDEA 缓存后需要重新索引；索引期间暂不提供路由结果。

## 支持范围

- Java 源文件中的 `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping`。
- 类路径与方法路径组合，`value` / `path`、数组路径、多个 HTTP 请求方式、无路径的方法映射。
- 字符串字面量、字符串字面量拼接、本类及其外围类中的 `static final String` 常量。
- 完整 URL 自动提取 path，移除查询参数及 fragment，补齐开头斜杠、忽略末尾斜杠。
- 完整路由精确匹配优先，其次为路径后缀、路径变量/通配符匹配，再显示完整路由的前缀匹配和任意位置的连续子串匹配。
- 路径变量如 `/orders/{id}`、正则变量如 `/orders/{id:[0-9]{2,4}}`、`*`、`**`、`?` 通配符。
- 当前项目及其模块中的可索引 Java 文件；依赖 JAR、排除目录不作为搜索范围。
- 同 URL 的不同请求方式、Controller 或模块保留为独立结果，最多返回 100 条。

例如代码声明/api/outboundorder/list，输入outboundorder/list可以通过后缀匹配找到，输入tboundorder/list或boundorder/li也可以通过连续子串匹配找到；不支持跳字或拼写纠错。若完整 URL 带有声明中不存在的网关前缀或 context-path，请粘贴去掉该前缀的路由；本版不会猜测并移除任意业务路径。

本版暂不支持跨文件常量/静态导入常量、自定义组合注解、继承或接口中的映射组合、配置占位符 `${...}` / SpEL，以及运行时动态注册路由。无法在当前文件内确定的路径会跳过，避免显示错误 URL。代码存在多种映射注解时按首个受支持注解处理，应遵循 Spring 在单个类/方法上仅使用一个映射注解的约定。

## 缓存与性能

1. **磁盘索引**：使用 IDEA FileBasedIndex；只接受 Java 文件，先以 `Mapping` 进行文本筛选，再解析当前文件 PSI。索引不解析跨文件引用、不嵌套访问其他索引，代码编辑后由 IDEA 增量更新受影响文件。
2. **项目内存路由表**：首次搜索从持久化索引构建。精确和静态后缀匹配使用预建表，路径模板预编译，完整路由前缀使用有序表；低优先级的子串匹配扫描内存中的路由键，达到100条结果后停止，搜索时不会扫描项目源码。
3. **查询缓存**：保存最近 128 个规范化查询；大小写不同的相同 URL 共用缓存。索引、PSI 或项目根发生变化后重建内存表并清理查询缓存。普通代码编辑也可能触发内存表重建，读取的是索引记录，无需重新解析所有源文件。
4. **导航检查**：回车时仅重新检查选中的源文件，避免窗口显示后代码发生变化导致按旧 offset 跳错。

本机5万条静态路由、Java17运行时的内存查找基准：1万次不同后缀查询P95约3.2ms，200次不同子串查询P95约1.7ms。此数字包含新增子串补充匹配，不含IDEA索引、缓存验证、窗口渲染或导航；真实业务项目尚未测速。动态路由匹配会遍历预编译模板，动态路由很多时耗时会增加。

## 构建

使用PowerShell 7和JDK17或更高版本；生产代码固定使用IDEA 2023.1SDK、Java17字节码编译，不需要Gradle或Maven。SDK/JBR和测试依赖不会打入安装包。

先准备[JetBrains官方2023.1SDK](https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIC/2023.1/ideaIC-2023.1.zip)，解压到../../work/idea-url-controller-build/sdk-2023.1，也可用-SdkHome指定其他路径。默认编译器使用-IdeaHome下的jbr，可用-JavaHome指定独立JDK目录。

```powershell
pwsh -File .\build.ps1 -IdeaHome 'D:\@Softwares\idea'
```

默认构建中间文件写入源码目录外的 `../../work/idea-url-controller-build`，安装 ZIP 写到源码目录的上一级。可以用 `-BuildDirectory` 指定专用中间目录。构建会执行路由匹配与性能测试。

真实平台集成测试（先构建；首次运行从 Maven Central 下载 JUnit 等测试依赖到中间目录）：

```powershell
pwsh -File .\test-platform.ps1 -IdeaHome 'D:\@Softwares\idea'
```

集成测试使用独立、无界面的 IDEA 沙箱及真实 PSI/索引；不会修改现有 IDEA 配置或业务项目。测试类只放入沙箱的测试 JAR，不进入交付安装包。

源码入口：

- `SpringRouteIndex.java` / `SpringRoutes.java`：索引与映射提取。
- `RouteCache.java` / `RouteTable.java`：缓存与匹配。
- `ControllerContributorFactory.java`：Search Everywhere 接入与导航。
- `src/main/resources/META-INF/plugin.xml`：插件注册，兼容范围为231至253分支，即2023.1至2025.3。

## 验证记录与人工验收

已验证：IDEA 2023.1SDK及Java17编译；10,230个路由断言，覆盖单词中间片段、大小写、完整URL、优先级、结果数量上限及5万条路由基准。真实IDEA 2023.1、2024.1、2024.3、2025.3.4平台测试验证了PSI提取、磁盘索引、缓存、片段搜索、未保存修改、删除文件、Search Everywhere混合排序和方法跳转。2023.1测试使用普通JDK17，平台输出字体扩展接口警告，路由测试断言全部通过。

尚未验证：正常IDEA窗口下的人工操作、真实业务仓库响应时间、IDE重启后的缓存复用、远程开发模式及兼容范围内全部补丁版本。Plugin Verifier的兼容检查结果见下方版本记录。

兼容版本记录（生产包统一使用2023.1SDK编译）：

| IDEA版本 | 真实平台测试 | Plugin Verifier |
| --- | --- | --- |
| 2023.1（231.8109.175） | 通过 | Compatible |
| 2024.1（241.14494.240） | 通过 | Compatible |
| 2024.3（243.21565.193） | 通过 | Compatible |
| 2025.3.4（253.32098.37） | 通过 | Compatible |

人工验收背景：确认安装后的原生搜索入口、项目路由展示和编辑后的更新符合日常使用。

| 页面入口 | 操作场景 | 预期结果 |
| --- | --- | --- |
| 双击 Shift → All | 粘贴 `outboundorder/list` | URL Controllers 显示正确方法，回车定位 |
| 同上 | 粘贴 `OUTBOUNDORDER/LIST` 或完整 URL | 命中相同路由，显示代码原始大小写 |
| 同上 | 输入tboundorder/list或boundorder/li | 命中/OutboundOrder/list，完整匹配仍排在子串匹配前面 |
| 同上 | 搜索有多个 Controller/请求方式的路由 | 保留多个结果，能根据类名与文件选择 |
| 同上 | 输入 `getInfoById` 等 URL 后缀，同时命中类、文件及普通方法 | 所有匹配路由优先显示，其他结果排在下面 |
| 编辑 Controller 后重新双击 Shift | 修改映射但不保存 | 旧 URL 消失，新 URL 可搜索 |
| 删除 Controller 后重新搜索 | 搜索原 URL | 不再显示已删除方法 |
