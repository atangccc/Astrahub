# AstraHub 星际通讯前端元素与后端数据字段设计

本文档用于描述 Halo AstraHub 插件中“星际通讯”页面当前前端 demo 的完整元素、交互、数据字段和后端接口建议。后端开发时应尽量按本文档提供稳定字段，前端后续可以从 mock 数据平滑切换到真实 API。

## 1. 页面整体结构

“星际通讯”位于 AstraHub 控制台导航中，导航位置在“关系图”之后。

页面由以下区域组成：

1. 聊天消息主面板
2. 底部消息输入区
3. 右侧星链成员列表抽屉
4. 头像右键菜单
5. 成员资料弹窗
6. 发起友链邀请弹窗状态
7. 消息记录搜索弹窗
8. 表情选择气泡
9. 新消息下滑提示
10. Toast 提示

## 2. 初始化数据

前端页面启动时需要一次性拿到当前站点、频道、成员列表、最近消息和可选表情列表。

推荐接口：

```http
GET /v1/star-communications/bootstrap
```

推荐响应：

```ts
type StarCommunicationBootstrapResponse = {
  success: true
  data: {
    currentSite: StarCommunicationMember
    channel: StarCommunicationChannel
    members: StarCommunicationMember[]
    recentMessages: StarCommunicationMessage[]
    emojis?: StarCommunicationEmoji[]
    unreadCount?: number
  }
}
```

## 3. 当前站点 currentSite

用于判断消息是否为自己发送、发送消息认证、自己的头像和“我 · 星系名称”展示。

```ts
type CurrentSite = {
  siteId: string
  siteName: string
  siteUrl: string
  avatarUrl?: string
  avatarText?: string
  galaxyName: string
  apiStatus: "connected" | "disconnected"
}
```

前端使用位置：

- 自己发送的消息右侧显示
- 发送消息时作为 sender
- 判断 `message.senderSiteId === currentSite.siteId`
- 自己消息标题显示为 `我 · galaxyName`

## 4. 频道 Channel

当前 demo 只有一个公共频道，后端第一版可以固定为 public。

```ts
type StarCommunicationChannel = {
  id: string
  name: string
  type: "public" | "private" | "system"
  description?: string
  memberCount: number
  createdAt: string
  updatedAt: string
}
```

前端使用位置：

- 当前聊天区对应频道
- 消息列表查询
- 消息发送
- WebSocket 事件过滤

## 5. 右侧星链成员列表

### 5.1 可见元素

成员列表在页面右侧抽屉中，包含：

- 抽屉折叠/展开按钮
- 标题：`星链成员 (数量)`
- 搜索按钮
- 搜索输入框
- 取消搜索按钮
- 成员卡片列表

每个成员卡片包含：

- 头像
- 名称
- 星系名称
- 已连状态标签
- 加入时间
- 最近活跃时间

展示格式：

```md
主标题：站点名称 · 星系名称
副标题：加入 joinedAt · 活跃 lastActiveAt
状态：isFriendLinked = true 时显示“已连”
```

### 5.2 成员字段

```ts
type StarCommunicationMember = {
  siteId: string
  siteName: string
  siteUrl: string
  avatarUrl?: string
  avatarText?: string
  galaxyName: string
  description: string
  contactEmail?: string
  status: "active" | "inactive" | "external" | "blocked"

  isFriendLinked: boolean
  relationStatus: "none" | "following" | "followed_by" | "mutual"
  friendLinkCount: number

  trustScore: number
  influenceScore: number
  rssStatus: "active" | "inactive" | "none"

  joinedAt: string
  joinedText?: string
  lastActiveAt: string
  lastActiveText?: string
}
```

### 5.3 成员搜索规则

前端搜索会匹配：

- `siteName`
- `siteUrl`
- `galaxyName`

推荐接口：

```http
GET /v1/star-communications/members?keyword=&limit=&cursor=
```

响应：

```ts
type MemberListResponse = {
  success: true
  data: {
    items: StarCommunicationMember[]
    nextCursor?: string
    hasMore: boolean
  }
}
```

## 6. 聊天消息主面板

### 6.1 可见元素

聊天主面板包含：

