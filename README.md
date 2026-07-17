# wMatcher

[简体中文](README_zh.md)

A Java desktop tool for comparing two JARs and matching renamed or obfuscated classes.

## Usage

```bash
java -jar wMatcher.jar
```

1. Select the old JAR and new JAR.
2. Add dependency JARs if needed.
3. Start the comparison.
4. Review or correct uncertain matches.
5. Save the project as a `.wmatch` file.

## Build

```bash
./gradlew clean check shadowJar
```

Windows:

```powershell
.\gradlew.bat clean check shadowJar
```

Output: `wmatcher-app/build/libs/wMatcher-x.x.x-all.jar`

## License

[GNU General Public License v3.0](LICENSE)
