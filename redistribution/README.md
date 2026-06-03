# AI Test Plugin Redistribution

This is a standalone Gradle project for internal plugin redistribution. It does
not depend on the main source build.

It repackages an existing IntelliJ plugin ZIP by adding a config JAR to the
plugin `lib/` directory. The config JAR contains:

```text
openprojectx/ai-test/config.yaml
```

The plugin treats this classpath config as default configuration. User config in
`~/.codeimprover/.ai-test.yaml` or `~/.ai-test.yaml` still has higher priority.

## Usage

```bash
./gradlew repackagePlugin \
  -PoriginalPluginZip=/path/to/plugin-idea.zip \
  -PconfigFile=/path/to/config.yaml
```

The output is written to:

```text
build/distributions/<original-name>-company.zip
```

Optional properties:

```bash
./gradlew repackagePlugin \
  -PoriginalPluginZip=/path/to/plugin-idea.zip \
  -PconfigFile=/path/to/config.yaml \
  -PoutputZip=/path/to/ai-test-plugin-company.zip \
  -PconfigJarName=company-config.jar
```

Do not put secrets in the config file. The redistributed ZIP and embedded JAR
can be unpacked by anyone who has the file.
