# ASF NativeAOT for Android 可行性评估报告

日期：2026-10-06
验证环境：.NET SDK 10.0.401（linux-x64）、ASF 源码 master（6.3.10.3+，目标框架 net10.0）

## 结论

**不可行（当前技术条件下无法实现）**。存在 1 个平台级硬阻断（无解）+ 3 个 ASF 代码级阻断（需重写级改造）。

## 验证方法

两个对照实验，分别隔离「平台缺包」与「ASF 代码不适配 AOT」：

- 实验 A：`dotnet publish -c Release -r linux-bionic-arm64 -p:PublishAot=true`（Android Bionic 目标）
- 实验 B：`dotnet publish -c Release -r linux-arm64 -p:PublishAot=true`（glibc 目标，ASF 官方支持的 RID，用于观察 ASF 自身代码在 AOT 下的表现）

## 证据链

### 阻断 1：ASP.NET Core 无 Bionic 运行时包（平台级，无解）

实验 A 在 restore 阶段失败：

```
error NETSDK1082: There was no runtime pack for Microsoft.AspNetCore.App
available for the specified RuntimeIdentifier 'linux-bionic-arm64'.
```

NuGet 实测：
- `Microsoft.NETCore.App.Runtime.NativeAOT.linux-bionic-arm64` 存在（.NET 运行时支持 Bionic）
- `Microsoft.AspNetCore.App.Runtime.linux-bionic-arm64` **不存在**（404 BlobNotFound）
- 对照组 `Microsoft.AspNetCore.App.Runtime.linux-arm64`（glibc）正常存在

ASF 的 IPC 与 ASF-ui 构建在 ASP.NET Core 之上（`ArchiKestrel.cs`：`AddControllers()` + `MapControllers()` + `AddControllersAsServices()`），没有这个包， restore 无法完成，构建根本无法开始。此阻断只能等微软官方发布 ASP.NET Core Bionic 运行时（截至验证日无任何发布计划）。

### 阻断 2：ASF 核心代码大量依赖动态代码（代码级）

实验 B 绕过一个源生成器兼容问题（CS0246）后，AOT 分析器暴露大量 IL3050 错误。因 ASF 设置了 `TreatWarningsAsErrors=true`（Directory.Build.props:64），以下全部为硬编译错误（节选）：

```
WebBrowser.cs(763):      IL3050  JsonContent.Create<T>
IPC/Integration/SwaggerValidValuesAttribute.cs(47,53): IL3050 JsonArray.Add<T>
IPC/Integration/ApiAuthenticationMiddleware.cs(90):   IL3050 WriteAsJsonAsync<T>
IPC/Controllers/Api/TypeController.cs(99):            IL3050 Enum.GetValues(Type)
Helpers/Json/JsonUtilities.cs(53-127):                IL3050 JsonSerializer.Serialize/Deserialize 系列 ×6
Helpers/Json/JsonUtilities.cs(127):                   IL3050 DefaultJsonTypeInfoResolver()
```

修复路径是将全部 JSON 序列化改造为 `JsonSerializerContext` 源生成模式、替换 `Enum.GetValues` 反射调用等——属于对 ASF 序列化基础设施的整体重写。

### 阻断 3：插件系统依赖运行时加载 IL 程序集（代码级，生态级）

`Plugins/PluginsCore.cs:808`：

```csharp
assembly = Assembly.LoadFrom(assemblyPath);
```

ASF 在运行时扫描插件目录并动态加载任意 DLL（官方 ItemsMatcher/MobileAuthenticator 等插件 + 全部第三方插件生态）。NativeAOT 无 JIT，`Assembly.LoadFrom` 无法工作。代码中压制 IL2026 警告的前提是「运行时有 JIT 存在」，AOT 下前提不成立。此阻断意味着 AOT 化 ASF 将同时杀死插件生态。

### 阻断 4：ASF 官方从未支持 AOT（代码级）

- 官方 RID 列表：`linux-arm; linux-arm64; linux-x64; osx-*; win-*`（Directory.Build.props:18）
- `TrimMode=partial`（仅部分裁剪，即官方明确假设运行时有 JIT）
- 源码中多处 `UnconditionalSuppressMessage(IL2026/IL3000)` 注解均为动态运行时设计

## 综合判定

| 阻断 | 层级 | 可绕过性 |
|------|------|----------|
| ASP.NET Core 无 Bionic 包 | 微软平台层 | 无解（等官方支持） |
| JSON/IPC 动态代码 IL3050 | ASF 代码层 | 需整体重写序列化 + IPC |
| Assembly.LoadFrom 插件 | ASF 架构层 | 需重设计插件机制，牺牲插件生态 |
| TreatWarningsAsErrors + partial trim | ASF 工程层 | 需全量 AOT 注解审计 |

重新评估条件：微软发布 ASP.NET Core Bionic 运行时包，且 ASF 上游官方支持 NativeAOT。在此之前任何尝试均为重写级投入，且结果是一个失去插件生态的 ASF 分叉。

## 建议

维持 PRoot 独立 APK 方案（已实现：独立分发、一键启动、内嵌 Web UI、前台服务）。PRoot 非指令集模拟，ASF 原生 arm64 代码直接执行，开销仅在系统调用翻译层，对网络/IO 密集型的 ASF 影响可接受。后台保活问题通过电池优化白名单引导 + 看门狗 + 独立 `:asf` 进程解决。