- 顶部“加载历史通讯记录”提示行
- 消息列表
- 左侧消息
- 右侧自己的消息
- 消息头像
- 消息发送者名称
- 发送者星系名称
- 消息时间
- 消息气泡
- 新消息下滑提示

### 6.2 消息展示规则

对方消息：

```md
头像在左
标题：senderSiteName · senderGalaxyName
时间在标题右侧
消息气泡靠左
```

自己消息：

```md
头像在右
标题：时间 + 我 · currentSite.galaxyName
消息气泡靠右
```

注意：

- 聊天标题中的名称和星系名称不可点击。
- 聊天标题不显示 URL。
- 只有头像可以点击打开资料弹窗。
- 只有头像可以右键打开头像菜单。

### 6.3 消息字段

```ts
type StarCommunicationMessage = {
  id: string
  channelId: string

  senderSiteId: string
  senderSiteName: string
  senderSiteUrl: string
  senderAvatarUrl?: string
  senderAvatarText?: string
  senderGalaxyName: string

  content: string
  contentType: "text"
  plainText: string
  mentions?: string[]

  status: "normal" | "deleted" | "hidden"

  createdAt: string
  updatedAt?: string
  deletedAt?: string

  clientMessageId?: string
}
```

前端派生字段：

```ts
isOwn = message.senderSiteId === currentSite.siteId
timestamp = format(message.createdAt, "HH:mm")
```

## 7. 新消息下滑提示

### 7.1 可见元素

当有新消息或当前滚动不在底部时，聊天区中间下方显示下滑提示。

当前设计：

- 居中显示
- 无容器背景
- 上方文字：`x 条新消息`
- 下方图标：向下 chevron

### 7.2 字段

```ts
type UnreadState = {
  unreadCount: number
  showScrollBottomButton: boolean
}
```

点击行为：

- 滚动到底部
- `unreadCount = 0`
- 隐藏提示

## 8. 底部消息输入区

### 8.1 可见元素

底部输入区包含：

- 左侧表情按钮
- 右侧消息记录按钮
- 多行 textarea
- 快捷键提示
- 字数统计
- 发送按钮

### 8.2 输入框规则

```md
最大长度：500 字
Enter：发送
Shift + Enter：换行
空内容不能发送
发送中禁用输入框和发送按钮
```

### 8.3 发送接口

```http
POST /v1/star-communications/channels/{channelId}/messages
```

请求：

```ts
type SendMessageRequest = {
  content: string
  contentType: "text"
  clientMessageId: string
}
```

响应：

```ts
type SendMessageResponse = {
  success: true
  data: StarCommunicationMessage
}
```

校验建议：

- `content` trim 后不能为空
- `content` 最大 500 字
- 第一版只允许 `text`
- 后端必须转义 HTML，避免 XSS
- `clientMessageId` 用于防重复提交

## 9. 表情选择气泡

### 9.1 可见元素

点击输入区左侧表情按钮时，在输入框上方浮现气泡面板。

气泡包含：

- 表情网格
- 气泡尖角
- hover 状态

点击表情：

- 将 emoji 追加到输入框
- 自动关闭表情气泡
- 输入框重新聚焦

点击页面其他地方：

- 关闭表情气泡

### 9.2 表情字段

第一版前端可本地写死；如果后端提供，则使用：

```ts
type StarCommunicationEmoji = {
  id: string
  value: string
  label?: string
  group: "default" | "planet" | "custom"
  sort: number
}
```

推荐接口：

```http
GET /v1/star-communications/emojis
```

## 10. 消息记录弹窗

### 10.1 可见元素

点击输入区右侧消息记录图标后打开。

弹窗包含：

- 标题：消息记录
- 关闭按钮
- 搜索输入框
- 连续日志式消息列表

注意：

- 每条消息不是 card。
- 每条消息不是 button。
- 每条消息只是一行连续日志，使用轻分隔线。

每条日志展示：

```md
发送者名称
消息时间
消息摘要
```

### 10.2 搜索规则

搜索匹配：

- 发送者名称
- 发送者 URL
- 消息内容

### 10.3 消息列表接口

