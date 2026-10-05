# Pi AI 0.85.1 第三方声明

- Package：`@earendil-works/pi-ai@0.85.1`，MIT。
- 上游：https://github.com/earendil-works/pi
- npm gitHead：`d981de1229ef899957bbe968bc8dcda02a21f477`。
- npm integrity：`sha512-+VgVIJDkDO2efYJKEEqvPTH4zmnIaXdAppGbO+vKFA9qy5PdhFiAenuFAkU+oiCSfOC4dMHDyrjdQeL4ZoC5CQ==`。
- 许可证完整文本来源（本次实际下载核对）：https://raw.githubusercontent.com/earendil-works/pi/d981de1229ef899957bbe968bc8dcda02a21f477/LICENSE
- 使用范围：ADR-099 / S15 / MODEL-13 L1 保持。独立 Node 私有桥只调用公开 `providers/openrouter` 的 `openrouterProvider().auth.oauth.login/toAuth`；不深导入内部 dist、不复制 OAuth 实现、不启动 Pi Agent。
- MIT 授权不等于任何服务商账号、订阅或 OAuth client ID 使用授权。OpenRouter 第三方 PKCE S256 依据其官方文档，不需要 client ID，产物是长期 API key。Codex 条件 Unknown，未实现、未启用。
- 安装依赖的其他许可证仍由其各自发行包保留；本声明不替代其许可义务，也不替本仓库选择 LICENSE。

## 上游许可证原文

```text
MIT License

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
