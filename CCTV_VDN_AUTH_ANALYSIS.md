# 央视播放器 vdnxbk API 认证签名深度分析

基于 liveplayer.js (ver 1.24) 的完整分析

## 一、核心发现总结

### 1.1 API 端点

- **直播流**: `vdnxbk.live.cntv.cn/api/v3/vdn/live`
- **回放流**: `vdnxbk.live.cntv.cn/api/v3/vdn/livets`

### 1.2 签名生成核心逻辑

从混淆代码中提取的关键片段（位置 32097）：

```javascript
// 生成时间戳（10位）
var timestamp = new Date().getTime();  // 毫秒
timestamp = timestamp.toString().substring(0, 10);  // 取前10位，转为秒级

// 生成随机数（100-999之间）
var random = Math.round(Math.random() * 1000);
if (random - 100 < 0) {
    random += 100;
}

// 签名盐值
var salt = "7G17927AY79W7A979H79W7G179P7AA7AG";  // PC端
var saltIpad = "47899B86370B879139C08EA3B5E88267";  // iPad/移动端

// **关键：authKey/sign 生成算法**
// authKey = timestamp + '-' + random + '-' + MD5(channel + timestamp + random + salt).toLowerCase()
var authKey = timestamp + '-' + random + '-' + setH5Str(channel_t + timestamp + random + salt).toLocaleLowerCase();
```

### 1.3 认证参数完整说明

**vdnxbk API 请求参数：**

| 参数名 | 说明 | 示例/取值 |
|--------|------|----------|
| `channel` | 频道ID | `cctv1`, `cctv13`, `cctv4` 等 |
| `vn` | 客户端类型 | `1` (PC), `1000` (iPad) |
| `pdrm` | 是否需要DRM | `0` 或 `1` |
| `uid` | 用户指纹ID | 从 Cookie 获取（可空） |
| `hbss` | 心跳时间戳 | 与 timestamp 相同 |

**HTTP 请求头：**

| 请求头 | 值 | 说明 |
|--------|-----|------|
| `auth-key` | `{timestamp}-{random}-{sign}` | 认证签名 |
| `vtoken` | （可选）| 如果页面提供 |

## 二、签名生成详细算法

### 2.1 setH5Str 函数分析

`setH5Str` 实际上是一个 **MD5 哈希函数**的别名或包装。从代码中追踪：

```javascript
// 位置 32097 附近
_0x16c7ff = _0x47c247 + '-' + _0x1aa34a + '-' + setH5Str(_0x5614e6['t'] + _0x47c247 + _0x1aa34a + _0x237b0f)['toLocaleLowerCase']();
```

`setH5Str` 输入字符串，输出 32 位十六进制 MD5 值。

### 2.2 完整签名生成步骤

```plaintext
输入：
  - channel: 频道ID (如 "cctv1")
  - salt: 盐值（根据客户端类型选择）

步骤：
1. timestamp = 当前时间戳（秒，10位）
2. random = 随机数（100-999）
3. str = channel + timestamp + random + salt
4. sign = MD5(str).toLowerCase()
5. authKey = timestamp + "-" + random + "-" + sign

输出：
  authKey（HTTP 请求头）
```

### 2.3 盐值（Salt）

```javascript
// 从混淆代码中提取的常量
var SALT_PC = "7G17927AY79W7A979H79W7G179P7AA7AG";     // PC端/默认
var SALT_IPAD = "47899B86370B879139C08EA3B5E88267";    // iPad/移动端

// 另一个盐值（旧版或备用）
var SALT_OLD = "B4B51E8523157ED8D17ADB76041BCD09";

// 使用规则：
if (isIPad()) {
    salt = SALT_IPAD;
} else {
    salt = SALT_PC;
}
```

## 三、完整请求构建示例

### 3.1 直播流请求

```
GET /api/v3/vdn/live?channel=cctv1&vn=1&pdrm=0&uid=&hbss=1710327654
Host: vdnxbk.live.cntv.cn
auth-key: 1710327654-567-a1b2c3d4e5f6...（32位MD5）
```

其中 `auth-key` 计算：
```
timestamp = "1710327654"
random = "567"
channel = "cctv1"
salt = "7G17927AY79W7A979H79W7G179P7AA7AG"

str = "cctv1" + "1710327654" + "567" + "7G17927AY79W7A979H79W7G179P7AA7AG"
    = "cctv117103276545677G17927AY79W7A979H79W7G179P7AA7AG"

sign = MD5(str).toLowerCase()
     = "..." (32位十六进制)

authKey = "1710327654-567-" + sign
```

### 3.2 回放流请求

```
GET /api/v3/vdn/livets?channel=cctv1&vn=1&pdrm=0&uid=&begintimeabs=1710320000000&hbss=1710327654
Host: vdnxbk.live.cntv.cn
auth-key: ...
```

## 四、响应解密分析

### 4.1 响应格式

vdnxbk API 返回 JSON 响应（部分字段可能加密）：

```json
{
  "ack": "yes",
  "manifest": {
    "hls_url": "...",
    "hls_cdrm": "...",
    "hls_nd": "..."
  },
  "lc": {
    "ip": "...",
    "isp_code": "...",
    "provice_code": "...",
    "city_code": "..."
  }
}
```

