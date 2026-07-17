# wMatcher

[English](README.md)

一个用于比较两个 JAR，并匹配重命名或混淆类的 Java 桌面工具。

## 使用

```bash
java -jar wMatcher.jar
```

1. 选择旧版 JAR 和新版 JAR。
2. 按需添加依赖 JAR。
3. 开始比较。
4. 检查或修正不确定的匹配。
5. 将工程保存为 `.wmatch` 文件。

## 构建

```bash
./gradlew clean check shadowJar
```

Windows：

```powershell
.\gradlew.bat clean check shadowJar
```

输出：`wmatcher-app/build/libs/wMatcher-x.x.x-all.jar`

## 许可证

[GNU General Public License v3.0](LICENSE)