```http
GET /v1/star-communications/channels/{channelId}/messages
```

查询参数：

```ts
type MessageListQuery = {
  cursor?: string
  limit?: number
  keyword?: string
  before?: string
  after?: string
}
```

响应：

```ts
type MessageListResponse = {
  success: true
  data: {
    items: StarCommunicationMessage[]
    nextCursor?: string
    hasMore: boolean
  }
}
```

## 11. 头像右键菜单

### 11.1 可见元素

在聊天消息头像上右键时，菜单显示在头像旁边。

菜单包含：

- `@ TA`
- `查看资料`
- `举报`

### 11.2 定位规则

```md
菜单相对星际通讯面板定位
优先显示在头像右侧
自己的消息头像在右侧时，优先显示在头像左侧
不能溢出面板边界
点击外部关闭
```

### 11.3 行为

`@ TA`：

- 在输入框追加 `@站点名称 `
- 聚焦输入框

`查看资料`：

- 打开成员资料弹窗

`举报`：

- 显示 Toast
- 后续可接举报接口

推荐举报接口：

```http
POST /v1/star-communications/messages/{messageId}/reports
```

请求：

```ts
type ReportMessageRequest = {
  reason?: string
}
```

## 12. 成员资料弹窗

### 12.1 可见元素

点击聊天头像或成员列表卡片后打开。

弹窗包含：

- 关闭按钮
- 头像
- 站点名称
- 站点 ID 简写
- 星体信息
- 网址
- 描述
- 加入时间
- 指标信息
- 交换友链/已连友链按钮
- 访问星球按钮
- 最新文章列表

注意：

- 弹窗背景不遮暗后面页面。
- 站点名称后面不加星标。
- 站点名称不可点击。
- 网址不可点击。
- 只有“访问星球”按钮可以跳转。
- “星纪”“指标”这类前置标签不显示，内容直接铺开。

### 12.2 展示规则

```md
标题：siteName
ID：siteId 前 8 位
星体：siteName · galaxyName
网址：siteUrl
描述：description
加入：joinedAt / joinedText
指标：影响 influenceScore · 可信 trustScore · 友链 friendLinkCount 条
```

### 12.3 成员详情接口

```http
GET /v1/star-communications/members/{siteId}
```

响应：

```ts
type MemberDetailResponse = {
  success: true
  data: StarCommunicationMember
}
```

## 13. 最新文章列表

### 13.1 可见元素

成员资料弹窗右侧显示最新文章。

每篇文章包含：

- 标题
- 日期
- 摘要

文章本身可以点击跳转文章 URL。

### 13.2 字段

```ts
type StarCommunicationRecentPost = {
  id: string
  siteId: string
  title: string
  url: string
  summary: string
  publishedAt: string
}
```

推荐接口：

```http
GET /v1/star-communications/members/{siteId}/recent-posts
```

响应：

```ts
type RecentPostsResponse = {
  success: true
  data: {
    items: StarCommunicationRecentPost[]
  }
}
```

## 14. 发起友链邀请状态

当前资料弹窗内，如果成员 `isFriendLinked = false`，显示“交换友链”按钮。

点击后进入邀请表单状态。

### 14.1 可见元素

邀请表单包含：

- 标题：发起星系连接请求
- 分组输入框
- 留言输入框
- 取消按钮
- 发送邀请按钮

### 14.2 请求字段

```ts
type CreateFriendInvitationRequest = {
  peerSiteId: string
  linkGroupName?: string
  message?: string
}
```

推荐接口：

```http
POST /v1/friend-invitations
```

或如果要放在星际通讯命名下：

```http
POST /v1/star-communications/members/{siteId}/friend-invitations
```

## 15. Toast 提示

### 15.1 可见元素

Toast 显示在聊天区底部偏上位置。

类型：

```ts
type ToastType = "success" | "error" | "info"
```

字段：

```ts
type ToastState = {
  message: string
  type: ToastType
}
```

使用场景：

- 举报成功
- 发送失败
- 邀请发送成功
- 内容超过 500 字

## 16. WebSocket 实时事件

建议复用现有 Hub WebSocket，新增事件类型。

### 16.1 新消息事件