### 4.2 AES 解密参数（若需要）

从代码中找到的 AES 密钥和 IV（Base64 编码）：

```javascript
// 可能的 AES Key（44字节 Base64）
const KEY_1 = "0hdiziKsev1LRe24oGTMPwfg9f+kcCWQ56sxi+jMAKE=";
const KEY_2 = "JMo0DT+7XkLZcT1KE1Nv8rOXwxDc7UmOB7eVzx11MvU=";

// 可能的 AES IV（24字节 Base64）
const IV_1 = "I6JulGGNroT8GVjO56ss6A==";
const IV_2 = "QQ5Pe7EiIIUWIpqmJL0oGg==";

// 加密模式：CBC
// 填充：Pkcs7
```

**注意**：当前分析表明 vdnxbk API 的 JSON 响应通常**不加密**，加密主要用于旧版 API。

## 五、代码混淆标记说明

liveplayer.js 使用了混淆器，关键标识：

- **字符串数组**: `a0_0x6951` （包含所有硬编码字符串）
- **解码函数**: `a0_0x18ae10` （访问字符串数组）
- **混淆函数**: `_0x204638`, `_0xc256f9`, `_0x5c8fe1` 等（动态函数名）

混淆字符串索引示例：
```javascript
_0x204638(0x438) = 'authKey'
_0x204638(0x2c5) = 'random'
_0x204638(0xed)  = 'round'
_0x204638(0x218) = 'getTime'
```

## 六、关键代码位置索引

| 位置 | 内容 |
|------|------|
| 0 | 字符串数组 `a0_0x6951` 开始 |
| 8206 | `vdnxbk.live.cntv.cn/api/v3/vdn/live` 字符串 |
| 14504 | `vdnxbk.live.cntv.cn/api/v3/vdn/livets` 字符串 |
| 32097 | **核心签名生成逻辑** (authKey 赋值) |
| 51099 | 回放流的 authKey 生成逻辑 |
| 176331 | `doLoadLiveDataByAjax` 函数（HTTP 请求）|
| 202472 | `parseLiveDataFromVdnx` 函数（解析响应）|

## 七、Android Java 实现建议

### 7.1 依赖库

```gradle
// build.gradle
implementation 'commons-codec:commons-codec:1.15'  // MD5
implementation 'com.squareup.okhttp3:okhttp:4.9.3' // HTTP 请求
```

### 7.2 核心类设计

```java
public class CctvVdnAuth {
    // 盐值常量
    private static final String SALT_PC = "7G17927AY79W7A979H79W7G179P7AA7AG";
    private static final String SALT_MOBILE = "47899B86370B879139C08EA3B5E88267";

    /**
     * 生成 authKey 签名
     * @param channel 频道ID
     * @param isIPad 是否移动端
     * @return authKey 字符串
     */
    public static String generateAuthKey(String channel, boolean isIPad) {
        // 实现见下一节
    }

    /**
     * 构建完整的 vdnxbk 请求
     */
    public static String buildVdnxbkUrl(String channel, boolean isLive) {
        // 实现见下一节
    }
}
```

### 7.3 完整实现代码

参见项目中的 `CctvVdnAuthHelper.java`（将单独创建）。

## 八、测试验证

### 8.1 测试用例

```java
@Test
public void testAuthKeyGeneration() {
    String channel = "cctv1";
    long timestamp = 1710327654L;
    int random = 567;

    String expected = "1710327654-567-" + calculateSign(channel, timestamp, random, false);
    String actual = CctvVdnAuth.generateAuthKey(channel, timestamp, random, false);

    assertEquals(expected, actual);
}
```

### 8.2 实际请求测试

使用 curl 或 Postman 测试：

```bash
# 生成 authKey（使用在线 MD5 工具或 Java 代码）
timestamp=$(date +%s)
random=$((RANDOM % 900 + 100))
str="cctv1${timestamp}${random}7G17927AY79W7A979H79W7G179P7AA7AG"
sign=$(echo -n "$str" | md5sum | awk '{print $1}')
authKey="${timestamp}-${random}-${sign}"

# 发送请求
curl -H "auth-key: $authKey" \
  "https://vdnxbk.live.cntv.cn/api/v3/vdn/live?channel=cctv1&vn=1&pdrm=0&uid=&hbss=${timestamp}"
```

## 九、注意事项

1. **时间同步**: 确保设备时间准确，误差不能超过几分钟
2. **随机数范围**: 严格在 100-999 之间
3. **MD5 小写**: 签名必须转为小写
4. **字符编码**: 使用 UTF-8 编码
5. **HTTPS**: 生产环境必须使用 HTTPS
6. **User-Agent**: 建议设置合理的 User-Agent 头

## 十、后续工作

- [ ] 完整 Java 实现并集成到 StreamParser
- [ ] 添加错误重试机制
- [ ] 实现响应解密（如需要）
- [ ] 添加单元测试
- [ ] 性能优化（缓存 authKey）

---

**分析完成时间**: 2025-03-13
**源文件**: liveplayer.js (https://js.player.cntv.cn/creator/liveplayer.js)
**文件大小**: 243KB
**版本**: 1.24

