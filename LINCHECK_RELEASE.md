# Lincheck release checklist
Follow these steps to release a new `<version>` of Lincheck.

1. Checkout `develop` branch: <br>
   `git checkout develop`

2. Retrieve the most recent `develop`: <br>
   `git pull`

3. Make sure the `master` branch is fully merged into `develop`: <br>
   `git merge origin/master`

4. Replace `<old-version>` with `<version>`:
   * in [README.md](README.md)
   * in [docs/v.list](docs/v.list) 
   * in [gradle.properties](gradle.properties)
   * in all the published subprojects (`common`, `jvm-agent`, `trace`) `gradle.properties` files 
     * upon new Lincheck release, all subprojects should have the same version

5. Commit the updated [`gradle.properties`](gradle.properties): <br>
   `git add gradle.properties README.md docs/v.list` <br>
   `git commit -m "Release lincheck-<version>"`
   
6. Create a tag related to the releasing `<version>`: <br>
   `git tag lincheck-<version>`

7. Push the release commit and the tag: <br>
   `git push` <br>
   `git push origin lincheck-<version>`

8. Merge the new version into `master`: <br>
   `git checkout master` <br>
   `git merge develop` <br>
   `git push`
   
9. Make sure that the `master` branch build is green on [Teamcity](https://teamcity.jetbrains.com/project/KotlinTools_KotlinxLincheck?branch=%3Cdefault%3E&mode=builds)

10. Press 'deploy' button in the [Teamcity publish configuration](https://teamcity.jetbrains.com/buildConfiguration/KotlinTools_KotlinxLincheck_Publish?branch=%3Cdefault%3E&buildTypeTab=overview&mode=builds). 
Set `releaseVersion` property to `<version>` in the pop-up window. Make sure that the build succeeds.
After the task succeeds, the artifact should be uploaded to https://central.sonatype.com/ automatically (may take several minutes).

11. In [GitHub](https://github.com/JetBrains/lincheck/releases) interface:
    * Create a release named `lincheck-<version>`.
    * Write a release notes message following the old ones as example of style.
    
12. Switch into `develop` branch back: <br>
    `git checkout develop`

13. Update the version to the next `SNAPSHOT` one in [`gradle.properties`](gradle.properties).
    * also update the version in all published subprojects `gradle.properties` files

14. Commit and push the updated [`gradle.properties`](gradle.properties): <br>
   `git add gradle.properties` <br>
   `git commit -m "Prepare for next development iteration"` <br>
   `git push`
    
**Congratulation! You've just released a new Lincheck version!**