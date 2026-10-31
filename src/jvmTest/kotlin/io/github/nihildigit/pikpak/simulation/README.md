# 确定性传输验收（仅限内存状态机回归，不代表真实网络吞吐）

> **实测局限说明（2026-10-31 真实多地域节点验证）**：
> 本目录的模拟矩阵**不能证明真实跨国链路上的吞吐表现**，对高 RTT 代理节点的速度优化基本没有指导作用：
> 1. `VirtualNetwork` 假设服务端窗口上限为 `8 MiB`，而真实 PikPak `dl-z01a` 全集群（22 台存活节点，含原 `0053/0054`）均在服务端内核锁死 **`64 KiB` TCP 发送窗口**（相差 128 倍）。高 RTT 下单连接物理上限被 `64 KiB / RTT` 焊死，模拟器中靠扩块在 `RTT=200ms` 跑出 `120+ MB/s` 的结论在真实网络中完全不成立。
> 2. `degradedEdgeFindsSibling` 手工喂入 `125 MB/s` 同族节点，不符合真实 `FileDetail.medias` 跨簇（`dl-a10b`）及同簇 `43~346 KiB/s` 连续劣化分布。
> 3. 未覆盖跨洋代理 TLS 冷握手（`1.4~3.2s`）、突发重传抖动触发 `BodySilence` 连环换冷主机、运行中途切换代理节点后的旧样本残留，以及单文件受单签名 URL 8 连接限制而闲置一半账号连接预算的瓶颈。
>
> 因此这套测试仅用于回归校验 `BlockCache` / `RangeReader` 的字节拼接完整性、内存上限与 8 连接门禁不越界；性能判断一律以真实多节点联调日志为准。

`TransportSimulationTest`（文件 `TransportSloTest.kt`）将真实 `BlockCache`、账号 `TransferScheduler`、`RangeReader`、`HostHealth` 接到流式 Ktor MockEngine。测试未替换控制器。生产组件与网络、磁盘均使用同一 `TestCoroutineScheduler` 时钟。所有吞吐场景的 SDK 缓冲预算为 16 MiB。

## 场景和断言

- 125,000,000 B/s 全局瓶颈，RTT 30 ms、TTFB 30 ms：稳态唯一持久化吞吐至少 92%。
- 同一瓶颈，RTT 200 ms、TTFB 250 ms：至少 90%。
- 同一瓶颈，RTT 200 ms、TTFB 1350 ms：至少 90%。
- 400 KiB/s 每连接退化边缘，通过 `client.hostHealth.observe(FileDetail)` 的媒体链接学习 sibling；64 MiB 必须在 60 秒内持久化，且确实访问 sibling。候选来自测试媒体元数据，不依赖生产默认 seeds。
- 无 sibling 的 400 KiB/s 每连接退化边缘下载 256 MiB：稳态吞吐至少超过旧 1 MiB 请求的理想八路上限 20%。旧策略上限由 `8S / (TTFB + S/r)` 计算，未将解析上限伪装成旧代码实测。
- 第二个请求在 64 KiB + 17 字节处正文 reset，加确定性 loss：grant 不得越过故障偏移，记录实际故障，并核对失败 RangeAttempt 的 delivered 与后续请求起点、终点，所有持久化字节一致。
- 首请求停顿 30 秒，非 urgent seek 指向它已拥有的位置：分别报告 250 ms 和 1000 ms 播放缓冲期限，替代请求须在期限后 100 ms 内开始，5 秒内取得正确的 64 KiB。两次替代时间必须随期限改变，原请求须为 Cancelled 而非 Failed，避免把正文静默 watchdog 当作期限抢救。独立 urgent 场景报告 1000 ms 正缓冲，仍要求 100 ms 内替代低优先级请求。
- 固定非对齐偏移 seek storm：交替取消在途读取与跳转，验证每次 96 KiB 内容，检查取消分类、连接与内存释放。
- 单独验证模拟 CDN 的第九个活动请求返回 503；生产路径不得触发此限制。取消一个正文后，同一虚拟时间戳的替代请求必须获准，另外七个仍开放正文必须继续占用连接。

吞吐用预先固定的 20% 至 80% 唯一持久化字节区间，记录实际跨过两个阈值时的字节数和时间，以实际字节差计算，排除启动与最后少量连接的收尾阶段。持久化窗口上界允许窗口起点实测已经传输但尚未落盘的字节结转；每个 tick 的 wire 容量仍独立严格守恒。不是根据观察结果挑选最佳窗口。每次同时输出从冷启动到最后写入的总时间和端到端 B/s，不能把稳态成绩解释成端到端成绩。样本为 2 GiB，无无限预热。当前没有对端到端时间设置与 oracle 的比例门槛。

## 网络模型与边界