```ts
type StarCommunicationMessageCreatedEvent = {
  type: "star_communication.message.created"
  eventId: string
  occurredAt: string
  payload: StarCommunicationMessage
}
```

前端行为：

- 如果当前频道匹配，将消息追加到消息列表
- 如果用户不在底部，增加 unreadCount
- 如果用户在底部，自动滚动到底部

### 16.2 消息更新事件

```ts
type StarCommunicationMessageUpdatedEvent = {
  type: "star_communication.message.updated"
  eventId: string
  occurredAt: string
  payload: {
    id: string
    channelId: string
    status: "normal" | "deleted" | "hidden"
    updatedAt: string
  }
}
```

### 16.3 成员更新事件

```ts
type StarCommunicationMemberUpdatedEvent = {
  type: "star_communication.member.updated"
  eventId: string
  occurredAt: string
  payload: StarCommunicationMember
}
```

前端行为：

- 更新右侧成员列表
- 更新消息中成员名称/头像/星系名称展示
- 更新资料弹窗

## 17. 后端数据库建议

### 17.1 star_communication_channels

```sql
id
name
type
description
member_count
created_at
updated_at
```

### 17.2 star_communication_messages

```sql
id
channel_id
sender_site_id
sender_site_name
sender_site_url
sender_avatar_url
sender_avatar_text
sender_galaxy_name
content
plain_text
content_type
status
client_message_id
created_at
updated_at
deleted_at
```

推荐索引：

```sql
(channel_id, created_at DESC)
(sender_site_id, created_at DESC)
(client_message_id)
```

全文搜索可根据数据库选择：

```sql
plain_text
sender_site_name
sender_site_url
```

### 17.3 成员数据来源

成员数据优先来自现有站点表和关系图数据，不一定要单独建成员表。

如果需要快照，可建：

```sql
star_communication_member_snapshots
```

字段：

```sql
site_id
site_name
site_url
avatar_url
avatar_text
galaxy_name
description
status
is_friend_linked
relation_status
friend_link_count
trust_score
influence_score
rss_status
joined_at
last_active_at
updated_at
```

## 18. 安全与权限

发送消息：

- 必须是已接入 AstraHub 的 active 站点
- 必须通过 Hub API Key 或站点认证
- 内容不能为空
- 内容最多 500 字
- 第一版仅允许 text
- 建议限流：每个站点 10 秒最多 3 条

读取消息：

- 已接入站点可读取 public 频道
- 未接入站点不允许发送

内容安全：

- 后端保存原始 text 时必须按文本处理
- 返回前端时不要返回未清洗 HTML
- `plainText` 用于搜索
- emoji 原样保存

## 19. 当前前端 mock 字段映射

```md
planet.id              -> siteId
planet.name            -> siteName
planet.url             -> siteUrl
planet.avatar          -> avatarText / avatarUrl
planet.galaxy          -> galaxyName
planet.bio             -> description
planet.lastActive      -> lastActiveText / lastActiveAt
planet.trustScore      -> trustScore
planet.influenceScore  -> influenceScore
planet.isFriendLinked  -> isFriendLinked
planet.joinedTime      -> joinedAt / joinedText
planet.friendLinkCount -> friendLinkCount
planet.rssStatus       -> rssStatus

message.id             -> id
message.planetId       -> senderSiteId
message.planetName     -> senderSiteName
message.planetAvatar   -> senderAvatarText / senderAvatarUrl
message.planetUrl      -> senderSiteUrl
message.content        -> content
message.timestamp      -> format(createdAt, "HH:mm")
message.isOwn          -> senderSiteId === currentSite.siteId
message.createdAt      -> createdAt
```

## 20. 第一版后端最小闭环

建议优先实现以下接口：

```http
GET  /v1/star-communications/bootstrap
GET  /v1/star-communications/channels/public/messages
POST /v1/star-communications/channels/public/messages
GET  /v1/star-communications/members/{siteId}
GET  /v1/star-communications/members/{siteId}/recent-posts
```

然后补 WebSocket：

```md
star_communication.message.created
star_communication.message.updated
star_communication.member.updated
```

这样即可覆盖当前前端全部元素和主要交互。
