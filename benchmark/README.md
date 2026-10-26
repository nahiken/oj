# Benchmark

P0 基线压测目录。目标：在改造前记录可复现的提交/运行/查询指标。

## 前置

- Gateway 已启动：`http://127.0.0.1:8024`
- MySQL、Redis、Nacos 已启动
- 沙箱服务可用（Docker 或 native）
- Node 20+（脚本无第三方依赖）
- 已执行 `benchmark/setup.ps1` 准备测试用户、题目和 Cookie

## 快速开始

```powershell
# 1. 初始化测试数据 + 登录 Cookie
.\benchmark\setup.ps1

# 2. 冒烟：20 并发 30 秒
$env:CONCURRENCY='20'; $env:DURATION_SEC='30'; $env:LABEL='smoke'
node .\benchmark\load.js

# 3. 完整基线
.\benchmark\run-baseline.ps1
```

Linux/macOS 用 `export VAR=value` 代替 `$env:VAR='value'`。

## 场景

| 场景 | 命令 | 目的 |
|---|---|---|
| 稳定 20 | `CONCURRENCY=20 DURATION_SEC=300` | 低负载基线 |
| 稳定 50 | `CONCURRENCY=50 DURATION_SEC=300` | 中负载 |
| 稳定 100 | `CONCURRENCY=100 DURATION_SEC=300` | 高负载 |
| 突发 120/15s | `CONCURRENCY=120 DURATION_SEC=15` | 复现简历突发场景 |
| 查询 | `PATHNAME=/api/judge/submit/page/vo BODY_FILE=.runtime/query-body.json` | 读路径基线 |
| 运行 | `PATHNAME=/api/judge/run BODY_FILE=.runtime/run-body.json` | 自测路径 |

## 指标口径

- **成功率**：HTTP 2xx 占比。提交接口的 2xx 只代表“已受理”，不代表判题通过。
- **P95**：客户端观测到的请求耗时（毫秒）。
- **RPS**：完成请求数 / 实际运行秒数。
- 端到端判题 P95 需要额外轮询 `/api/judge/submit/{id}`，见后续脚本。

## 环境记录

每次压测必须填写并写入 `progress.md`：

```text
CPU:            {{}}
内存:           {{}}
OS:             {{}}
Docker:         {{}}
JDK:            {{}}
镜像:           {{}}
题目数/用例数:   {{}}
并发模型:        {{}}
```

## 输出

- 控制台摘要
- `benchmark/results/<timestamp>/<label>.json`
- 汇总表由 `run-baseline.ps1` 生成

## 注意

- 结果目录默认被 `.gitignore` 忽略，只提交脚本与汇总模板。
- 不要在压测期间同时跑 Maven 构建、IDE 索引或 Docker 拉镜像。
- 先跑 `setup.ps1`，否则提交会 401 或 404。