- 单一 1 ms tick 分配全局容量，公平水位分配不能增加总配额，空闲 tick 的额度不结转。
- 每个模拟 socket 保存 cwnd、ssthresh、未 ACK 字节、ACK 队列和复用状态。初始窗口为 10 MSS，ACK 延迟为 RTT，慢启动按 ACK 字节增长，拥塞避免近似 Reno。
- 第 N 个累计 packet 触发确定性 loss；丢失批次占用 wire 配额，窗口减半，至少一个 RTT 后重传。不是随机丢包分布或完整 fast-recovery/RTO 实现。
- 冷连接增加一次 RTT 建连成本。每次 HTTP 请求独立支付 TTFB；完整正文保留 socket/window，取消关闭，不复用。空闲超过十个 RTT 时衰减至初始窗口。
- MockEngine 正文是有背压的 ByteChannel，生产者按配额分段写入，单次最多 128 KiB，不预先生成完整响应。每次准入和每个 tick 只回收 isClosedForWrite 的 flow，取消 writer 后幂等释放，不提前释放仍开放的真实请求。退化场景和下载失败输出每次请求的 host、字节范围、开始及结束虚拟时间、完成状态和连接复用信息。
- 磁盘是串行、有限速的 DurableBlockStore，每次收到真实字节逐字节验证，只保留提交元数据；读取已提交块时重建确定性内容。不把模拟文件数据常驻 heap。
- 每 tick 校验瓶颈守恒，退出校验活动请求和 SDK ownership credits 清零。

16 MiB 断言针对 SDK `TransportMemoryBudget` 的字节数组所有权，不包含 Ktor channel、模拟网络生产者、内核 socket buffer、元数据及 JVM 总 heap。测试另外记录磁盘同时持有的写入字节。当前没有独立 JVM 分配探针证明整个传输路径总 heap 小于 16 MiB。

模型未覆盖 DNS/TLS、真实 socket 身份、TCP 接收自动调节、SACK、相关突发 loss、API mint 限流、多文件公平性、Android ART 或 Darwin。5 秒 seek 是明确的回归门槛，不是经过同约束 oracle 证明的最优值。

## 真实数据面

相邻 `benchmark/LoopbackTransportBenchmarkTest.kt` 使用 JDK HTTP/1.1 回环服务端、真实 `defaultCdnHttpClient`、真实生产缓存和 `RandomAccessFile` DurableBlockStore。下载 128 MiB，SDK 预算 16 MiB，最终 fsync 并逐字节回读验证，记录 MB/s、GC 时间、请求数、socket 数和峰值 SDK 内存。

真实吞吐只报告，不对普通开发机器设置 125 MB/s 硬门槛。测试不访问 PikPak，不修改现有 probes。产物保留在 `build/transport-benchmark/loopback-*.bin`，测试不删除文件。它不是 Android 性能证据，也没有测量 CPU allocation、协程唤醒或锁等待。

## 本次验证结果

最终工作树串行通过 `compileCommonMainKotlinMetadata`、`assemble -Pkotlin.native.ignoreDisabledTargets=true`、137 项目标测试，以及全部 commonTest 加 FileSessionStoreTest 的 333 项非 live 测试。assemble 编译了 iOS 两目标及 commonTest，未运行 iOS 模拟器。日志保存在 `build/transport-metadata.log`、`build/transport-assemble.log`、`build/transport-final-tests.log`、`build/transport-all-unit-tests.log`。

| 场景 | 数据量 | 稳态 MB/s | 冷启动至落盘秒数 | 峰值计费内存 |
| --- | ---: | ---: | ---: | ---: |
| RTT30 TTFB30 千兆 | 2 GiB | 125.054621 | 17.630 | 3 MiB |
| RTT200 TTFB250 千兆 | 2 GiB | 120.923300 | 21.419 | 3 MiB |
| RTT200 TTFB1350 千兆 | 2 GiB | 124.957595 | 28.959 | 3 MiB |
| 退化边缘和同族 sibling | 64 MiB | 3.293212 | 26.102 | 3 MiB |
| 退化边缘无 sibling | 256 MiB | 3.097260 | 88.830 | 3 MiB |
| reset 加 loss | 64 MiB | 115.919168 | 0.983 | 3 MiB |

低 RTT 的窗口结果比 125 MB/s 高 0.0437%，来自窗口起点已在流水线内的字节结转，不代表链路超发。无 sibling 场景超过旧 1 MiB 策略理想上限 2.145424 MB/s 的 44.37%。

250 ms、1000 ms 缓冲期限分别在 250 ms、1000 ms 启动替代，正确的 64 KiB 分别在 372 ms、1121 ms 到手；urgent 在 0 ms 启动替代，121 ms 到手。六次 seek storm 用时 6456 ms，67 个请求，峰值 4.5 MiB，零 503。reset 位于第二次请求已交付 65553 字节后，精确从文件偏移 327697 续接。

回环最终测量：128 MiB，0.7335199 秒，182.977623 MB/s，峰值计费内存 3 MiB，22 个请求、8 个 socket、最多 8 个活动请求、GC 25 ms，fsync 后逐字节回读一致。产物 `build/transport-benchmark/loopback-17171325116411044637.bin`。

使用稳定的 fetch 插入顺序消除了等长 tail 的对象 hash 分支。连续两轮独立 simulation 运行的五个原有下载 profile 和三个 rescue profile 输出完全一致，记录在 `build/transport-deterministic-a.log` 与 `build/transport-deterministic-b.log`。这不等于已覆盖随机 seed 扫描、所有网络分布或设备平台。

reset 前的 1 ms 排空让失败偏移可重复，不模拟真实内核 reset 对未读缓冲的具体处置。
