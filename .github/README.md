<p align="center">
  <a href="https://aechronis.net">
    <picture>
      <img src="logo.png" alt="Aechronis logo">
    </picture>
  </a>
</p>

<p align="center">
  <a href="https://discord.gg/aechronis"><img alt="Discord" src="https://img.shields.io/discord/1407105776669032489?style=flat-square&label=discord" /></a>
  <a href="https://github.com/Aechronis/aechronis/tree/master/.github/workflows"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/Aechronis/aechronis/jvm.yml?style=flat-square" /></a>
  <img alt="Java 25" src="https://img.shields.io/badge/java-25-orange?style=flat-square" />
</p>

---

<p align="center">
  <a href="https://youtu.be/bTeR0es2bIE"><img src="gameplay.gif" alt="Aechronis Gameplay"></a>
</p>

---

### Building

```bash
# run dev environment, automatically reloads when changes are made to modules
./gradlew devRun

# build and run production jars
./gradlew assembleServerDistribution
cd build/distributions/aechronis
java -jar aechronis.jar
```

### Documentation
For more info on the server itself, including specific items, gameplay systems etc. [see our docs](https://aechronis.net). We aim to keep the codebase, for the most part, self-documenting.

### Contributing
If you're interested in contributing to Aechronis, please contact us on [discord](https://discord.gg/aechronis) before submitting a pull request.
