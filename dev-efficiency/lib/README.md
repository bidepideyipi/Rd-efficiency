# CNCCQ 本地介质

Central 无 `cn.cfit.cnccq.jms:cnccq-jms-base`，构建前需将厂商 jar 放到本目录：

- `cnccq-jms-base-V2.0.5.0.jar`（从 `docs/开发手册/CNCCQ JAVA开发介质及手册/` 介质包拷贝）
- `cnccq-jms-base-V2.0.5.0.pom`（本仓库已提供）

安装到本地 Maven 仓：

```bash
./install-cnccq.sh
# 或：mvn -f ../pom.xml validate
```

`*.jar` 已在仓库 `.gitignore` 中，勿提交。
