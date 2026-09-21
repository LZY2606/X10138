# 语义配置合并器

本地 Web 应用：对同一份服务配置的共同祖先与两个分支做语义三向合并，支持 YAML / JSON。

## 运行

```bash
./gradlew test
./gradlew run --args='--host 127.0.0.1 --port 5221'
# 打开 http://127.0.0.1:5221
```

无需外部进程，全部状态持久化在 `data/` 目录（原始输入、策略版本、裁决历史、合并会话、导出）。

## 语义规则

- 对象按键名合并；数组按路径登记的策略处理：`replace`（整体替换）、`by_id`（按稳定 id 合并，重复 id 报冲突）、`ordered`（有序序列按位合并）。未登记策略且两边都改动时直接报冲突，不猜测。
- 删除字段与设为 `null` 是不同动作；缺失字段与 null 值不混为一谈。
- YAML 锚点/别名展开为独立副本参与比较，输出不产生共享可变引用。
- 人工裁决绑定祖先与两侧内容指纹；任一侧变化后旧裁决仅作为建议，不自动套用。
- 多冲突一次提交使用乐观版本号，过期提交返回 409，不会覆盖他人已解决的项。
- 导出（YAML/JSON）指纹稳定；通过 `/api/bundle` 导出再导入后，路径、来源链与结果指纹保持一致。

## 主要 API

- `POST /api/documents` 添加文档；`PUT /api/policies` 登记数组策略
- `POST /api/merges` 创建合并；`POST /api/merges/{id}/resolve` 批量裁决（带 `expectedVersion`）
- `POST /api/merges/{id}/export` 导出；`GET/POST /api/bundle` 全量导出/导入
