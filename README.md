# Short Link - 高性能短链系统

一个支持 **100万 QPS** 的生产级短链服务系统，采用分布式架构设计，支持高并发、高可用和水平扩展。

## 项目特性

- ⚡ **超高性能**：支持100万QPS访问量
- 🔐 **防穿透设计**：时间分片布隆过滤器，有效防止缓存穿透
- 🔄 **智能缓存**：本地缓存 + Redis集群 + 缓存预热
- 🗄️ **海量存储**：32库256表，总计8192张表，支持动态扩容
- 📊 **全链路监控**：Prometheus + Grafana + Sentinel
- 🚀 **云原生架构**：Spring Cloud + Nacos 微服务架构

## 技术栈

| 类别 | 技术选型 |
|------|---------|
| 核心框架 | Spring Boot 3.5.3, Spring Cloud 2023.0.3, Spring Cloud Alibaba 2023.0.1.2 |
| 数据库 | MySQL 8.0.33, ShardingSphere 5.5.1 |
| 缓存 | Redis集群, Redisson 3.35.0, Caffeine本地缓存 |
| 配置中心 | Nacos |
| 限流熔断 | Sentinel 1.8.8 |
| 监控告警 | Prometheus, Grafana, AlertManager |
| 日志 | Logback + ELK Stack |
| 工具库 | Lombok, Guava, Apache Commons Lang3 |

## 项目结构

```
src/main/java/com/winston/shortlink/
├── ShortLinkApplication.java          # 启动类
├── controller/                        # 控制层
│   ├── ShortUrlController.java        # 短链核心接口
│   ├── RedirectController.java        # 重定向控制器
│   ├── BloomFilterController.java     # 布隆过滤器管理
│   └── MonitorController.java         # 监控接口
├── service/                          # 业务层
│   ├── ShortUrlService.java           # 短链核心服务
│   ├── ShortCodeService.java          # 短码生成服务
│   ├── ClusterAwareCacheService.java  # 集群缓存服务
│   ├── BloomFilterService.java        # 布隆过滤器服务
│   └── DistributedLockService.java    # 分布式锁服务
├── entity/                           # 实体类
│   └── ShortUrlMapping.java           # 短链实体
├── dao/                              # 数据访问层
│   └── ShortUrlDao.java               # 短链数据访问
├── repository/                       # 数据仓库
├── config/                           # 配置类
│   ├── ShardingConfig.java            # 分库分表配置
│   ├── RedisConfig.java               # Redis配置
│   ├── RedissonConfig.java           # Redisson配置
│   ├── CacheConfig.java               # 缓存配置
│   └── BloomFilterAsyncConfig.java    # 布隆过滤器配置
├── constant/                         # 常量类
├── dto/                              # 数据传输对象
├── interceptor/                      # 拦截器
├── filter/                           # 过滤器
├── exception/                        # 异常处理
└── util/                             # 工具类

src/main/resources/
├── application.yml                    # 主配置文件
├── bootstrap.yml                      # Nacos配置
├── sharding-new.yaml                  # 分库分表配置
├── prometheus.yml                     # Prometheus配置
├── alertmanager.yml                   # 告警配置
├── logback-spring.xml                 # 日志配置
└── redis/                            # Redis配置文件
```

## 核心架构

### 1. 分库分表策略

- **32个数据源**：ds_0 ~ ds_31
- **256张表**：每个数据库256张表
- **总计8192张表**：支持海量数据存储
- **分片策略**：基于 short_code 哈希分片

### 2. 多级缓存架构

```
请求 → Caffeine本地缓存 → Redis集群缓存 → MySQL数据库
```

### 3. 布隆过滤器

- **时间分片**：6小时粒度，自动轮转
- **防穿透**：快速识别不存在的短链
- **异步重建**：后台异步重建，不影响业务

### 4. 分布式缓存同步

- **Redis Stream**：跨节点缓存同步
- **事件驱动**：实时更新各节点缓存
- **最终一致性**：保证集群数据一致

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+
- Nacos 2.0+

### 配置步骤

1. **修改数据库配置**

编辑 `src/main/resources/application.yml`，配置MySQL和Redis连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/short_link
    username: root
    password: your_password
  data:
    redis:
      cluster:
        nodes:
          - 127.0.0.1:7000
          - 127.0.0.1:7001
          - 127.0.0.1:7002
```

2. **配置Nacos**

编辑 `src/main/resources/bootstrap.yml`，修改Nacos服务器地址：

```yaml
spring:
  cloud:
    nacos:
      server-addr: localhost:8848
      namespace: your-namespace
```

3. **初始化数据库**

执行数据库初始化脚本（如有）创建分库分表结构。

4. **启动服务**

```bash
mvn clean package
java -jar target/short-link.jar
```

### Docker 部署

```bash
# 构建镜像
docker build -t short-link:latest .

# 启动服务
docker run -d -p 8001:8001 \
  -e MYSQL_HOST=mysql \
  -e REDIS_HOST=redis \
  --name short-link \
  short-link:latest
```

## API 接口

### 创建短链

```http
POST /api/shorturl/create
Content-Type: application/json

{
  "longUrl": "https://example.com/very/long/url",
  "customCode": "mylink"  // 可选
}

Response:
{
  "code": 200,
  "message": "success",
  "data": {
    "shortCode": "AbCdEf",
    "shortUrl": "http://short.link/AbCdEf",
    "longUrl": "https://example.com/very/long/url"
  }
}
```

### 短链重定向

```http
GET /{shortCode}

Response: 302重定向到原始URL
```

### 查询短链信息

```http
GET /api/shorturl/info?shortCode=AbCdEf

Response:
{
  "code": 200,
  "message": "success",
  "data": {
    "shortCode": "AbCdEf",
    "longUrl": "https://example.com/very/long/url",
    "createTime": "2024-01-01T00:00:00",
    "accessCount": 1000
  }
}
```

## 监控指标

系统提供以下监控指标：

| 指标类型 | 说明 |
|---------|------|
| 业务指标 | 短链创建数、重定向数、访问统计 |
| 性能指标 | QPS、响应时间、错误率 |
| 系统指标 | JVM内存、GC、线程数 |
| 缓存指标 | 命中率、缓存大小、布隆过滤器状态 |

访问 `http://localhost:8001/actuator/prometheus` 查看Prometheus指标。

## 性能优化

### 已实施的优化策略

- ✅ 多级缓存减少数据库访问
- ✅ 布隆过滤器防止缓存穿透
- ✅ 连接池优化（HikariCP）
- ✅ 异步日志输出
- ✅ JVM参数调优

### 建议的JVM参数

```bash
java -jar -Xms4g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/logs/heapdump.hprof \
  short-link.jar
```

## 贡献指南

欢迎提交 Issue 和 Pull Request！

## 许可证

[MIT License](LICENSE)

## 联系方式

- 项目地址：[GitHub](https://github.com/your-username/short-link)
- 问题反馈：[Issues](https://github.com/your-username/short-link/issues)