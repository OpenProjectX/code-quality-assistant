```shell
rm -rf /data/.gradle/caches/9.3.0/transforms/

./gradlew :plugin-idea:runIde
./gradlew :plugin-idea:buildPlugin -x :plugin-idea:buildSearchableOptions

./gradlew :plugin-idea:publishPlugin -x  :plugin-idea:buildSearchableOptions 

./gradlew :plugin-idea:assemble

./gradlew :plugin-idea:publishPluginZipPublicationToMavenLocal

ls ~/.m2/repository/org/openprojectx/ai/plugin/ai-test-plugin
tree  ~/.m2/repository/org/openprojectx/ai/plugin/ai-test-plugin


./gradlew :plugin-idea:generatePomFileForPluginZipPublication
./gradlew :plugin-idea:publishPluginZipPublicationToMavenLocal

./gradlew :plugin-idea:publishPluginDistributionPublicationToSonatypeRepository

./gradlew :plugin-idea:publishPluginDistributionPublicationToSonatypeRepository closeAndReleaseSonatypeStagingRepository

```

git filter-branch --force --index-filter \
'git rm --cached --ignore-unmatch doc/usage.mp4' \
--prune-empty -- --all

git lfs track "*.gif"