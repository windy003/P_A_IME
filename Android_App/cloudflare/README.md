# PyIME 同步后端(Cloudflare Worker + D1,账号体系版)

App 通过这个 Worker 读写云端 D1 数据库做双向增量同步。数据按账号(owner)隔离,不同账号互不可见。
表会在首次请求时自动创建,无需手动建表。

## 账号规则

- **注册仅限管理员**:调用 `/register` 需带请求头 `X-Admin-Key`,且等于部署时设的机密 `ADMIN_KEY`。
- **普通用户只能登录**:用 `/login` 拿 `token`,之后同步请求带 `X-Auth-Token`。
- 密码以 `SHA-256(salt + 密码)` 存储,不存明文。

## 部署步骤

1. 安装并登录 wrangler:
   ```
   npm i -g wrangler
   wrangler login
   ```

2. 创建 D1 数据库,把输出的 `database_id` 填到 `wrangler.toml`:
   ```
   wrangler d1 create pyime
   ```

3. 设置管理员密钥(注册账号时要在 app 里填的那个):
   ```
   wrangler secret put ADMIN_KEY
   ```
   按提示输入一个你自定的密钥。

4. 部署:
   ```
   wrangler deploy
   ```
   得到地址,形如 `https://pyime-sync.<你的子域>.workers.dev`。

## 在 App 里使用

打开输入法工具栏「☁」按钮 → 进入同步页:

- **注册账号(管理员)**:点底部「注册新账号(管理员)」,填 Worker 地址、`ADMIN_KEY`、新用户名、新密码 → 注册。
- **登录**:填 Worker 地址、用户名、密码 → 登录 → 自动比较两端差异。
- **同步**:差异面板里长按某条可将其排除本次同步;确认后点「增量双向同步」。
  剪贴板两端合并后只保留最近 10 条。

> 同一台手机换不同账号登录时,会先清空本机的剪贴板/常用语,改用新账号的云端数据(会有确认提示)。

## 接口(全部 POST,JSON)

| 路径        | 鉴权头        | body                              | 返回                          |
|-------------|---------------|-----------------------------------|-------------------------------|
| `/register` | `X-Admin-Key` | `{username,password}`             | `{ok:true}`                   |
| `/login`    | —             | `{username,password}`             | `{token,username}`            |
| `/pull`     | `X-Auth-Token`| —                                 | `{clipboard,folders,phrases}` |
| `/push`     | `X-Auth-Token`| `{clipboard,folders,phrases}`     | `{ok:true,applied}`           |
